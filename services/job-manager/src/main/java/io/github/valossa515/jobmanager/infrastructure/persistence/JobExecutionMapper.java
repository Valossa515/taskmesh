package io.github.valossa515.jobmanager.infrastructure.persistence;

import io.github.valossa515.jobmanager.domain.model.JobExecution;

public class JobExecutionMapper {

    private JobExecutionMapper() {
    }

    public static JobExecutionJpaEntity toEntity(
            JobExecution execution
    ) {
        JobExecutionJpaEntity entity = new JobExecutionJpaEntity();

        entity.setJobId(execution.getJobId());
        entity.setExecutionId(execution.getExecutionId());
        entity.setEventId(execution.getEventId());
        entity.setClaimId(execution.getClaimId());

        entity.setWorkerId(execution.getWorkerId());
        entity.setWorkerInstanceId(
                execution.getWorkerInstanceId()
        );

        entity.setStatus(execution.getStatus());
        entity.setGrantedAt(execution.getGrantedAt());
        entity.setExpiresAt(execution.getExpiresAt());

        entity.setFinishedAt(execution.getFinishedAt());
        entity.setFailureReason(execution.getFailureReason());

        return entity;
    }

    public static JobExecution toDomain(
            JobExecutionJpaEntity entity
    ) {
        return JobExecution.restore(
                entity.getJobId(),
                entity.getExecutionId(),
                entity.getEventId(),
                entity.getClaimId(),
                entity.getWorkerId(),
                entity.getWorkerInstanceId(),
                entity.getStatus(),
                entity.getGrantedAt(),
                entity.getExpiresAt(),
                entity.getFinishedAt(),
                entity.getFailureReason()
        );
    }
}