
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

    private static final Duration TOKEN_LIFETIME =
            Duration.ofHours(1);

    private static final Set<String> ALLOWED_WORKERS =
            Set.of("worker-java", "worker-dotnet");

    private final JwtEncoder encoder;

    public WorkerInstanceRegistrationService(
            JwtEncoder encoder
    ) {
        this.encoder = encoder;
    }

    public Registration register(String workerId) {
        if (!ALLOWED_WORKERS.contains(workerId)) {
            throw new IllegalArgumentException(
                    "Unsupported worker identity"
            );
        }

        UUID instanceId = UUID.randomUUID();

        Instant issuedAt = Instant.now();
        Instant expiresAt = issuedAt.plus(TOKEN_LIFETIME);

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
                .build();

        JwsHeader header = JwsHeader
                .with(MacAlgorithm.HS256)
                .build();

        String token = encoder.encode(
                JwtEncoderParameters.from(
                        header,
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
}