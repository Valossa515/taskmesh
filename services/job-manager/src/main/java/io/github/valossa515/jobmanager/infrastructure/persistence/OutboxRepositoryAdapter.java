package io.github.valossa515.jobmanager.infrastructure.persistence;

import io.github.valossa515.jobmanager.application.port.IOutboxRepository;
import io.github.valossa515.jobmanager.domain.model.OutboxEvent;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public class OutboxRepositoryAdapter implements IOutboxRepository {

    private final SpringDataOutboxRepository repository;

    public OutboxRepositoryAdapter(SpringDataOutboxRepository repository) {
        this.repository = repository;
    }

    @Override
    public OutboxEvent save(OutboxEvent event) {
        OutboxJpaEntity entity = OutboxMapper.toEntity(event);

        OutboxJpaEntity saved = repository.save(entity);

        return OutboxMapper.toDomain(saved);
    }

    @Override
    public Optional<OutboxEvent> findById(UUID id) {
        return repository.findById(id)
                .map(OutboxMapper::toDomain);
    }
}
