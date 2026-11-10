
package io.github.valossa515.jobmanager.application.job.execution;

import io.github.valossa515.jobmanager.application.port.IDatabaseClock;
import io.github.valossa515.jobmanager.application.port.IJobExecutionRepository;
import io.github.valossa515.jobmanager.application.port.IJobRepository;
import io.github.valossa515.jobmanager.application.port.IOutboxRepository;
import io.github.valossa515.jobmanager.domain.model.Job;
import io.github.valossa515.jobmanager.domain.model.JobExecution;
import io.github.valossa515.jobmanager.domain.model.JobExecutionStatus;
import io.github.valossa515.jobmanager.domain.model.JobStatus;
import io.github.valossa515.jobmanager.domain.model.OutboxEvent;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;

@Service
public class ExecutionClaimService {

    private static final Duration LEASE =
            Duration.ofSeconds(30);

    private final IJobRepository jobs;
    private final IJobExecutionRepository executions;
    private final IOutboxRepository events;
    private final IDatabaseClock clock;

    public ExecutionClaimService(
            IJobRepository jobs,
            IJobExecutionRepository executions,
            IOutboxRepository events,
            IDatabaseClock clock
    ) {
        this.jobs = jobs;
        this.executions = executions;
        this.events = events;
        this.clock = clock;
    }

    @Transactional
    public StartResult start(
            UUID jobId,
            UUID eventId,
            UUID claimId,
            String workerId,
            String workerInstanceId
    ) {
        Objects.requireNonNull(jobId, "jobId is required");
        Objects.requireNonNull(eventId, "eventId is required");
        Objects.requireNonNull(claimId, "claimId is required");

        if (workerId == null || workerId.isBlank()
                || workerInstanceId == null
                || workerInstanceId.isBlank()) {
            throw new IllegalArgumentException(
                    "Worker identity is required"
            );
        }

        // Serialize execution decisions for the same Job.
        Job job = jobs.findByIdForUpdate(jobId)
                .orElseThrow(() ->
                        new NoSuchElementException(
                                "Job not found"
                        )
                );

        // Validate the submission event.
        OutboxEvent event = events.findById(eventId)
                .orElseThrow(
                        SubmissionMismatchException::new
                );

        if (!event.getAggregateId().equals(jobId)
                || !"job.submitted".equals(event.getEventType())) {
            throw new SubmissionMismatchException();
        }

        // Only NO_OP is supported in Milestone 03.
        if (!"NO_OP".equals(job.getType())) {
            throw new UnsupportedJobTypeException();
        }

        // Use PostgreSQL time after acquiring the lock.
        Instant now = clock.now();

        return switch (job.getStatus()) {
            case PENDING -> grant(
                    job,
                    eventId,
                    claimId,
                    workerId,
                    workerInstanceId,
                    now
            );

            case RUNNING -> handleRunning(
                    job,
                    eventId,
                    claimId,
                    workerId,
                    workerInstanceId,
                    now
            );

            case COMPLETED, FAILED ->
                    StartResult.terminal(job);
        };
    }

    private StartResult grant(
            Job job,
            UUID eventId,
            UUID claimId,
            String workerId,
            String workerInstanceId,
            Instant now
    ) {
        JobExecution execution = JobExecution.grant(
                job.getId(),
                eventId,
                claimId,
                workerId,
                workerInstanceId,
                now,
                LEASE
        );

        job.start(now);

        // Both writes belong to the same transaction.
        jobs.save(job);
        executions.save(execution);

        return StartResult.active(execution);
    }

    private StartResult handleRunning(
            Job job,
            UUID eventId,
            UUID claimId,
            String workerId,
            String workerInstanceId,
            Instant now
    ) {
        JobExecution execution = executions
                .findByJobId(job.getId())
                .orElseThrow(() ->
                        new IllegalStateException(
                                "Running job has no execution"
                        )
                );

        if (execution.getStatus()
                != JobExecutionStatus.ACTIVE) {
            throw new IllegalStateException(
                    "Inconsistent execution state"
            );
        }

        // Expire the execution when its lease is no longer valid.
        if (!now.isBefore(execution.getExpiresAt())) {
            execution.expire(now);

            job.fail(
                    JobExecution.LEASE_EXPIRED_REASON,
                    now
            );

            executions.save(execution);
            jobs.save(job);

            return StartResult.terminal(job);
        }

        // Idempotent retry by the original worker instance.
        boolean sameClaim =
                execution.getEventId().equals(eventId)
                        && execution.getClaimId().equals(claimId)
                        && execution.getWorkerId().equals(workerId)
                        && execution.getWorkerInstanceId()
                        .equals(workerInstanceId);

        if (sameClaim) {
            return StartResult.active(execution);
        }

        throw new ExecutionAlreadyClaimedException();
    }

    public record StartResult(
            JobStatus status,
            UUID executionId,
            Instant expiresAt,
            String failureReason
    ) {
        static StartResult active(JobExecution execution) {
            return new StartResult(
                    JobStatus.RUNNING,
                    execution.getExecutionId(),
                    execution.getExpiresAt(),
                    null
            );
        }

        static StartResult terminal(Job job) {
            return new StartResult(
                    job.getStatus(),
                    null,
                    null,
                    job.getFailureReason()
            );
        }
    }

    public static class ExecutionAlreadyClaimedException
            extends IllegalStateException {

        public ExecutionAlreadyClaimedException() {
            super("Job already has an active execution");
        }
    }

    public static class SubmissionMismatchException
            extends IllegalStateException {

        public SubmissionMismatchException() {
            super("Submission does not match job");
        }
    }

    public static class UnsupportedJobTypeException
            extends IllegalStateException {

        public UnsupportedJobTypeException() {
            super("Unsupported job type");
        }
    }
}