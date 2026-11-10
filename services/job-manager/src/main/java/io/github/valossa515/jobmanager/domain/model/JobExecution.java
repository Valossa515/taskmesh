package io.github.valossa515.jobmanager.domain.model;

import java.time.Instant;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;

public class JobExecution {
    public static final String LEASE_EXPIRED_REASON =
            "EXECUTION_LEASE_EXPIRED";

    private final UUID jobId;
    private final UUID executionId;
    private final UUID eventId;
    private final UUID claimId;

    private final String workerId;
    private final String workerInstanceId;

    private JobExecutionStatus status;

    private final Instant grantedAt;
    private final Instant expiresAt;

    private Instant finishedAt;
    private String failureReason;

    private JobExecution(
            UUID jobId,
            UUID executionId,
            UUID eventId,
            UUID claimId,
            String workerId,
            String workerInstanceId,
            JobExecutionStatus status,
            Instant grantedAt,
            Instant expiresAt,
            Instant finishedAt,
            String failureReason
    ) {
        this.jobId = Objects.requireNonNull(jobId);
        this.executionId = Objects.requireNonNull(executionId);
        this.eventId = Objects.requireNonNull(eventId);
        this.claimId = Objects.requireNonNull(claimId);

        this.workerId = requireNotBlank(workerId);
        this.workerInstanceId = requireNotBlank(workerInstanceId);

        this.status = Objects.requireNonNull(status);
        this.grantedAt = Objects.requireNonNull(grantedAt);
        this.expiresAt = Objects.requireNonNull(expiresAt);

        this.finishedAt = finishedAt;
        this.failureReason = failureReason;

        validateInvariants();
    }

    public static JobExecution grant(
            UUID jobId,
            UUID eventId,
            UUID claimId,
            String workerId,
            String workerInstanceId,
            Instant grantedAt,
            Duration lease
    ) {
        Objects.requireNonNull(grantedAt);
        Objects.requireNonNull(lease);

        if (lease.isZero() || lease.isNegative()) {
            throw new IllegalArgumentException(
                    "Lease duration must be positive"
            );
        }

        return new JobExecution(
                jobId,
                UUID.randomUUID(),
                eventId,
                claimId,
                workerId,
                workerInstanceId,
                JobExecutionStatus.ACTIVE,
                grantedAt,
                grantedAt.plus(lease),
                null,
                null
        );
    }

    public static JobExecution restore(
            UUID jobId,
            UUID executionId,
            UUID eventId,
            UUID claimId,
            String workerId,
            String workerInstanceId,
            JobExecutionStatus status,
            Instant grantedAt,
            Instant expiresAt,
            Instant finishedAt,
            String failureReason
    ) {
        return new JobExecution(
                jobId, executionId, eventId, claimId,
                workerId, workerInstanceId, status,
                grantedAt, expiresAt, finishedAt, failureReason
        );
    }

    public void complete(Instant now) {
        requireActiveBeforeDeadline(now);

        status = JobExecutionStatus.COMPLETED;
        finishedAt = now;
    }

    public void fail(String reason, Instant now) {
        requireActiveBeforeDeadline(now);
        requireNotBlank(reason);

        status = JobExecutionStatus.FAILED;
        failureReason = reason;
        finishedAt = now;
    }

    public void expire(Instant now) {
        Objects.requireNonNull(now);
        requireActive();

        if (now.isBefore(expiresAt)) {
            throw new IllegalStateException(
                    "Execution lease has not expired"
            );
        }

        status = JobExecutionStatus.EXPIRED;
        failureReason = LEASE_EXPIRED_REASON;
        finishedAt = now;
    }

    private void requireActiveBeforeDeadline(Instant now) {
        Objects.requireNonNull(now);
        requireActive();

        if (now.isBefore(grantedAt) || !now.isBefore(expiresAt)) {
            throw new IllegalStateException(
                    "Execution is outside its lease"
            );
        }
    }

    private void requireActive() {
        if (status != JobExecutionStatus.ACTIVE) {
            throw new IllegalStateException(
                    "Execution is already terminal"
            );
        }
    }

    private void validateInvariants() {
        if (!expiresAt.isAfter(grantedAt)) {
            throw new IllegalArgumentException(
                    "expiresAt must be after grantedAt"
            );
        }

        if (status == JobExecutionStatus.ACTIVE) {
            if (finishedAt != null || failureReason != null) {
                throw new IllegalArgumentException(
                        "Active execution cannot have a result"
                );
            }
            return;
        }

        if (finishedAt == null || finishedAt.isBefore(grantedAt)) {
            throw new IllegalArgumentException(
                    "Terminal execution requires a valid finishedAt"
            );
        }

        if (status == JobExecutionStatus.EXPIRED) {
            if (finishedAt.isBefore(expiresAt)
                    || !LEASE_EXPIRED_REASON.equals(failureReason)) {
                throw new IllegalArgumentException(
                        "Invalid expired execution"
                );
            }
            return;
        }

        if (!finishedAt.isBefore(expiresAt)) {
            throw new IllegalArgumentException(
                    "Execution result is after lease expiration"
            );
        }

        if (status == JobExecutionStatus.FAILED) {
            requireNotBlank(failureReason);
        } else if (failureReason != null) {
            throw new IllegalArgumentException(
                    "Completed execution cannot have a failure reason"
            );
        }
    }

    private static String requireNotBlank(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "Value must not be blank"
            );
        }
        return value;
    }

    public UUID getJobId() { return jobId; }
    public UUID getExecutionId() { return executionId; }
    public UUID getEventId() { return eventId; }
    public UUID getClaimId() { return claimId; }
    public String getWorkerId() { return workerId; }
    public String getWorkerInstanceId() { return workerInstanceId; }
    public JobExecutionStatus getStatus() { return status; }
    public Instant getGrantedAt() { return grantedAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public String getFailureReason() { return failureReason; }
}
