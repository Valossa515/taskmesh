
package io.github.valossa515.jobmanager.domain.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public class OutboxEvent {

    private final UUID id;
    private final UUID aggregateId;
    private final String eventType;
    private final String routingKey;
    private final String payload;
    private final Instant createdAt;

    private OutboxStatus status;
    private int attempts;
    private Instant publishedAt;

    private String lockedBy;
    private Instant lockedUntil;
    private UUID leaseToken;

    private OutboxEvent(
            UUID id,
            UUID aggregateId,
            String eventType,
            String routingKey,
            String payload,
            OutboxStatus status,
            int attempts,
            Instant createdAt,
            Instant publishedAt,
            String lockedBy,
            Instant lockedUntil,
            UUID leaseToken
    ) {
        this.id = Objects.requireNonNull(id);
        this.aggregateId = Objects.requireNonNull(aggregateId);
        this.eventType = requireText(eventType);
        this.routingKey = requireText(routingKey);
        this.payload = requireText(payload);
        this.status = Objects.requireNonNull(status);
        this.createdAt = Objects.requireNonNull(createdAt);

        if (attempts < 0) {
            throw new IllegalArgumentException(
                    "Attempts cannot be negative"
            );
        }

        if (status == OutboxStatus.PENDING
                && publishedAt != null) {
            throw new IllegalArgumentException(
                    "Pending event cannot have publishedAt"
            );
        }

        if (status == OutboxStatus.PUBLISHED
                && publishedAt == null) {
            throw new IllegalArgumentException(
                    "Published event requires publishedAt"
            );
        }

        boolean hasLease = lockedBy != null
                || lockedUntil != null
                || leaseToken != null;

        if (hasLease) {
            if (status != OutboxStatus.PENDING
                    || lockedBy == null
                    || lockedBy.isBlank()
                    || lockedUntil == null
                    || leaseToken == null) {
                throw new IllegalArgumentException(
                        "Invalid outbox lease"
                );
            }
        }

        this.attempts = attempts;
        this.publishedAt = publishedAt;
        this.lockedBy = lockedBy;
        this.lockedUntil = lockedUntil;
        this.leaseToken = leaseToken;
    }

    public static OutboxEvent create(
            UUID aggregateId,
            String eventType,
            String routingKey,
            String payload
    ) {
        return create(
                UUID.randomUUID(),
                aggregateId,
                eventType,
                routingKey,
                payload,
                Instant.now()
        );
    }

    public static OutboxEvent create(
            UUID id,
            UUID aggregateId,
            String eventType,
            String routingKey,
            String payload,
            Instant createdAt
    ) {
        return new OutboxEvent(
                id,
                aggregateId,
                eventType,
                routingKey,
                payload,
                OutboxStatus.PENDING,
                0,
                createdAt,
                null,
                null,
                null,
                null
        );
    }

    public static OutboxEvent restore(
            UUID id,
            UUID aggregateId,
            String eventType,
            String routingKey,
            String payload,
            OutboxStatus status,
            int attempts,
            Instant createdAt,
            Instant publishedAt
    ) {
        return restore(
                id, aggregateId, eventType, routingKey,
                payload, status, attempts, createdAt,
                publishedAt, null, null, null
        );
    }

    public static OutboxEvent restore(
            UUID id,
            UUID aggregateId,
            String eventType,
            String routingKey,
            String payload,
            OutboxStatus status,
            int attempts,
            Instant createdAt,
            Instant publishedAt,
            String lockedBy,
            Instant lockedUntil,
            UUID leaseToken
    ) {
        return new OutboxEvent(
                id, aggregateId, eventType, routingKey,
                payload, status, attempts, createdAt,
                publishedAt, lockedBy, lockedUntil, leaseToken
        );
    }

    public boolean isAvailableAt(Instant now) {
        Objects.requireNonNull(now);

        return status == OutboxStatus.PENDING
                && (lockedUntil == null
                || !lockedUntil.isAfter(now));
    }

    public void reserve(
            String publisherId,
            UUID token,
            Instant now,
            Instant expiresAt
    ) {
        Objects.requireNonNull(now);

        if (publisherId == null || publisherId.isBlank()) {
            throw new IllegalArgumentException(
                    "Publisher ID must not be blank"
            );
        }

        Objects.requireNonNull(token, "token is required");
        Objects.requireNonNull(expiresAt, "expiresAt is required");

        if (!expiresAt.isAfter(now)) {
            throw new IllegalArgumentException(
                    "Lease expiration must be after current time"
            );
        }

        if (!isAvailableAt(now)) {
            throw new IllegalStateException(
                    "Event is not available for reservation"
            );
        }

        this.lockedBy = publisherId;
        this.lockedUntil = expiresAt;
        this.leaseToken = token;
    }

    public void recordAttempt() {
        if (status != OutboxStatus.PENDING) {
            throw new IllegalStateException(
                    "Cannot retry a published event"
            );
        }

        this.attempts = Math.incrementExact(attempts);
    }

    public void markPublished(UUID token, Instant now) {
        Objects.requireNonNull(token, "token is required");
        Objects.requireNonNull(now, "now is required");

        if (status != OutboxStatus.PENDING) {
            throw new IllegalStateException(
                    "Event is already published"
            );
        }

        if (leaseToken == null || !leaseToken.equals(token)) {
            throw new IllegalStateException(
                    "Invalid lease token"
            );
        }

        if (lockedUntil == null || !lockedUntil.isAfter(now)) {
            throw new IllegalStateException(
                    "Lease has expired"
            );
        }

        this.status = OutboxStatus.PUBLISHED;
        this.publishedAt = now;

        this.lockedBy = null;
        this.lockedUntil = null;
        this.leaseToken = null;
    }

    private static String requireText(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "Value cannot be blank"
            );
        }

        return value;
    }

    public UUID getId() { return id; }
    public UUID getAggregateId() { return aggregateId; }
    public String getEventType() { return eventType; }
    public String getRoutingKey() { return routingKey; }
    public String getPayload() { return payload; }
    public OutboxStatus getStatus() { return status; }
    public int getAttempts() { return attempts; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getPublishedAt() { return publishedAt; }

    public String getLockedBy() { return lockedBy; }
    public Instant getLockedUntil() { return lockedUntil; }
    public UUID getLeaseToken() { return leaseToken; }
}
