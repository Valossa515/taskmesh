
package io.github.valossa515.jobmanager.presentation.rest;

import io.github.valossa515.jobmanager.application.job.execution.WorkerInstanceRegistrationService;
import io.github.valossa515.jobmanager.presentation.rest.dto.WorkerInstanceRegistrationResponse;
import io.github.valossa515.jobmanager.presentation.rest.security.WorkerIdentity;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/worker-instances")
@ConditionalOnProperty(
        prefix = "taskmesh.security.worker-instance-registration",
        name = "enabled",
        havingValue = "true"
)
public class WorkerInstanceRegistrationController {

    private final WorkerInstanceRegistrationService service;

    public WorkerInstanceRegistrationController(
            WorkerInstanceRegistrationService service
    ) {
        this.service = service;
    }

    @PostMapping("/register")
    public ResponseEntity<WorkerInstanceRegistrationResponse> register(
            Authentication authentication
    ) {
        if (authentication == null
                || !authentication.isAuthenticated()) {
            throw new AccessDeniedException(
                    "Worker authentication required"
            );
        }

        String workerId = switch (authentication.getName()) {
            case "worker-java-local" -> "worker-java";
            case "worker-dotnet-local" -> "worker-dotnet";
            default -> throw new AccessDeniedException(
                    "Unknown worker identity"
            );
        };

        return response(service.register(workerId));
    }

    @PostMapping("/refresh")
    public ResponseEntity<WorkerInstanceRegistrationResponse> refresh(
            Authentication authentication
    ) {
        WorkerIdentity identity =
                WorkerIdentity.from(authentication);

        // WorkerIdentity.from() already establishes that
        // the principal is an authenticated JWT.
        JwtAuthenticationToken token =
                (JwtAuthenticationToken) authentication;

        String signedDeadline = token.getToken()
                .getClaimAsString(
                        WorkerInstanceRegistrationService
                                .SESSION_EXPIRES_AT_CLAIM
                );

        Instant sessionExpiresAt;

        try {
            sessionExpiresAt = Instant.parse(
                    signedDeadline
            );
        } catch (DateTimeParseException | NullPointerException exception) {
            throw new AccessDeniedException(
                    "Worker session deadline is missing or invalid"
            );
        }

        var renewal = service.refresh(
                identity.workerId(),
                UUID.fromString(identity.instanceId()),
                sessionExpiresAt
        );

        return response(renewal);
    }

    private ResponseEntity<WorkerInstanceRegistrationResponse> response(
            WorkerInstanceRegistrationService.Registration registration
    ) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new WorkerInstanceRegistrationResponse(
                        registration.workerId(),
                        registration.workerInstanceId(),
                        registration.accessToken(),
                        "Bearer",
                        registration.expiresAt()
                ));
    }
}