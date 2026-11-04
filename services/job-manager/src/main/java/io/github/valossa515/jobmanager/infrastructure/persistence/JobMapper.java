package io.github.valossa515.jobmanager.infrastructure.persistence;

import io.github.valossa515.jobmanager.domain.model.Job;

public final class JobMapper {
    private JobMapper() {
    }

    public static JobJpaEntity toEntity(Job job) {
        JobJpaEntity entity = new JobJpaEntity();
        entity.setId(job.getId());
        entity.setType(job.getType());
        entity.setPayload(job.getPayload());
        entity.setStatus(job.getStatus());
        entity.setCreatedAt(job.getCreatedAt());
        entity.setStartedAt(job.getStartedAt());
        entity.setCompletedAt(job.getCompletedAt());
        entity.setFailureReason(job.getFailureReason());

        return entity;
    }

    public static Job toDomain(JobJpaEntity entity) {
        return Job.restore(
                entity.getId(),
                entity.getType(),
                entity.getPayload(),
                entity.getStatus(),
                entity.getCreatedAt(),
                entity.getStartedAt(),
                entity.getCompletedAt(),
                entity.getFailureReason()
        );
    }
}
