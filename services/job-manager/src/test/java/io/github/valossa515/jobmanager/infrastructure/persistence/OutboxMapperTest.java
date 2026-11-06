
package io.github.valossa515.jobmanager.infrastructure.persistence;

import io.github.valossa515.jobmanager.domain.model.OutboxEvent;
import io.github.valossa515.jobmanager.domain.model.OutboxStatus;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class OutboxMapperTest {

    private static final Instant NOW =
            Instant.parse("2026-11-06T12:00:00Z");

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
        assertNull(restored.getLockedBy());
        assertNull(restored.getLockedUntil());
        assertNull(restored.getLeaseToken());
    }

    @Test
    void shouldPreservePublishedState() {
        OutboxEvent original = OutboxEvent.create(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "job.submitted",
                "job.submitted",
                "{}",
                NOW.minusSeconds(60)
        );

        UUID token = UUID.randomUUID();

        original.reserve(
                "test-publisher",
                token,
                NOW,
                NOW.plusSeconds(30)
        );

        original.recordAttempt();
        original.markPublished(
                token,
                NOW.plusSeconds(10)
        );

        OutboxEvent restored = OutboxMapper.toDomain(
                OutboxMapper.toEntity(original)
        );

        assertEquals(OutboxStatus.PUBLISHED, restored.getStatus());
        assertEquals(1, restored.getAttempts());
        assertEquals(
                original.getPublishedAt(),
                restored.getPublishedAt()
        );

        assertNull(restored.getLockedBy());
        assertNull(restored.getLockedUntil());
        assertNull(restored.getLeaseToken());
    }


    @Test
    void shouldPreserveActiveLeaseWhenMapping() {
        Instant now = Instant.parse(
                "2026-11-06T12:00:00Z"
        );

        OutboxEvent original = OutboxEvent.create(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "job.submitted",
                "job.submitted",
                "{}",
                now.minusSeconds(60)
        );

        UUID token = UUID.randomUUID();

        original.reserve(
                "publisher-1",
                token,
                now,
                now.plusSeconds(30)
        );

        OutboxJpaEntity entity =
                OutboxMapper.toEntity(original);

        OutboxEvent restored =
                OutboxMapper.toDomain(entity);

        assertEquals(
                original.getId(),
                restored.getId()
        );

        assertEquals(
                original.getLockedBy(),
                restored.getLockedBy()
        );

        assertEquals(
                original.getLockedUntil(),
                restored.getLockedUntil()
        );

        assertEquals(
                original.getLeaseToken(),
                restored.getLeaseToken()
        );
    }
}
