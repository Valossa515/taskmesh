package io.github.valossa515.jobmanager.application.port;

import io.github.valossa515.jobmanager.domain.model.JobExecution;

import java.util.Optional;
import java.util.UUID;

public interface IJobExecutionRepository {
    JobExecution save(JobExecution execution);
    Optional<JobExecution> findByJobId(UUID jobId);
}
