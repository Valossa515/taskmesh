
package io.github.valossa515.jobmanager.application.job.execution;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
@ConditionalOnProperty(
        prefix = "taskmesh.security.worker-instance-registration",
        name = "enabled",
        havingValue = "true"
)
public class WorkerInstanceRegistrationService {

    public static final String ISSUER = "taskmesh-job-manager";
    public static final String AUDIENCE = "taskmesh-execution-api";
    public static final String SESSION_EXPIRES_AT_CLAIM =
            "session_expires_at";

    private static final Duration TOKEN_LIFETIME =
            Duration.ofHours(1);

    private static final Duration SESSION_LIFETIME =
            Duration.ofHours(8);

    private static final Duration MIN_RENEWAL_VALIDITY =
            Duration.ofSeconds(90);

    private static final Set<String> ALLOWED_WORKERS =
            Set.of("worker-java", "worker-dotnet");

    private final JwtEncoder encoder;

    public WorkerInstanceRegistrationService(
            JwtEncoder encoder
    ) {
        this.encoder = encoder;
    }

    public Registration register(String workerId) {
        Instant now = Instant.now();

        return issue(
                workerId,
                UUID.randomUUID(),
                now,
                now.plus(SESSION_LIFETIME)
        );
    }

    public Registration refresh(
            String workerId,
            UUID instanceId,
            Instant sessionExpiresAt
    ) {
        Objects.requireNonNull(instanceId);
        Objects.requireNonNull(sessionExpiresAt);

        Instant now = Instant.now();

        if (!sessionExpiresAt.isAfter(
                now.plus(MIN_RENEWAL_VALIDITY)
        )) {
            throw new WorkerSessionExpiredException();
        }

        return issue(
                workerId,
                instanceId,
                now,
                sessionExpiresAt
        );
    }

    private Registration issue(
            String workerId,
            UUID instanceId,
            Instant issuedAt,
            Instant sessionExpiresAt
    ) {
        if (!ALLOWED_WORKERS.contains(workerId)) {
            throw new IllegalArgumentException(
                    "Unsupported worker identity"
            );
        }

        Instant expiresAt =
                issuedAt.plus(TOKEN_LIFETIME);

        if (expiresAt.isAfter(sessionExpiresAt)) {
            expiresAt = sessionExpiresAt;
        }

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(ISSUER)
                .subject(workerId)
                .audience(List.of(AUDIENCE))
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .id(UUID.randomUUID().toString())
                .claim(
                        "worker_instance_id",
                        instanceId.toString()
                )
                .claim("scope", "worker.execute")
                .claim(
                        SESSION_EXPIRES_AT_CLAIM,
                        sessionExpiresAt.toString()
                )
                .build();

        String token = encoder.encode(
                JwtEncoderParameters.from(
                        JwsHeader
                                .with(MacAlgorithm.HS256)
                                .build(),
                        claims
                )
        ).getTokenValue();

        return new Registration(
                workerId,
                instanceId,
                token,
                expiresAt
        );
    }

    public record Registration(
            String workerId,
            UUID workerInstanceId,
            String accessToken,
            Instant expiresAt
    ) {
    }

    public static class WorkerSessionExpiredException
            extends IllegalStateException {

        public WorkerSessionExpiredException() {
            super("Worker session lifetime is exhausted");
        }
    }
}