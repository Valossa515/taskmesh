package io.github.valossa515.jobmanager.presentation.rest.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.util.UUID;

public record FailExecutionRequest(
        @NotNull UUID eventId,

        @NotBlank
        @Pattern(
                regexp = "^(?!EXECUTION_LEASE_EXPIRED$).*$",
                message = "Lease expiration is manager-owned"
        )
        String reason) {
}
