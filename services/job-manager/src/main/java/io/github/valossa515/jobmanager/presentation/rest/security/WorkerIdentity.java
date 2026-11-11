
package io.github.valossa515.jobmanager.presentation.rest.security;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.UUID;

public record WorkerIdentity(
        String workerId,
        String instanceId
) {
    public static WorkerIdentity from(
            Authentication authentication
    ) {
        if (!(authentication instanceof JwtAuthenticationToken token)
                || !authentication.isAuthenticated()) {
            throw new AccessDeniedException(
                    "Worker JWT authentication required"
            );
        }

        Jwt jwt = token.getToken();

        String workerId = jwt.getSubject();
        String instanceId =
                jwt.getClaimAsString("worker_instance_id");

        if (!"worker-java".equals(workerId)
                && !"worker-dotnet".equals(workerId)) {
            throw new AccessDeniedException(
                    "Invalid worker identity"
            );
        }

        try {
            if (instanceId == null
                    || !UUID.fromString(instanceId)
                    .toString().equals(instanceId)) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException exception) {
            throw new AccessDeniedException(
                    "Invalid worker instance identity"
            );
        }

        return new WorkerIdentity(
                workerId,
                instanceId
        );
    }
}