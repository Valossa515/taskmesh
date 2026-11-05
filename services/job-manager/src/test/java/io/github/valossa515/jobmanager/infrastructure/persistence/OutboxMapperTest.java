package io.github.valossa515.jobmanager.infrastructure.persistence;

import io.github.valossa515.jobmanager.domain.model.OutboxEvent;
import io.github.valossa515.jobmanager.domain.model.OutboxStatus;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

public class OutboxMapperTest {

    @Test
    void shouldMapPendingEventToEntityAndBack() {
        OutboxEvent original = OutboxEvent.create(
                UUID.randomUUID(),
                "job.submitted",
                "job.submitted",
                """
                {"eventType":"job.submitted"}
                """
        );

        OutboxJpaEntity entity = OutboxMapper.toEntity(original);
        OutboxEvent restored = OutboxMapper.toDomain(entity);

        assertEquals(original.getId(), restored.getId());
        assertEquals(original.getAggregateId(), restored.getAggregateId());
        assertEquals(original.getEventType(), restored.getEventType());
        assertEquals(original.getRoutingKey(), restored.getRoutingKey());
        assertEquals(original.getPayload(), restored.getPayload());
        assertEquals(OutboxStatus.PENDING, restored.getStatus());
        assertEquals(0, restored.getAttempts());
        assertEquals(original.getCreatedAt(), restored.getCreatedAt());
        assertNull(restored.getPublishedAt());
    }

    @Test
    void shouldPreservePublishedState() {
        OutboxEvent original = OutboxEvent.create(
                UUID.randomUUID(),
                "job.submitted",
                "job.submitted",
                "{}"
        );

        original.recordAttempt();
        original.markPublished();

        OutboxEvent restored = OutboxMapper.toDomain(
                OutboxMapper.toEntity(original)
        );

        assertEquals(OutboxStatus.PUBLISHED, restored.getStatus());
        assertEquals(1, restored.getAttempts());
        assertEquals(original.getPublishedAt(), restored.getPublishedAt());
    }
}