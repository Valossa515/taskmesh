package io.github.valossa515.jobmanager.domain.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public class Job {

    private final UUID id;
    private final String type;
    private final String payload;
    private JobStatus status;
    private final Instant createdAt;
    private Instant startedAt;
    private Instant completedAt;
    private String failureReason;

    private Job(UUID id, String type, String payload, JobStatus status, Instant createdAt,
                Instant startedAt, Instant completedAt, String failureReason) {
        Objects.requireNonNull(id, "id must not be null");
        requireNotBlank(type, "type");
        requireNotBlank(payload, "payload");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        validateStateInvariants(status, createdAt, startedAt, completedAt, failureReason);

        this.id = id;
        this.type = type;
        this.payload = payload;
        this.status = status;
        this.createdAt = createdAt;
        this.startedAt = startedAt;
        this.completedAt = completedAt;
        this.failureReason = failureReason;
    }

    private static void requireNotBlank(String value, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }

    private static void validateStateInvariants(JobStatus status, Instant createdAt, Instant startedAt,
                                                Instant completedAt, String failureReason) {
        boolean requiresStartedAt = status != JobStatus.PENDING;
        boolean requiresCompletedAt = status == JobStatus.COMPLETED || status == JobStatus.FAILED;
        boolean requiresFailureReason = status == JobStatus.FAILED;

        if (requiresStartedAt != (startedAt != null)) {
            throw new IllegalArgumentException("startedAt must %sbe set when status is %s"
                    .formatted(requiresStartedAt ? "" : "not ", status));
        }
        if (requiresCompletedAt != (completedAt != null)) {
            throw new IllegalArgumentException("completedAt must %sbe set when status is %s"
                    .formatted(requiresCompletedAt ? "" : "not ", status));
        }
        if (requiresFailureReason) {
            if (failureReason == null || failureReason.isBlank()) {
                throw new IllegalArgumentException("failureReason must not be blank when status is FAILED");
            }
        } else if (failureReason != null) {
            throw new IllegalArgumentException("failureReason must not be set when status is " + status);
        }

        if (startedAt != null && startedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("startedAt must not be before createdAt");
        }
        if (completedAt != null && completedAt.isBefore(startedAt)) {
            throw new IllegalArgumentException("completedAt must not be before startedAt");
        }
    }

    public static Job create(String type, String payload) {
        return new Job(UUID.randomUUID(), type, payload, JobStatus.PENDING, Instant.now(),
                null, null, null);
    }

    public static Job restore(UUID id, String type, String payload, JobStatus status, Instant createdAt,
                              Instant startedAt, Instant completedAt, String failureReason) {
        return new Job(id, type, payload, status, createdAt, startedAt, completedAt, failureReason);
    }


    public void start() {
        start(Instant.now());
    }

    public void start(Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        requireStatus(JobStatus.PENDING);

        if (now.isBefore(createdAt)) {
            throw new IllegalArgumentException(
                    "startedAt must not be before createdAt"
            );
        }

        this.status = JobStatus.RUNNING;
        this.startedAt = now;
    }

    public void complete() {
        requireStatus(JobStatus.RUNNING);
        this.status = JobStatus.COMPLETED;
        this.completedAt = Instant.now();
    }

    public void fail(String reason) {
        fail(reason, Instant.now());
    }

    public void fail(String reason, Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        requireStatus(JobStatus.RUNNING);

        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException(
                    "Failure reason must not be blank"
            );
        }

        if (now.isBefore(startedAt)) {
            throw new IllegalArgumentException(
                    "completedAt must not be before startedAt"
            );
        }

        this.status = JobStatus.FAILED;
        this.completedAt = now;
        this.failureReason = reason;
    }

    private void requireStatus(JobStatus expected) {
        if (status != expected) {
            throw new IllegalStateException(
                    "Job %s must be %s but is %s".formatted(id, expected, status));
        }
    }

    public UUID getId() {
        return id;
    }

    public String getType() {
        return type;
    }

    public String getPayload() {
        return payload;
    }

    public JobStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public String getFailureReason() {
        return failureReason;
    }
}
