package io.github.valossa515.jobmanager.infrastructure.persistence;

import io.github.valossa515.jobmanager.domain.model.OutboxStatus;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "outbox_events")
public class OutboxJpaEntity {

    @Id
    private UUID id;

    @Column(name = "aggregate_id", nullable = false)
    private UUID aggregateId;

    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;

    @Column(name = "routing_key", nullable = false, length = 150)
    private String routingKey;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private OutboxStatus status;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    protected OutboxJpaEntity() {
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getAggregateId() { return aggregateId; }
    public void setAggregateId(UUID aggregateId) {
        this.aggregateId = aggregateId;
    }

    public String getEventType() { return eventType; }
    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    public String getRoutingKey() { return routingKey; }
    public void setRoutingKey(String routingKey) {
        this.routingKey = routingKey;
    }

    public String getPayload() { return payload; }
    public void setPayload(String payload) {
        this.payload = payload;
    }

    public OutboxStatus getStatus() { return status; }
    public void setStatus(OutboxStatus status) {
        this.status = status;
    }

    public int getAttempts() { return attempts; }
    public void setAttempts(int attempts) {
        this.attempts = attempts;
    }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getPublishedAt() { return publishedAt; }
    public void setPublishedAt(Instant publishedAt) {
        this.publishedAt = publishedAt;
    }
}