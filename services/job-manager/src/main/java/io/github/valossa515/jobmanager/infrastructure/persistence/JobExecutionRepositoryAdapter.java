package io.github.valossa515.jobmanager.infrastructure.persistence;

import io.github.valossa515.jobmanager.application.port.IJobExecutionRepository;
import io.github.valossa515.jobmanager.domain.model.JobExecution;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public class JobExecutionRepositoryAdapter implements IJobExecutionRepository {

    private final SpringDataJobExecutionRepository repository;

    public JobExecutionRepositoryAdapter(SpringDataJobExecutionRepository repository) {
        this.repository = repository;
    }



    @Override
    public JobExecution save(JobExecution execution) {
        JobExecutionJpaEntity entity =
                JobExecutionMapper.toEntity(execution);

        JobExecutionJpaEntity saved = repository.save(entity);

        return JobExecutionMapper.toDomain(saved);
    }

    @Override
    public Optional<JobExecution> findByJobId(UUID jobId) {
        return repository.findById(jobId)
                .map(JobExecutionMapper::toDomain);
    }
}
