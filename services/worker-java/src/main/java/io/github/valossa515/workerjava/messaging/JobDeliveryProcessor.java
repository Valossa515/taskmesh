
package io.github.valossa515.workerjava.messaging;

import io.github.valossa515.workerjava.execution.ExecutionApiClient;
import io.github.valossa515.workerjava.execution.ExecutionApiException;

import org.springframework.amqp.core.Message;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

import static io.github.valossa515.workerjava.messaging
        .JobProcessingDeferredException.Phase;
import static io.github.valossa515.workerjava.messaging
        .JobProcessingDeferredException.Recovery;

@Component
public class JobDeliveryProcessor {

    private static final String PROBLEM_PREFIX =
            "urn:taskmesh:problem:";

    private final JobSubmittedMessageValidator validator;
    private final ExecutionApiClient executionApi;
    private final DeadLetterPublisher deadLetterPublisher;
    private final Clock clock;

    public JobDeliveryProcessor(
            JobSubmittedMessageValidator validator,
            ExecutionApiClient executionApi,
            DeadLetterPublisher deadLetterPublisher,
            Clock clock
    ) {
        this.validator = Objects.requireNonNull(validator);
        this.executionApi = Objects.requireNonNull(executionApi);
        this.deadLetterPublisher =
                Objects.requireNonNull(deadLetterPublisher);
        this.clock = Objects.requireNonNull(clock);
    }

    /**
     * A normal return means the source delivery is eligible
     * for acknowledgement. This method never sends an ACK.
     *
     * Recovery is intentionally not implemented here yet.
     * The future retry loop must preserve claimId and
     * executionId across uncertain HTTP responses.
     */
    public void process(Message original) {
        Objects.requireNonNull(original);

        JobSubmittedMessageValidator.ValidatedSubmission
                submission;

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

        // One claim identity for this delivery attempt.
        // It must be reused by the future retry loop.
        UUID claimId = UUID.randomUUID();

        ExecutionApiClient.StartResult started;

        try {
            started = executionApi.start(
                    submission.jobId(),
                    submission.eventId(),
                    claimId
            );
        } catch (ExecutionApiException exception) {
            handleFailure(
                    original,
                    exception,
                    Phase.START
            );
            return;
        }

        if (started == null || started.status() == null) {
            throw deferred(
                    Recovery.PAUSE_INTAKE,
                    Phase.START,
                    null
            );
        }

        // Terminal observation: another delivery may have
        // already completed the Job. Do not run it again.
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
            throw deferred(
                    Recovery.PAUSE_INTAKE,
                    Phase.START,
                    null
            );
        }

        // Do not report a late outcome knowingly.
        // The Job Manager remains authoritative.
        if (!started.expiresAt().isAfter(clock.instant())) {
            throw deferred(
                    Recovery.RECONCILE_START,
                    Phase.START,
                    null
            );
        }

        // NO_OP has no external side effects.
        // Do not repeat this workload during outcome retries.
        try {
            ExecutionApiClient.OutcomeResult completed =
                    executionApi.complete(
                            submission.jobId(),
                            submission.eventId(),
                            started.executionId()
                    );

            if (completed == null
                    || completed.status()
                    != ExecutionApiClient.JobStatus.COMPLETED) {
                throw deferred(
                        Recovery.PAUSE_INTAKE,
                        Phase.COMPLETE,
                        null
                );
            }

        } catch (ExecutionApiException exception) {
            handleFailure(
                    original,
                    exception,
                    Phase.COMPLETE
            );
        }
    }

    private void handleFailure(
            Message original,
            ExecutionApiException exception,
            Phase phase
    ) {
        switch (exception.kind()) {
            case QUARANTINE, OUTCOME_CONFLICT ->
                    deadLetterPublisher.publish(
                            original,
                            quarantineReason(phase, exception)
                    );

            case TRANSIENT ->
                    throw deferred(
                            Recovery.RETRY_SAME_COMMAND,
                            phase,
                            exception
                    );

            case ACTIVE_CLAIM ->
                    throw deferred(
                            Recovery.WAIT_FOR_ACTIVE_CLAIM,
                            phase,
                            exception
                    );

            case STALE_EXECUTION ->
                    throw deferred(
                            Recovery.RECONCILE_START,
                            phase,
                            exception
                    );

            case AUTHENTICATION, PROTOCOL ->
                    throw deferred(
                            Recovery.PAUSE_INTAKE,
                            phase,
                            exception
                    );
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

    private JobProcessingDeferredException deferred(
            Recovery recovery,
            Phase phase,
            Throwable cause
    ) {
        return new JobProcessingDeferredException(
                recovery,
                phase,
                cause
        );
    }
}