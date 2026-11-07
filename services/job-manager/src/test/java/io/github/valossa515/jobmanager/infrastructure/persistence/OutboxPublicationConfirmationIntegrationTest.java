
package io.github.valossa515.jobmanager.infrastructure.persistence;

import io.github.valossa515.jobmanager.application.port.IJobRepository;
import io.github.valossa515.jobmanager.application.port.IOutboxRepository;
import io.github.valossa515.jobmanager.domain.model.Job;
import io.github.valossa515.jobmanager.domain.model.OutboxEvent;
import io.github.valossa515.jobmanager.domain.model.OutboxStatus;
import io.github.valossa515.jobmanager.support.IntegrationTestConfig;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Import(IntegrationTestConfig.class)
class OutboxPublicationConfirmationIntegrationTest {

    @Autowired
    private IJobRepository jobRepository;

    @Autowired
    private IOutboxRepository outboxRepository;

    private OutboxEvent createReservedEvent(
            UUID token,
            Instant lockedUntil
    ) {
        Job job = jobRepository.save(
                Job.create("NO_OP", "{}")
        );

        OutboxEvent event = OutboxEvent.restore(
                UUID.randomUUID(),
                job.getId(),
                "job.submitted",
                "job.submitted",
                "{}",
                OutboxStatus.PENDING,
                0,
                Instant.now().minusSeconds(300),
                null,
                "publisher-A",
                lockedUntil,
                token
        );

        return outboxRepository.save(event);
    }

    @Test
    void shouldConfirmPublicationWithValidLease() {
        UUID token = UUID.randomUUID();

        OutboxEvent event = createReservedEvent(
                token,
                Instant.now().plusSeconds(300)
        );

        boolean confirmed = outboxRepository.markPublished(
                event.getId(),
                token
        );

        assertTrue(confirmed);

        OutboxEvent restored = outboxRepository
                .findById(event.getId())
                .orElseThrow();

        assertEquals(
                OutboxStatus.PUBLISHED,
                restored.getStatus()
        );

        assertNotNull(restored.getPublishedAt());

        assertNull(restored.getLockedBy());
        assertNull(restored.getLockedUntil());
        assertNull(restored.getLeaseToken());
    }

    @Test
    void shouldRejectPublicationWithWrongToken() {
        UUID correctToken = UUID.randomUUID();
        UUID wrongToken = UUID.randomUUID();

        OutboxEvent event = createReservedEvent(
                correctToken,
                Instant.now().plusSeconds(300)
        );

        boolean confirmed = outboxRepository.markPublished(
                event.getId(),
                wrongToken
        );

        assertFalse(confirmed);

        OutboxEvent restored = outboxRepository
                .findById(event.getId())
                .orElseThrow();

        assertEquals(
                OutboxStatus.PENDING,
                restored.getStatus()
        );

        assertNull(restored.getPublishedAt());

        assertEquals(
                correctToken,
                restored.getLeaseToken()
        );

        assertEquals(
                "publisher-A",
                restored.getLockedBy()
        );
    }

    @Test
    void shouldRejectPublicationWithExpiredLease() {
        UUID token = UUID.randomUUID();

        OutboxEvent event = createReservedEvent(
                token,
                Instant.now().minusSeconds(60)
        );

        boolean confirmed = outboxRepository.markPublished(
                event.getId(),
                token
        );

        assertFalse(confirmed);

        OutboxEvent restored = outboxRepository
                .findById(event.getId())
                .orElseThrow();

        assertEquals(
                OutboxStatus.PENDING,
                restored.getStatus()
        );

        assertNull(restored.getPublishedAt());
        assertEquals(token, restored.getLeaseToken());
    }
}