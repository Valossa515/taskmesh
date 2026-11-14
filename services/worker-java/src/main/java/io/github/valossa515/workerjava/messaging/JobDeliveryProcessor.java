
package io.github.valossa515.workerjava.messaging;

import io.github.valossa515.workerjava.execution.ExecutionApiClient;
import io.github.valossa515.workerjava.execution.ExecutionApiException;

import org.springframework.amqp.core.Message;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static io.github.valossa515.workerjava.messaging
        .JobProcessingDeferredException.Phase;
import static io.github.valossa515.workerjava.messaging
        .JobProcessingDeferredException.Recovery;

@Component
public class JobDeliveryProcessor {

    private static final String PROBLEM_PREFIX =
            "urn:taskmesh:problem:";

    private static final int MAX_COMMAND_CALLS = 4;
    private static final int MAX_DELIVERY_CALLS = 8;

    private static final long MAX_WAIT_MS = 45_000;
    private static final long ACTIVE_CLAIM_WAIT_MS = 10_000;

    private final JobSubmittedMessageValidator validator;
    private final ExecutionApiClient executionApi;
    private final DeadLetterPublisher deadLetterPublisher;
    private final Clock clock;
    private final Sleeper sleeper;

    @FunctionalInterface
    interface Sleeper {
        void sleep(Duration duration)
                throws InterruptedException;
    }

    @Autowired
    public JobDeliveryProcessor(
            JobSubmittedMessageValidator validator,
            ExecutionApiClient executionApi,
            DeadLetterPublisher deadLetterPublisher,
            Clock clock
    ) {
        this(
                validator,
                executionApi,
                deadLetterPublisher,
                clock,
                duration -> Thread.sleep(duration.toMillis())
        );
    }

    // Tests can replace real waiting with a controlled sleeper.
    JobDeliveryProcessor(
            JobSubmittedMessageValidator validator,
            ExecutionApiClient executionApi,
            DeadLetterPublisher deadLetterPublisher,
            Clock clock,
            Sleeper sleeper
    ) {
        this.validator = Objects.requireNonNull(validator);
        this.executionApi = Objects.requireNonNull(executionApi);
        this.deadLetterPublisher =
                Objects.requireNonNull(deadLetterPublisher);
        this.clock = Objects.requireNonNull(clock);
        this.sleeper = Objects.requireNonNull(sleeper);
    }

    /**
     * Normal return means this delivery may be ACKed.
     * This component never issues the broker ACK itself.
     */
    public void process(Message original) {
        Objects.requireNonNull(original);

        JobSubmittedMessageValidator.ValidatedSubmission submission;

        try {
            submission = validator.validate(
                    original.getBody(),
                    original.getMessageProperties().getMessageId()
            );
        } catch (InvalidJobMessageException exception) {
            deadLetterPublisher.publish(
                    original,
                    exception.reason().name()
            );
            return;
        }

        new Delivery(original, submission).run();
    }

    private final class Delivery {

        private final Message original;
        private final JobSubmittedMessageValidator.ValidatedSubmission
                submission;

        private final UUID claimId = UUID.randomUUID();

        private int totalCalls;
        private int startCalls;
        private int completeCalls;

        private UUID executionId;
        private Instant leaseExpiresAt;

        private Phase phase = Phase.START;
        private String lastFailure = "UNKNOWN";

        private Delivery(
                Message original,
                JobSubmittedMessageValidator.ValidatedSubmission submission
        ) {
            this.original = original;
            this.submission = submission;
        }

        private void run() {
            while (true) {
                if (totalCalls >= MAX_DELIVERY_CALLS) {
                    quarantine(
                            lastFailure + "_HTTP_BUDGET_EXHAUSTED"
                    );
                    return;
                }

                if (phase == Phase.START) {
                    if (startCalls >= MAX_COMMAND_CALLS) {
                        quarantine(
                                lastFailure + "_START_RETRY_EXHAUSTED"
                        );
                        return;
                    }

                    startCalls++;
                    totalCalls++;

                    try {
                        var started = executionApi.start(
                                submission.jobId(),
                                submission.eventId(),
                                claimId
                        );

                        if (started == null || started.status() == null) {
                            pause(Phase.START, null);
                        }

                        if (started.status()
                                == ExecutionApiClient.JobStatus.COMPLETED
                                || started.status()
                                == ExecutionApiClient.JobStatus.FAILED) {
                            return;
                        }

                        if (started.status()
                                != ExecutionApiClient.JobStatus.RUNNING
                                || started.executionId() == null
                                || started.expiresAt() == null) {
                            pause(Phase.START, null);
                        }

                        // Reconciliation cannot silently change the grant.
                        if (executionId != null
                                && !executionId.equals(
                                started.executionId()
                        )) {
                            pause(Phase.START, null);
                        }

                        executionId = started.executionId();
                        leaseExpiresAt = started.expiresAt();

                        if (!leaseExpiresAt.isAfter(clock.instant())) {
                            lastFailure = "START_LEASE_EXPIRED";
                            continue;
                        }

                        phase = Phase.COMPLETE;

                    } catch (ExecutionApiException exception) {
                        if (handleFailure(
                                exception,
                                Phase.START,
                                startCalls
                        )) {
                            return;
                        }
                    }

                    continue;
                }

                // Only COMPLETE is supported for NO_OP.
                // A transport retry does not rerun a workload.
                if (!leaseExpiresAt.isAfter(clock.instant())) {
                    lastFailure = "COMPLETE_LEASE_EXPIRED";
                    phase = Phase.START;
                    continue;
                }

                if (completeCalls >= MAX_COMMAND_CALLS) {
                    quarantine(
                            lastFailure + "_COMPLETE_RETRY_EXHAUSTED"
                    );
                    return;
                }

                completeCalls++;
                totalCalls++;

                try {
                    var completed = executionApi.complete(
                            submission.jobId(),
                            submission.eventId(),
                            executionId
                    );

                    if (completed == null
                            || completed.status()
                            != ExecutionApiClient.JobStatus.COMPLETED) {
                        pause(Phase.COMPLETE, null);
                    }

                    return;

                } catch (ExecutionApiException exception) {
                    if (handleFailure(
                            exception,
                            Phase.COMPLETE,
                            completeCalls
                    )) {
                        return;
                    }
                }
            }
        }

        /**
         * @return true when the message has been quarantined
         *         and processing is finished; false to continue.
         */
        private boolean handleFailure(
                ExecutionApiException exception,
                Phase failedPhase,
                int commandCalls
        ) {
            lastFailure = failedPhase.name()
                    + "_" + exception.kind().name();

            switch (exception.kind()) {
                case QUARANTINE, OUTCOME_CONFLICT -> {
                    quarantine(
                            quarantineReason(failedPhase, exception)
                    );
                    return true;
                }

                case AUTHENTICATION, PROTOCOL -> {
                    pause(failedPhase, exception);
                }

                case TRANSIENT -> {
                    if (commandCalls >= MAX_COMMAND_CALLS
                            || totalCalls >= MAX_DELIVERY_CALLS) {
                        quarantine(
                                lastFailure + "_RETRY_EXHAUSTED"
                        );
                        return true;
                    }

                    waitBeforeRetry(
                            failedPhase,
                            commandCalls,
                            exception,
                            false
                    );
                    return false;
                }

                case ACTIVE_CLAIM -> {
                    if (failedPhase != Phase.START) {
                        pause(failedPhase, exception);
                    }

                    if (commandCalls >= MAX_COMMAND_CALLS
                            || totalCalls >= MAX_DELIVERY_CALLS) {
                        quarantine(
                                lastFailure + "_RETRY_EXHAUSTED"
                        );
                        return true;
                    }

                    waitBeforeRetry(
                            failedPhase,
                            commandCalls,
                            exception,
                            true
                    );
                    return false;
                }

                case STALE_EXECUTION -> {
                    if (failedPhase != Phase.COMPLETE) {
                        pause(failedPhase, exception);
                    }

                    // Ask the Job Manager about the original
                    // submission, using the same claimId.
                    phase = Phase.START;
                    return false;
                }
            }

            throw new IllegalStateException(
                    "Unrecognized execution failure"
            );
        }

        private void waitBeforeRetry(
                Phase failedPhase,
                int commandCalls,
                ExecutionApiException exception,
                boolean activeClaim
        ) {
            long backoff =
                    1000L << (commandCalls - 1);

            long minimum = activeClaim
                    ? Math.max(
                    backoff,
                    ACTIVE_CLAIM_WAIT_MS
            )
                    : backoff;

            long jitter =
                    ThreadLocalRandom.current()
                            .nextLong(0, 251);

            long retryAfter =
                    parseRetryAfter(exception.retryAfter());

            long delay = Math.max(
                    minimum + jitter,
                    retryAfter
            );

            if (delay > MAX_WAIT_MS) {
                // Do not violate a large Retry-After value
                // by retrying earlier than the server requested.
                pause(failedPhase, exception);
            }

            try {
                sleeper.sleep(Duration.ofMillis(delay));

            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();

                throw new JobProcessingDeferredException(
                        Recovery.PAUSE_INTAKE,
                        failedPhase,
                        interrupted
                );
            }
        }

        private long parseRetryAfter(String value) {
            if (value == null || value.isBlank()) {
                return 0;
            }

            String raw = value.trim();

            try {
                if (raw.matches("[0-9]+")) {
                    long seconds = Long.parseLong(raw);
                    return Math.multiplyExact(seconds, 1000L);
                }

                Instant deadline = ZonedDateTime.parse(
                        raw,
                        DateTimeFormatter.RFC_1123_DATE_TIME
                ).toInstant();

                return Math.max(
                        0,
                        Duration.between(
                                clock.instant(),
                                deadline
                        ).toMillis()
                );
            } catch (NumberFormatException | ArithmeticException exception) {
                return MAX_WAIT_MS + 1;

            } catch (Exception exception) {
                // A malformed header is ignored; the normal
                // exponential backoff still applies.
                return 0;
            }
        }

        private void quarantine(String reason) {
            deadLetterPublisher.publish(original, reason);
        }
    }

    private String quarantineReason(
            Phase phase,
            ExecutionApiException exception
    ) {
        String problemType = exception.problemType();

        if (problemType != null
                && problemType.startsWith(PROBLEM_PREFIX)) {
            String suffix = problemType
                    .substring(PROBLEM_PREFIX.length())
                    .replace('-', '_')
                    .toUpperCase(Locale.ROOT);

            String candidate =
                    phase.name() + "_" + suffix;

            if (candidate.matches("[A-Z0-9_]{1,64}")) {
                return candidate;
            }
        }

        return phase.name()
                + "_"
                + exception.kind().name();
    }

    private void pause(
            Phase phase,
            Throwable cause
    ) {
        throw new JobProcessingDeferredException(
                Recovery.PAUSE_INTAKE,
                phase,
                cause
        );
    }
}