package io.github.valossa515.jobmanager.presentation.rest.dto;

import io.github.valossa515.jobmanager.domain.model.JobStatus;

import java.time.Instant;
import java.util.UUID;

public record StartExecutionResponse(
        UUID jobId,
        JobStatus status,
        UUID executionId,
        Instant expiresAt,
        String failureReason
) {
}
