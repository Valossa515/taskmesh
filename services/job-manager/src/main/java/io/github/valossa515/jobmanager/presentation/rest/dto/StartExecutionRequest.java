package io.github.valossa515.jobmanager.presentation.rest.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record StartExecutionRequest(
        @NotNull UUID eventId,
        @NotNull UUID claimId)
{
}
