
package io.github.valossa515.jobmanager.presentation.rest;

import io.github.valossa515.jobmanager.application.job.execution.WorkerInstanceRegistrationService;
import io.github.valossa515.jobmanager.presentation.rest.dto.WorkerInstanceRegistrationResponse;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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

        var registration = service.register(workerId);

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