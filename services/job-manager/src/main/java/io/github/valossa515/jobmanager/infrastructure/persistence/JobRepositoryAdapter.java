package io.github.valossa515.jobmanager.infrastructure.persistence;

import io.github.valossa515.jobmanager.application.port.IJobRepository;
import io.github.valossa515.jobmanager.domain.model.Job;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public class JobRepositoryAdapter implements IJobRepository {
    private final SpringDataJobRepository repository;

    public JobRepositoryAdapter(SpringDataJobRepository repository) {
        this.repository = repository;
    }


    @Override
    public Job save(Job job) {
        JobJpaEntity entity = JobMapper.toEntity(job);
        JobJpaEntity savedEntity = repository.save(entity);
        return JobMapper.toDomain(savedEntity);
    }

    @Override
    public Optional<Job> findById(UUID id) {
        return repository.findById(id)
                .map(JobMapper::toDomain);
    }
}