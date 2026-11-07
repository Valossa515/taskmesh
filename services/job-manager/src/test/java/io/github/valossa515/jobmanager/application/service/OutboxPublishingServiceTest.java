
package io.github.valossa515.jobmanager.application.service;

import io.github.valossa515.jobmanager.application.exception.EventPublicationException;
import io.github.valossa515.jobmanager.application.port.IEventPublisher;
import io.github.valossa515.jobmanager.application.port.IOutboxRepository;
import io.github.valossa515.jobmanager.domain.model.OutboxEvent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OutboxPublishingServiceTest {

    private static final String PUBLISHER_ID = "publisher-test";
    private static final int BATCH_SIZE = 10;
    private static final Duration LEASE_DURATION =
            Duration.ofSeconds(30);

    @Mock
    private IOutboxRepository outboxRepository;

    @Mock
    private IEventPublisher eventPublisher;

    private OutboxPublishingService service;

    @BeforeEach
    void setUp() {
        service = new OutboxPublishingService(
                outboxRepository,
                eventPublisher,
                PUBLISHER_ID,
                BATCH_SIZE,
                30
        );
    }

    private OutboxEvent reservedEvent() {
        Instant now = Instant.now();

        OutboxEvent event = OutboxEvent.create(
                UUID.randomUUID(),
                "job.submitted",
                "job.submitted",
                "{}"
        );

        event.reserve(
                PUBLISHER_ID,
                UUID.randomUUID(),
                now,
                now.plusSeconds(30)
        );

        return event;
    }

    @Test
    void shouldPublishAndConfirmAllEvents() {
        OutboxEvent first = reservedEvent();
        OutboxEvent second = reservedEvent();

        when(outboxRepository.reservePending(
                PUBLISHER_ID,
                BATCH_SIZE,
                LEASE_DURATION
        )).thenReturn(List.of(first, second));

        when(outboxRepository.markPublished(
                first.getId(),
                first.getLeaseToken()
        )).thenReturn(true);

        when(outboxRepository.markPublished(
                second.getId(),
                second.getLeaseToken()
        )).thenReturn(true);

        int published = service.publishBatch();

        assertEquals(2, published);

        verify(eventPublisher).publish(first);
        verify(eventPublisher).publish(second);

        verify(outboxRepository).markPublished(
                first.getId(),
                first.getLeaseToken()
        );

        verify(outboxRepository).markPublished(
                second.getId(),
                second.getLeaseToken()
        );
    }

    @Test
    void shouldContinueProcessingAfterPublicationFailure() {
        OutboxEvent first = reservedEvent();
        OutboxEvent second = reservedEvent();

        when(outboxRepository.reservePending(
                PUBLISHER_ID,
                BATCH_SIZE,
                LEASE_DURATION
        )).thenReturn(List.of(first, second));

        doThrow(new EventPublicationException(
                "Simulated RabbitMQ failure"
        )).when(eventPublisher).publish(first);

        when(outboxRepository.markPublished(
                second.getId(),
                second.getLeaseToken()
        )).thenReturn(true);

        int published = service.publishBatch();

        assertEquals(1, published);

        verify(eventPublisher).publish(first);
        verify(eventPublisher).publish(second);

        verify(outboxRepository, never()).markPublished(
                first.getId(),
                first.getLeaseToken()
        );

        verify(outboxRepository).markPublished(
                second.getId(),
                second.getLeaseToken()
        );
    }

    @Test
    void shouldNotCountEventWhenLeaseIsLost() {
        OutboxEvent event = reservedEvent();

        when(outboxRepository.reservePending(
                PUBLISHER_ID,
                BATCH_SIZE,
                LEASE_DURATION
        )).thenReturn(List.of(event));

        when(outboxRepository.markPublished(
                event.getId(),
                event.getLeaseToken()
        )).thenReturn(false);

        int published = service.publishBatch();

        assertEquals(0, published);

        verify(eventPublisher).publish(event);

        verify(outboxRepository).markPublished(
                event.getId(),
                event.getLeaseToken()
        );
    }

    @Test
    void shouldReturnZeroWhenNoEventsAreAvailable() {
        when(outboxRepository.reservePending(
                PUBLISHER_ID,
                BATCH_SIZE,
                LEASE_DURATION
        )).thenReturn(List.of());

        int published = service.publishBatch();

        assertEquals(0, published);

        verifyNoInteractions(eventPublisher);

        verify(outboxRepository, never())
                .markPublished(any(), any());
    }

    @Test
    void shouldNeverConfirmEventAfterPublicationFailure() {
        OutboxEvent event = reservedEvent();

        when(outboxRepository.reservePending(
                PUBLISHER_ID,
                BATCH_SIZE,
                LEASE_DURATION
        )).thenReturn(List.of(event));

        doThrow(new EventPublicationException(
                "Broker confirmation timed out"
        )).when(eventPublisher).publish(event);

        int published = service.publishBatch();

        assertEquals(0, published);

        verify(eventPublisher).publish(event);

        verify(outboxRepository, never()).markPublished(
                any(), any()
        );
    }
}