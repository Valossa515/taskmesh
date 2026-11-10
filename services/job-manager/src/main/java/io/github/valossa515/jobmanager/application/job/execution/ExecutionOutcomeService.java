
package io.github.valossa515.jobmanager.application.job.execution;

import io.github.valossa515.jobmanager.application.port.IDatabaseClock;
import io.github.valossa515.jobmanager.application.port.IJobExecutionRepository;
import io.github.valossa515.jobmanager.application.port.IJobRepository;
import io.github.valossa515.jobmanager.domain.model.Job;
import io.github.valossa515.jobmanager.domain.model.JobExecution;
import io.github.valossa515.jobmanager.domain.model.JobExecutionStatus;
import io.github.valossa515.jobmanager.domain.model.JobStatus;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;

@Service
public class ExecutionOutcomeService {

    private final IJobRepository jobs;
    private final IJobExecutionRepository executions;
    private final IDatabaseClock clock;

    public ExecutionOutcomeService(
            IJobRepository jobs,
            IJobExecutionRepository executions,
            IDatabaseClock clock
    ) {
        this.jobs = jobs;
        this.executions = executions;
        this.clock = clock;
    }

    @Transactional
    public OutcomeResult complete(
            UUID jobId,
            UUID eventId,
            UUID executionId,
            String workerId,
            String workerInstanceId
    ) {
        return transition(
                jobId, eventId, executionId,
                workerId, workerInstanceId,
                Operation.COMPLETE, null
        );
    }

    @Transactional
    public OutcomeResult fail(
            UUID jobId,
            UUID eventId,
            UUID executionId,
            String workerId,
            String workerInstanceId,
            String reason
    ) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException(
                    "Failure reason must not be blank"
            );
        }

        if (JobExecution.LEASE_EXPIRED_REASON.equals(reason)) {
            throw new IllegalArgumentException(
                    "Lease expiration is manager-owned"
            );
        }

        return transition(
                jobId, eventId, executionId,
                workerId, workerInstanceId,
                Operation.FAIL, reason
        );
    }

    private OutcomeResult transition(
            UUID jobId,
            UUID eventId,
            UUID executionId,
            String workerId,
            String workerInstanceId,
            Operation operation,
            String reason
    ) {
        Objects.requireNonNull(jobId);
        Objects.requireNonNull(eventId);
        Objects.requireNonNull(executionId);

        if (workerId == null || workerId.isBlank()
                || workerInstanceId == null
                || workerInstanceId.isBlank()) {
            throw new IllegalArgumentException(
                    "Worker identity is required"
            );
        }

        // Every lifecycle writer locks the Job first.
        Job job = jobs.findByIdForUpdate(jobId)
                .orElseThrow(() ->
                        new NoSuchElementException("Job not found")
                );

        JobExecution execution = executions
                .findByJobId(jobId)
                .orElseThrow(() ->
                        new NoSuchElementException(
                                "Job execution not found"
                        )
                );

        // The outcome must belong to the original grant.
        boolean authorized =
                execution.getExecutionId().equals(executionId)
                        && execution.getEventId().equals(eventId)
                        && execution.getWorkerId().equals(workerId)
                        && execution.getWorkerInstanceId()
                        .equals(workerInstanceId);

        if (!authorized) {
            throw new ExecutionStaleException();
        }

        // An expired grant never accepts worker outcomes.
        if (execution.getStatus() == JobExecutionStatus.EXPIRED) {
            if (job.getStatus() != JobStatus.FAILED
                    || !JobExecution.LEASE_EXPIRED_REASON
                    .equals(job.getFailureReason())) {
                throw new IllegalStateException(
                        "Inconsistent expired execution"
                );
            }

            return OutcomeResult.stale(job);
        }

        // Idempotent replay takes precedence over deadline checks.
        if (job.getStatus() == JobStatus.COMPLETED
                || job.getStatus() == JobStatus.FAILED) {

            boolean sameCompletion =
                    operation == Operation.COMPLETE
                            && job.getStatus() == JobStatus.COMPLETED
                            && execution.getStatus()
                            == JobExecutionStatus.COMPLETED;

            boolean sameFailure =
                    operation == Operation.FAIL
                            && job.getStatus() == JobStatus.FAILED
                            && execution.getStatus()
                            == JobExecutionStatus.FAILED
                            && Objects.equals(
                            execution.getFailureReason(), reason
                    );

            if (sameCompletion || sameFailure) {
                return OutcomeResult.recorded(job);
            }

            throw new OutcomeConflictException();
        }

        if (job.getStatus() != JobStatus.RUNNING
                || execution.getStatus()
                != JobExecutionStatus.ACTIVE) {
            throw new IllegalStateException(
                    "Inconsistent execution lifecycle"
            );
        }

        // Sample PostgreSQL time after acquiring the Job lock.
        Instant now = clock.now();

        if (!now.isBefore(execution.getExpiresAt())) {
            execution.expire(now);

            job.fail(
                    JobExecution.LEASE_EXPIRED_REASON,
                    now
            );

            jobs.save(job);
            executions.save(execution);

            // Return a decision, not an exception.
            // The transaction must commit the expiration.
            return OutcomeResult.stale(job);
        }

        if (operation == Operation.COMPLETE) {
            job.complete(now);
            execution.complete(now);
        } else {
            job.fail(reason, now);
            execution.fail(reason, now);
        }

        jobs.save(job);
        executions.save(execution);

        return OutcomeResult.recorded(job);
    }

    private enum Operation {
        COMPLETE,
        FAIL
    }

    public enum Decision {
        RECORDED,
        STALE
    }

    public record OutcomeResult(
            UUID jobId,
            JobStatus status,
            String failureReason,
            Decision decision
    ) {
        static OutcomeResult recorded(Job job) {
            return new OutcomeResult(
                    job.getId(),
                    job.getStatus(),
                    job.getFailureReason(),
                    Decision.RECORDED
            );
        }

        static OutcomeResult stale(Job job) {
            return new OutcomeResult(
                    job.getId(),
                    job.getStatus(),
                    job.getFailureReason(),
                    Decision.STALE
            );
        }
    }

    public static class ExecutionStaleException
            extends IllegalStateException {

        public ExecutionStaleException() {
            super("Execution is not owned by this worker");
        }
    }

    public static class OutcomeConflictException
            extends IllegalStateException {

        public OutcomeConflictException() {
            super("Execution has a different terminal outcome");
        }
    }
}