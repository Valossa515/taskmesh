package io.github.valossa515.jobmanager.application.service;

import io.github.valossa515.jobmanager.application.exception.EventPublicationException;
import io.github.valossa515.jobmanager.application.port.IEventPublisher;
import io.github.valossa515.jobmanager.application.port.IOutboxRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

import io.github.valossa515.jobmanager.application.exception.EventPublicationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
public class OutboxPublishingService {

    private final IOutboxRepository outboxRepository;
    private final IEventPublisher eventPublisher;

    private final String publisherId;
    private final int batchSize;
    private final Duration leaseDuration;

    private static final Logger log =
            LoggerFactory.getLogger(OutboxPublishingService.class);

    public OutboxPublishingService(IOutboxRepository outboxRepository, IEventPublisher eventPublisher, String publisherId, int batchSize, Duration leaseDuration) {
        this.outboxRepository = outboxRepository;
        this.eventPublisher = eventPublisher;
        this.publisherId = publisherId;
        this.batchSize = batchSize;
        this.leaseDuration = leaseDuration;
    }

    @Autowired
    public OutboxPublishingService(
            IOutboxRepository outboxRepository,
            IEventPublisher eventPublisher,
            @Value("${taskmesh.publisher.id:job-manager-1}")
            String publisherId,
            @Value("${taskmesh.publisher.batch-size:10}")
            int batchSize,
            @Value("${taskmesh.publisher.lease-seconds:30}")
            long leaseSeconds
    ) {
        if (publisherId == null || publisherId.isBlank()) {
            throw new IllegalArgumentException(
                    "Publisher ID must not be blank"
            );
        }

        if (batchSize <= 0 || leaseSeconds <= 0) {
            throw new IllegalArgumentException(
                    "Invalid publisher configuration"
            );
        }

        this.outboxRepository = outboxRepository;
        this.eventPublisher = eventPublisher;
        this.publisherId = publisherId;
        this.batchSize = batchSize;
        this.leaseDuration = Duration.ofSeconds(leaseSeconds);
    }



    public int publishBatch() {
        int publishedCount = 0;

        for (int processed = 0; processed < batchSize; processed++) {

            var events = outboxRepository.reservePending(
                    publisherId,
                    1,
                    leaseDuration
            );

            if (events.isEmpty()) {
                break;
            }

            var event = events.getFirst();

            try {
                eventPublisher.publish(event);

                boolean confirmed = outboxRepository.markPublished(
                        event.getId(),
                        event.getLeaseToken()
                );

                if (confirmed) {
                    publishedCount++;
                } else {
                    log.warn(
                            "Publication confirmation rejected for event {}",
                            event.getId()
                    );
                }

            } catch (EventPublicationException exception) {
                log.warn(
                        "Failed to publish outbox event {}: {}",
                        event.getId(),
                        exception.getMessage()
                );

                if (Thread.currentThread().isInterrupted()) {
                    break;
                }
            }
        }

        return publishedCount;
    }
}