package io.github.valossa515.jobmanager.infrastructure.persistence;


import io.github.valossa515.jobmanager.domain.model.OutboxEvent;

public final class OutboxMapper {

    private OutboxMapper() {
    }

    public static OutboxJpaEntity toEntity(OutboxEvent event) {
        OutboxJpaEntity entity = new OutboxJpaEntity();

        entity.setId(event.getId());
        entity.setAggregateId(event.getAggregateId());
        entity.setEventType(event.getEventType());
        entity.setRoutingKey(event.getRoutingKey());
        entity.setPayload(event.getPayload());
        entity.setStatus(event.getStatus());
        entity.setAttempts(event.getAttempts());
        entity.setCreatedAt(event.getCreatedAt());
        entity.setPublishedAt(event.getPublishedAt());
        entity.setLockedBy(event.getLockedBy());
        entity.setLockedUntil(event.getLockedUntil());
        entity.setLeaseToken(event.getLeaseToken());
        return entity;
    }

    public static OutboxEvent toDomain(OutboxJpaEntity entity) {
        return OutboxEvent.restore(
                entity.getId(),
                entity.getAggregateId(),
                entity.getEventType(),
                entity.getRoutingKey(),
                entity.getPayload(),
                entity.getStatus(),
                entity.getAttempts(),
                entity.getCreatedAt(),
                entity.getPublishedAt(),
                entity.getLockedBy(),
                entity.getLockedUntil(),
                entity.getLeaseToken()
        );
    }
}
