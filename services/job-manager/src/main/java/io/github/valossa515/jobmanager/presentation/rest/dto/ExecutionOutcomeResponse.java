package io.github.valossa515.jobmanager.presentation.rest.dto;

import io.github.valossa515.jobmanager.domain.model.JobStatus;

import java.util.UUID;

public record ExecutionOutcomeResponse(
        UUID jobId,
        JobStatus status,
        String failureReason
) {
}
