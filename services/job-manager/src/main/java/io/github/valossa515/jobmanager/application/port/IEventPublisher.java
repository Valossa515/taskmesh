package io.github.valossa515.jobmanager.application.port;

import io.github.valossa515.jobmanager.domain.model.OutboxEvent;

public interface IEventPublisher {
    void publish(OutboxEvent event);
}
