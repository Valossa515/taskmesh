package io.github.valossa515.jobmanager.presentation.rest.dto;

import java.time.Instant;
import java.util.UUID;

public record WorkerInstanceRegistrationResponse(
        String workerId,
        UUID workerInstanceId,
        String accessToken,
        String tokenType,
        Instant expiresAt
) {
}
