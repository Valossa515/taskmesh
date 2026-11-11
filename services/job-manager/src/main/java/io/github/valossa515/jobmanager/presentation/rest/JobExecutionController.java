
package io.github.valossa515.jobmanager.presentation.rest;

import io.github.valossa515.jobmanager.application.job.execution.ExecutionClaimService;
import io.github.valossa515.jobmanager.application.job.execution.ExecutionOutcomeService;
import io.github.valossa515.jobmanager.presentation.rest.dto.CompleteExecutionRequest;
import io.github.valossa515.jobmanager.presentation.rest.dto.ExecutionOutcomeResponse;
import io.github.valossa515.jobmanager.presentation.rest.dto.FailExecutionRequest;
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
    private final ExecutionOutcomeService outcomeService;

    public JobExecutionController(
            ExecutionClaimService claimService,
            ExecutionOutcomeService outcomeService
    ) {
        this.claimService = claimService;
        this.outcomeService = outcomeService;
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

    @PostMapping("/{executionId}/complete")
    public ExecutionOutcomeResponse complete(
            @PathVariable UUID jobId,
            @PathVariable UUID executionId,
            @Valid @RequestBody CompleteExecutionRequest request,
            Authentication authentication
    ) {
        WorkerIdentity worker =
                WorkerIdentity.from(authentication);

        var result = outcomeService.complete(
                jobId,
                request.eventId(),
                executionId,
                worker.workerId(),
                worker.instanceId()
        );

        return toResponse(result);
    }

    @PostMapping("/{executionId}/fail")
    public ExecutionOutcomeResponse fail(
            @PathVariable UUID jobId,
            @PathVariable UUID executionId,
            @Valid @RequestBody FailExecutionRequest request,
            Authentication authentication
    ) {
        WorkerIdentity worker =
                WorkerIdentity.from(authentication);

        var result = outcomeService.fail(
                jobId,
                request.eventId(),
                executionId,
                worker.workerId(),
                worker.instanceId(),
                request.reason()
        );

        return toResponse(result);
    }

    private ExecutionOutcomeResponse toResponse(
            ExecutionOutcomeService.OutcomeResult result
    ) {
        // This decision is evaluated after the
        // transactional service method returns.
        if (result.decision()
                == ExecutionOutcomeService.Decision.STALE) {
            throw new ExecutionOutcomeService
                    .ExecutionStaleException();
        }

        return new ExecutionOutcomeResponse(
                result.jobId(),
                result.status(),
                result.failureReason()
        );
    }
}