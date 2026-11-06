
package io.github.valossa515.jobmanager.domain.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class OutboxEventTest {

    private static final Instant NOW =
            Instant.parse("2026-11-06T12:00:00Z");

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

    private OutboxEvent newLeaseEvent() {
        return OutboxEvent.create(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "job.submitted",
                "job.submitted",
                "{}",
                NOW.minusSeconds(60)
        );
    }

    private UUID reserveEvent(OutboxEvent event) {
        UUID token = UUID.randomUUID();

        event.reserve(
                "test-publisher",
                token,
                NOW,
                NOW.plusSeconds(30)
        );

        return token;
    }

    // Existing tests

    @Test
    void shouldCreatePendingEvent() {
        OutboxEvent event = newEvent();

        assertNotNull(event.getId());
        assertEquals(OutboxStatus.PENDING, event.getStatus());
        assertEquals(0, event.getAttempts());
        assertNull(event.getPublishedAt());
        assertNotNull(event.getCreatedAt());

        assertNull(event.getLockedBy());
        assertNull(event.getLockedUntil());
        assertNull(event.getLeaseToken());
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
        OutboxEvent event = newLeaseEvent();
        UUID token = reserveEvent(event);

        event.markPublished(token, NOW.plusSeconds(10));

        assertEquals(OutboxStatus.PUBLISHED, event.getStatus());
        assertEquals(NOW.plusSeconds(10), event.getPublishedAt());

        assertNull(event.getLockedBy());
        assertNull(event.getLockedUntil());
        assertNull(event.getLeaseToken());
    }

    @Test
    void shouldRejectPublishingTwice() {
        OutboxEvent event = newLeaseEvent();
        UUID token = reserveEvent(event);

        event.markPublished(token, NOW.plusSeconds(10));

        assertThrows(
                IllegalStateException.class,
                () -> event.markPublished(
                        token,
                        NOW.plusSeconds(20)
                )
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
                        NOW,
                        null
                )
        );
    }

    // Lease tests

    @Test
    void shouldReserveAvailableEvent() {
        OutboxEvent event = newLeaseEvent();
        UUID token = UUID.randomUUID();

        event.reserve(
                "publisher-1",
                token,
                NOW,
                NOW.plusSeconds(30)
        );

        assertEquals("publisher-1", event.getLockedBy());
        assertEquals(token, event.getLeaseToken());
        assertEquals(
                NOW.plusSeconds(30),
                event.getLockedUntil()
        );

        assertFalse(event.isAvailableAt(NOW));
        assertEquals(OutboxStatus.PENDING, event.getStatus());
    }

    @Test
    void shouldRejectReservationWhileLeaseIsActive() {
        OutboxEvent event = newLeaseEvent();

        reserveEvent(event);

        assertThrows(
                IllegalStateException.class,
                () -> event.reserve(
                        "publisher-2",
                        UUID.randomUUID(),
                        NOW.plusSeconds(10),
                        NOW.plusSeconds(40)
                )
        );
    }

    @Test
    void shouldAllowReservationAfterExpiration() {
        OutboxEvent event = newLeaseEvent();

        UUID oldToken = reserveEvent(event);
        UUID newToken = UUID.randomUUID();

        event.reserve(
                "publisher-2",
                newToken,
                NOW.plusSeconds(30),
                NOW.plusSeconds(60)
        );

        assertEquals("publisher-2", event.getLockedBy());
        assertEquals(newToken, event.getLeaseToken());
        assertNotEquals(oldToken, event.getLeaseToken());
    }

    @Test
    void shouldRejectStalePublisherConfirmation() {
        OutboxEvent event = newLeaseEvent();

        UUID oldToken = reserveEvent(event);
        UUID newToken = UUID.randomUUID();

        event.reserve(
                "publisher-2",
                newToken,
                NOW.plusSeconds(31),
                NOW.plusSeconds(60)
        );

        assertThrows(
                IllegalStateException.class,
                () -> event.markPublished(
                        oldToken,
                        NOW.plusSeconds(32)
                )
        );

        assertEquals(OutboxStatus.PENDING, event.getStatus());
        assertEquals(newToken, event.getLeaseToken());
    }

    @Test
    void shouldPublishWithValidLeaseAndClearReservation() {
        OutboxEvent event = newLeaseEvent();
        UUID token = reserveEvent(event);

        event.markPublished(
                token,
                NOW.plusSeconds(10)
        );

        assertEquals(OutboxStatus.PUBLISHED, event.getStatus());
        assertEquals(
                NOW.plusSeconds(10),
                event.getPublishedAt()
        );

        assertNull(event.getLockedBy());
        assertNull(event.getLockedUntil());
        assertNull(event.getLeaseToken());
    }

    @Test
    void shouldRejectConfirmationAfterLeaseExpiration() {
        OutboxEvent event = newLeaseEvent();
        UUID token = reserveEvent(event);

        assertThrows(
                IllegalStateException.class,
                () -> event.markPublished(
                        token,
                        NOW.plusSeconds(30)
                )
        );

        assertEquals(OutboxStatus.PENDING, event.getStatus());
    }
}
