
package io.github.valossa515.jobmanager.application.service;

import io.github.valossa515.jobmanager.application.exception.EventPublicationException;
import io.github.valossa515.jobmanager.application.port.IEventPublisher;
import io.github.valossa515.jobmanager.application.port.IOutboxRepository;
import io.github.valossa515.jobmanager.domain.model.OutboxEvent;
import io.github.valossa515.jobmanager.application.port.IOutboxMetrics;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
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

    @Mock
    private IOutboxMetrics outboxMetrics;

    private OutboxPublishingService service;

    @BeforeEach
    void setUp() {
        service = new OutboxPublishingService(
                outboxRepository,
                eventPublisher,
                outboxMetrics,
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
                PUBLISHER_ID, 1, LEASE_DURATION
        )).thenReturn(
                List.of(first),
                List.of(second),
                List.of()
        );

        when(outboxRepository.markPublished(
                first.getId(), first.getLeaseToken()
        )).thenReturn(true);

        when(outboxRepository.markPublished(
                second.getId(), second.getLeaseToken()
        )).thenReturn(true);

        int published = service.publishBatch();

        assertEquals(2, published);

        InOrder order = inOrder(
                outboxRepository,
                eventPublisher
        );

        order.verify(outboxRepository).reservePending(
                PUBLISHER_ID, 1, LEASE_DURATION
        );
        order.verify(eventPublisher).publish(first);
        order.verify(outboxRepository).markPublished(
                first.getId(), first.getLeaseToken()
        );

        order.verify(outboxRepository).reservePending(
                PUBLISHER_ID, 1, LEASE_DURATION
        );
        order.verify(eventPublisher).publish(second);
        order.verify(outboxRepository).markPublished(
                second.getId(), second.getLeaseToken()
        );

        order.verify(outboxRepository).reservePending(
                PUBLISHER_ID, 1, LEASE_DURATION
        );

        order.verifyNoMoreInteractions();

        verify(outboxMetrics, times(2)).recordPublished();
    }

    @Test
    void shouldContinueProcessingAfterPublicationFailure() {
        OutboxEvent first = reservedEvent();
        OutboxEvent second = reservedEvent();

        when(outboxRepository.reservePending(
                PUBLISHER_ID, 1, LEASE_DURATION
        )).thenReturn(
                List.of(first),
                List.of(second),
                List.of()
        );

        doThrow(new EventPublicationException(
                "Simulated RabbitMQ failure"
        )).when(eventPublisher).publish(first);

        when(outboxRepository.markPublished(
                second.getId(), second.getLeaseToken()
        )).thenReturn(true);

        int published = service.publishBatch();

        assertEquals(1, published);

        verify(eventPublisher).publish(first);
        verify(eventPublisher).publish(second);

        verify(outboxRepository, never()).markPublished(
                first.getId(), first.getLeaseToken()
        );

        verify(outboxRepository).markPublished(
                second.getId(), second.getLeaseToken()
        );

        verify(outboxMetrics).recordFailed();
    }

    @Test
    void shouldNotCountEventWhenLeaseIsLost() {
        OutboxEvent event = reservedEvent();

        when(outboxRepository.reservePending(
                PUBLISHER_ID, 1, LEASE_DURATION
        )).thenReturn(
                List.of(event),
                List.of()
        );

        when(outboxRepository.markPublished(
                event.getId(), event.getLeaseToken()
        )).thenReturn(false);

        int published = service.publishBatch();

        assertEquals(0, published);
        verify(eventPublisher).publish(event);

        verify(outboxRepository).markPublished(
                event.getId(), event.getLeaseToken()
        );
    }

    @Test
    void shouldReturnZeroWhenNoEventsAreAvailable() {
        when(outboxRepository.reservePending(
                PUBLISHER_ID, 1, LEASE_DURATION
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
                PUBLISHER_ID, 1, LEASE_DURATION
        )).thenReturn(
                List.of(event),
                List.of()
        );

        doThrow(new EventPublicationException(
                "Broker confirmation timed out"
        )).when(eventPublisher).publish(event);

        int published = service.publishBatch();

        assertEquals(0, published);

        verify(eventPublisher).publish(event);
        verify(outboxRepository, never())
                .markPublished(any(), any());

        verify(outboxMetrics).recordFailed();
    }

    @Test
    void shouldRespectBatchSizeLimit() {
        OutboxEvent first = reservedEvent();
        OutboxEvent second = reservedEvent();

        OutboxPublishingService limitedService =
                new OutboxPublishingService(
                        outboxRepository,
                        eventPublisher,
                        outboxMetrics,
                        PUBLISHER_ID,
                        2,
                        LEASE_DURATION
                );

        when(outboxRepository.reservePending(
                PUBLISHER_ID, 1, LEASE_DURATION
        )).thenReturn(
                List.of(first),
                List.of(second)
        );

        when(outboxRepository.markPublished(
                any(UUID.class),
                any(UUID.class)
        )).thenReturn(true);

        int published = limitedService.publishBatch();

        assertEquals(2, published);

        verify(outboxRepository, times(2)).reservePending(
                PUBLISHER_ID, 1, LEASE_DURATION
        );

        verify(eventPublisher, times(2))
                .publish(any(OutboxEvent.class));
    }
}