package io.github.valossa515.jobmanager.presentation.rest.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.Map;

public record CreateJobRequest(
        @NotBlank String type,
        @NotNull Map<String, Object> payload) {
}
