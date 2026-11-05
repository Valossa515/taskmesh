
package io.github.valossa515.jobmanager.domain.model;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class OutboxEventTest {

    private OutboxEvent newEvent() {
        return OutboxEvent.create(
                UUID.randomUUID(),
                "job.submitted",
                "job.submitted",
                """
                {"eventType":"job.submitted"}
                """
        );
    }

    @Test
    void shouldCreatePendingEvent() {
        OutboxEvent event = newEvent();

        assertNotNull(event.getId());
        assertEquals(OutboxStatus.PENDING, event.getStatus());
        assertEquals(0, event.getAttempts());
        assertNull(event.getPublishedAt());
        assertNotNull(event.getCreatedAt());
    }

    @Test
    void shouldRecordPublishingAttempt() {
        OutboxEvent event = newEvent();

        event.recordAttempt();

        assertEquals(1, event.getAttempts());
        assertEquals(OutboxStatus.PENDING, event.getStatus());
    }

    @Test
    void shouldMarkEventAsPublished() {
        OutboxEvent event = newEvent();

        event.markPublished();

        assertEquals(OutboxStatus.PUBLISHED, event.getStatus());
        assertNotNull(event.getPublishedAt());
    }

    @Test
    void shouldRejectPublishingTwice() {
        OutboxEvent event = newEvent();
        event.markPublished();

        assertThrows(
                IllegalStateException.class,
                event::markPublished
        );
    }

    @Test
    void shouldRejectNegativeAttemptsOnRestore() {
        assertThrows(
                IllegalArgumentException.class,
                () -> OutboxEvent.restore(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        "job.submitted",
                        "job.submitted",
                        "{}",
                        OutboxStatus.PENDING,
                        -1,
                        java.time.Instant.now(),
                        null
                )
        );
    }
}