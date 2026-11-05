
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

    private OutboxEvent(
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

        this.attempts = attempts;
        this.publishedAt = publishedAt;
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
        return new OutboxEvent(
                id,
                aggregateId,
                eventType,
                routingKey,
                payload,
                status,
                attempts,
                createdAt,
                publishedAt
        );
    }

    public void recordAttempt() {
        if (status != OutboxStatus.PENDING) {
            throw new IllegalStateException(
                    "Cannot retry a published event"
            );
        }

        this.attempts = Math.incrementExact(attempts);
    }

    public void markPublished() {
        if (status != OutboxStatus.PENDING) {
            throw new IllegalStateException(
                    "Event is already published"
            );
        }

        this.status = OutboxStatus.PUBLISHED;
        this.publishedAt = Instant.now();
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
}
