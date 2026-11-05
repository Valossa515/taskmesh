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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Import(IntegrationTestConfig.class)
@Transactional
public class OutboxRepositoryIntegrationTest {

    @Autowired
    private IJobRepository jobRepository;

    @Autowired
    private IOutboxRepository outboxRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

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

        event.recordAttempt();
        event.markPublished();

        outboxRepository.save(event);

        entityManager.flush();
        entityManager.clear();

        OutboxEvent restored = outboxRepository
                .findById(event.getId())
                .orElseThrow();

        assertEquals(OutboxStatus.PUBLISHED, restored.getStatus());
        assertEquals(1, restored.getAttempts());
        assertNotNull(restored.getPublishedAt());
    }
}
