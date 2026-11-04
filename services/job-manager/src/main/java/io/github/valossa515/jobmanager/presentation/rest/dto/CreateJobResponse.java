package io.github.valossa515.jobmanager.presentation.rest.dto;

import io.github.valossa515.jobmanager.domain.model.Job;
import io.github.valossa515.jobmanager.domain.model.JobStatus;

import java.time.Instant;
import java.util.UUID;

public record CreateJobResponse(
        UUID id, String type, JobStatus status, Instant createdAt) {

    public static CreateJobResponse from(Job job) {
        return new CreateJobResponse(
                job.getId(),
                job.getType(),
                job.getStatus(),
                job.getCreatedAt()
        );
    }
}
