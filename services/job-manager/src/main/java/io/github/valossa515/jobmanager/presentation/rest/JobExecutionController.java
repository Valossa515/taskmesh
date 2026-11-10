
package io.github.valossa515.jobmanager.presentation.rest;

import io.github.valossa515.jobmanager.application.job.execution.ExecutionClaimService;
import io.github.valossa515.jobmanager.presentation.rest.dto.StartExecutionRequest;
import io.github.valossa515.jobmanager.presentation.rest.dto.StartExecutionResponse;
import io.github.valossa515.jobmanager.presentation.rest.security.WorkerIdentity;

import jakarta.validation.Valid;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/jobs/{jobId}/executions")
public class JobExecutionController {

    private final ExecutionClaimService claimService;

    public JobExecutionController(
            ExecutionClaimService claimService
    ) {
        this.claimService = claimService;
    }

    @PostMapping("/start")
    public StartExecutionResponse start(
            @PathVariable UUID jobId,
            @Valid @RequestBody StartExecutionRequest request,
            Authentication authentication
    ) {
        WorkerIdentity worker =
                WorkerIdentity.from(authentication);

        var result = claimService.start(
                jobId,
                request.eventId(),
                request.claimId(),
                worker.workerId(),
                worker.instanceId()
        );

        return new StartExecutionResponse(
                jobId,
                result.status(),
                result.executionId(),
                result.expiresAt(),
                result.failureReason()
        );
    }
}