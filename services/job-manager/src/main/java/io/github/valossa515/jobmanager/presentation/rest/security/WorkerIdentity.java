
package io.github.valossa515.jobmanager.presentation.rest.security;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;

public record WorkerIdentity(
        String workerId,
        String instanceId
) {
    public static WorkerIdentity from(
            Authentication authentication
    ) {
        if (authentication == null
                || !authentication.isAuthenticated()) {
            throw new AccessDeniedException(
                    "Worker authentication required"
            );
        }

        return switch (authentication.getName()) {
            case "worker-java-local" ->
                    new WorkerIdentity(
                            "worker-java",
                            "java-local"
                    );

            case "worker-dotnet-local" ->
                    new WorkerIdentity(
                            "worker-dotnet",
                            "dotnet-local"
                    );

            default -> throw new AccessDeniedException(
                    "Unknown worker identity"
            );
        };
    }
}