package io.github.valossa515.jobmanager.application.port;

import io.github.valossa515.jobmanager.domain.model.Job;

import java.util.Optional;
import java.util.UUID;

public interface IJobRepository {
    Job save(Job job);
    Optional<Job> findById(UUID id);
    Optional<Job> findByIdForUpdate(UUID id);
}
