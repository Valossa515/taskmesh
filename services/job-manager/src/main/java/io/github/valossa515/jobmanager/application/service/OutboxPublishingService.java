
package io.github.valossa515.jobmanager.application.service;

import io.github.valossa515.jobmanager.application.exception.EventPublicationException;
import io.github.valossa515.jobmanager.application.port.IEventPublisher;
import io.github.valossa515.jobmanager.application.port.IOutboxMetrics;
import io.github.valossa515.jobmanager.application.port.IOutboxRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Objects;

@Service
public class OutboxPublishingService {

    private static final Logger log =
            LoggerFactory.getLogger(OutboxPublishingService.class);

    private final IOutboxRepository outboxRepository;
    private final IEventPublisher eventPublisher;
    private final IOutboxMetrics outboxMetrics;

    private final String publisherId;
    private final int batchSize;
    private final Duration leaseDuration;

    public OutboxPublishingService(
            IOutboxRepository outboxRepository,
            IEventPublisher eventPublisher,
            IOutboxMetrics outboxMetrics,
            String publisherId,
            int batchSize,
            Duration leaseDuration
    ) {
        this.outboxRepository =
                Objects.requireNonNull(outboxRepository);

        this.eventPublisher =
                Objects.requireNonNull(eventPublisher);

        this.outboxMetrics =
                Objects.requireNonNull(outboxMetrics);

        if (publisherId == null || publisherId.isBlank()) {
            throw new IllegalArgumentException(
                    "Publisher ID must not be blank"
            );
        }

        if (batchSize <= 0) {
            throw new IllegalArgumentException(
                    "Batch size must be positive"
            );
        }

        if (leaseDuration == null
                || leaseDuration.isNegative()
                || leaseDuration.isZero()) {
            throw new IllegalArgumentException(
                    "Lease duration must be positive"
            );
        }

        this.publisherId = publisherId;
        this.batchSize = batchSize;
        this.leaseDuration = leaseDuration;
    }

    @Autowired
    public OutboxPublishingService(
            IOutboxRepository outboxRepository,
            IEventPublisher eventPublisher,
            IOutboxMetrics outboxMetrics,
            @Value("${taskmesh.publisher.id:job-manager-1}")
            String publisherId,
            @Value("${taskmesh.publisher.batch-size:10}")
            int batchSize,
            @Value("${taskmesh.publisher.lease-seconds:30}")
            long leaseSeconds
    ) {
        this(
                outboxRepository,
                eventPublisher,
                outboxMetrics,
                publisherId,
                batchSize,
                Duration.ofSeconds(leaseSeconds)
        );
    }

    public int publishBatch() {
        long startedAt = System.nanoTime();

        try {
            int publishedCount = 0;

            for (int processed = 0;
                 processed < batchSize;
                 processed++) {

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

                    boolean confirmed =
                            outboxRepository.markPublished(
                                    event.getId(),
                                    event.getLeaseToken()
                            );

                    if (confirmed) {
                        publishedCount++;
                        outboxMetrics.recordPublished();
                    } else {
                        outboxMetrics.recordLeaseLost();

                        log.warn(
                                "Publication confirmation rejected for event {}",
                                event.getId()
                        );
                    }

                } catch (EventPublicationException exception) {
                    outboxMetrics.recordFailed();

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

        } finally {
            outboxMetrics.recordBatchDuration(
                    Duration.ofNanos(
                            System.nanoTime() - startedAt
                    )
            );
        }
    }
}