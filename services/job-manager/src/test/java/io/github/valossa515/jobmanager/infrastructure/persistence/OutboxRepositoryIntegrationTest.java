
package io.github.valossa515.jobmanager.infrastructure.persistence;

import io.github.valossa515.jobmanager.application.port.IJobRepository;
import io.github.valossa515.jobmanager.application.port.IOutboxRepository;
import io.github.valossa515.jobmanager.domain.model.Job;
import io.github.valossa515.jobmanager.domain.model.OutboxEvent;
import io.github.valossa515.jobmanager.domain.model.OutboxStatus;
import io.github.valossa515.jobmanager.support.IntegrationTestConfig;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Import(IntegrationTestConfig.class)
@Transactional
class OutboxRepositoryIntegrationTest {

    @Autowired
    private IJobRepository jobRepository;

    @Autowired
    private IOutboxRepository outboxRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private void publishEvent(OutboxEvent event) {
        Instant now = Instant.now();
        UUID token = UUID.randomUUID();

        event.reserve(
                "test-publisher",
                token,
                now,
                now.plusSeconds(30)
        );

        event.recordAttempt();
        event.markPublished(
                token,
                now.plusSeconds(1)
        );
    }

    @Test
    void shouldPersistPendingOutboxEvent() {
        Job job = jobRepository.save(
                Job.create("NO_OP", "{}")
        );

        OutboxEvent event = OutboxEvent.create(
                job.getId(),
                "job.submitted",
                "job.submitted",
                """
                {
                  "eventType": "job.submitted",
                  "schemaVersion": "1.0"
                }
                """
        );

        outboxRepository.save(event);

        entityManager.flush();
        entityManager.clear();

        OutboxEvent restored = outboxRepository
                .findById(event.getId())
                .orElseThrow();

        assertEquals(event.getId(), restored.getId());
        assertEquals(job.getId(), restored.getAggregateId());
        assertEquals(OutboxStatus.PENDING, restored.getStatus());
        assertEquals(0, restored.getAttempts());
        assertNull(restored.getPublishedAt());

        assertNull(restored.getLockedBy());
        assertNull(restored.getLockedUntil());
        assertNull(restored.getLeaseToken());

        String payloadType = jdbcTemplate.queryForObject(
                """
                SELECT jsonb_typeof(payload)
                FROM outbox_events
                WHERE id = ?
                """,
                String.class,
                event.getId()
        );

        assertEquals("object", payloadType);
    }

    @Test
    void shouldPersistPublishedOutboxEvent() {
        Job job = jobRepository.save(
                Job.create("NO_OP", "{}")
        );

        OutboxEvent event = OutboxEvent.create(
                job.getId(),
                "job.submitted",
                "job.submitted",
                "{}"
        );

        publishEvent(event);

        outboxRepository.save(event);

        entityManager.flush();
        entityManager.clear();

        OutboxEvent restored = outboxRepository
                .findById(event.getId())
                .orElseThrow();

        assertEquals(
                OutboxStatus.PUBLISHED,
                restored.getStatus()
        );
        assertEquals(1, restored.getAttempts());
        assertNotNull(restored.getPublishedAt());

        assertNull(restored.getLockedBy());
        assertNull(restored.getLockedUntil());
        assertNull(restored.getLeaseToken());
    }

    @Test
    void shouldReturnOnlyPendingEventsOrderedByCreationDate() {
        Job job = jobRepository.save(
                Job.create("NO_OP", "{}")
        );

        Instant base = Instant.parse(
                "2026-11-05T12:00:00Z"
        );

        OutboxEvent oldest = OutboxEvent.create(
                UUID.randomUUID(),
                job.getId(),
                "job.submitted",
                "job.submitted",
                "{}",
                base
        );

        OutboxEvent middle = OutboxEvent.create(
                UUID.randomUUID(),
                job.getId(),
                "job.submitted",
                "job.submitted",
                "{}",
                base.plusSeconds(10)
        );

        OutboxEvent newest = OutboxEvent.create(
                UUID.randomUUID(),
                job.getId(),
                "job.submitted",
                "job.submitted",
                "{}",
                base.plusSeconds(20)
        );

        OutboxEvent published = OutboxEvent.create(
                UUID.randomUUID(),
                job.getId(),
                "job.submitted",
                "job.submitted",
                "{}",
                base.minusSeconds(10)
        );

        publishEvent(published);

        // Insert deliberately out of order.
        outboxRepository.save(newest);
        outboxRepository.save(published);
        outboxRepository.save(oldest);
        outboxRepository.save(middle);

        entityManager.flush();
        entityManager.clear();

        List<OutboxEvent> results =
                outboxRepository.findPending(2);

        assertEquals(2, results.size());
        assertEquals(
                oldest.getId(),
                results.get(0).getId()
        );
        assertEquals(
                middle.getId(),
                results.get(1).getId()
        );
    }

    @Test
    void shouldUseIdToBreakTimestampTies() {
        Job job = jobRepository.save(
                Job.create("NO_OP", "{}")
        );

        Instant createdAt = Instant.parse(
                "2026-11-05T12:00:00Z"
        );

        OutboxEvent first = OutboxEvent.create(
                UUID.fromString(
                        "00000000-0000-0000-0000-000000000001"
                ),
                job.getId(),
                "job.submitted",
                "job.submitted",
                "{}",
                createdAt
        );

        OutboxEvent second = OutboxEvent.create(
                UUID.fromString(
                        "00000000-0000-0000-0000-000000000002"
                ),
                job.getId(),
                "job.submitted",
                "job.submitted",
                "{}",
                createdAt
        );

        outboxRepository.save(second);
        outboxRepository.save(first);

        entityManager.flush();
        entityManager.clear();

        List<OutboxEvent> results =
                outboxRepository.findPending(10);

        assertEquals(
                first.getId(),
                results.get(0).getId()
        );
        assertEquals(
                second.getId(),
                results.get(1).getId()
        );
    }


    @Test
    void shouldRejectInvalidBatchLimit() {
        assertThrows(
                InvalidDataAccessApiUsageException.class,
                () -> outboxRepository.findPending(0)
        );

        assertThrows(
                InvalidDataAccessApiUsageException.class,
                () -> outboxRepository.findPending(-1)
        );
    }


    @Test
    void shouldPersistActiveLeaseAndRestoreIt() {
        Job job = jobRepository.save(
                Job.create("NO_OP", "{}")
        );

        OutboxEvent event = OutboxEvent.create(
                job.getId(),
                "job.submitted",
                "job.submitted",
                "{}"
        );

        Instant now = Instant.parse(
                "2026-11-06T12:00:00Z"
        );

        UUID token = UUID.randomUUID();

        event.reserve(
                "publisher-1",
                token,
                now,
                now.plusSeconds(30)
        );

        outboxRepository.save(event);

        entityManager.flush();
        entityManager.clear();

        OutboxEvent restored = outboxRepository
                .findById(event.getId())
                .orElseThrow();

        assertEquals(
                OutboxStatus.PENDING,
                restored.getStatus()
        );

        assertEquals(
                "publisher-1",
                restored.getLockedBy()
        );

        assertEquals(
                token,
                restored.getLeaseToken()
        );

        assertEquals(
                now.plusSeconds(30),
                restored.getLockedUntil()
        );

        assertFalse(restored.isAvailableAt(now));

        assertTrue(
                restored.isAvailableAt(now.plusSeconds(30))
        );
    }


    @Test
    void shouldReservePendingEvents() {
        Job job = jobRepository.save(
                Job.create("NO_OP", "{}")
        );

        OutboxEvent first = OutboxEvent.create(
                job.getId(),
                "job.submitted",
                "job.submitted",
                "{}"
        );

        OutboxEvent second = OutboxEvent.create(
                job.getId(),
                "job.submitted",
                "job.submitted",
                "{}"
        );

        outboxRepository.save(first);
        outboxRepository.save(second);

        entityManager.flush();
        entityManager.clear();

        List<OutboxEvent> reserved =
                outboxRepository.reservePending(
                        "publisher-1",
                        2,
                        Duration.ofSeconds(30)
                );

        assertEquals(2, reserved.size());

        for (OutboxEvent event : reserved) {
            assertEquals(
                    OutboxStatus.PENDING,
                    event.getStatus()
            );

            assertEquals(
                    "publisher-1",
                    event.getLockedBy()
            );

            assertNotNull(event.getLeaseToken());
            assertNotNull(event.getLockedUntil());
        }

        assertNotEquals(
                reserved.get(0).getLeaseToken(),
                reserved.get(1).getLeaseToken()
        );
    }
}
