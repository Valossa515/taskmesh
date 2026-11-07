package io.github.valossa515.jobmanager.application.port;


import io.github.valossa515.jobmanager.domain.model.OutboxEvent;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface IOutboxRepository {
    OutboxEvent save(OutboxEvent event);
    Optional<OutboxEvent> findById(UUID id);
    List<OutboxEvent> findPending(int limit);
    List<OutboxEvent> reservePending(
            String publisherId,
            int limit,
            Duration leaseDuration
    );
    boolean markPublished(UUID eventId, UUID leaseToken);
}