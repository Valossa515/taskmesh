
package io.github.valossa515.jobmanager.presentation.rest;

import io.github.valossa515.jobmanager.application.job.execution.WorkerInstanceRegistrationService;
import io.github.valossa515.jobmanager.support.IntegrationTestConfig;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "taskmesh.security.worker-instance-registration.enabled=true",
        "taskmesh.security.worker-instance-registration.signing-key-base64=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "taskmesh.security.java-password=java-test-password",
        "taskmesh.security.dotnet-password=dotnet-test-password",
        "taskmesh.publisher.scheduling.enabled=false",
        "taskmesh.execution.expiration.scheduling.enabled=false"
})
@AutoConfigureMockMvc
@Import(IntegrationTestConfig.class)
class ExecutionJwtSecurityIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtEncoder encoder;

    @Autowired
    private WorkerInstanceRegistrationService registrations;

    private ResultActions requestWithToken(String token)
            throws Exception {
        return mockMvc.perform(
                post(
                        "/api/v1/jobs/"
                                + UUID.randomUUID()
                                + "/executions/start"
                )
                        .header(
                                HttpHeaders.AUTHORIZATION,
                                "Bearer " + token
                        )
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "eventId": "%s",
                                  "claimId": "%s"
                                }
                                """.formatted(
                                UUID.randomUUID(),
                                UUID.randomUUID()
                        ))
        );
    }

    private String signedToken(
            String issuer,
            List<String> audience,
            String workerId,
            String instanceId,
            String scope,
            Instant issuedAt,
            Instant expiresAt
    ) {
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .subject(workerId)
                .audience(audience)
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .id(UUID.randomUUID().toString());

        if (instanceId != null) {
            claims.claim(
                    "worker_instance_id",
                    instanceId
            );
        }

        if (scope != null) {
            claims.claim("scope", scope);
        }

        return encoder.encode(
                JwtEncoderParameters.from(
                        JwsHeader.with(MacAlgorithm.HS256).build(),
                        claims.build()
                )
        ).getTokenValue();
    }

    private String signedToken(
            String issuer,
            String audience,
            String instanceId,
            String scope
    ) {
        Instant now = Instant.now();

        return signedToken(
                issuer,
                List.of(audience),
                "worker-java",
                instanceId,
                scope,
                now,
                now.plusSeconds(3600)
        );
    }

    @Test
    void shouldRejectTamperedSignature() throws Exception {
        String token = registrations
                .register("worker-java")
                .accessToken();

        String[] parts = token.split("\\.");

        String signature = parts[2];

        char replacement =
                signature.charAt(0) == 'A' ? 'B' : 'A';

        String tampered =
                parts[0] + "."
                        + parts[1] + "."
                        + replacement
                        + signature.substring(1);

        requestWithToken(tampered)
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldRejectExpiredToken() throws Exception {
        Instant now = Instant.now();

        String token = signedToken(
                WorkerInstanceRegistrationService.ISSUER,
                List.of(
                        WorkerInstanceRegistrationService.AUDIENCE
                ),
                "worker-java",
                UUID.randomUUID().toString(),
                "worker.execute",
                now.minusSeconds(3600),
                now.minusSeconds(180)
        );

        requestWithToken(token)
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldRejectInvalidIssuer() throws Exception {
        String token = signedToken(
                "untrusted-issuer",
                WorkerInstanceRegistrationService.AUDIENCE,
                UUID.randomUUID().toString(),
                "worker.execute"
        );

        requestWithToken(token)
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldRejectInvalidAudience() throws Exception {
        String token = signedToken(
                WorkerInstanceRegistrationService.ISSUER,
                "some-other-api",
                UUID.randomUUID().toString(),
                "worker.execute"
        );

        requestWithToken(token)
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldRejectTokenWithoutExecutionScope()
            throws Exception {
        String token = signedToken(
                WorkerInstanceRegistrationService.ISSUER,
                WorkerInstanceRegistrationService.AUDIENCE,
                UUID.randomUUID().toString(),
                "worker.read"
        );

        requestWithToken(token)
                .andExpect(status().isForbidden());
    }

    @Test
    void shouldRejectMissingWorkerInstanceId()
            throws Exception {
        String token = signedToken(
                WorkerInstanceRegistrationService.ISSUER,
                WorkerInstanceRegistrationService.AUDIENCE,
                null,
                "worker.execute"
        );

        requestWithToken(token)
                .andExpect(status().isUnauthorized());
    }


    @Test
    void shouldRejectRefreshWithoutExecutionScope()
            throws Exception {

        String token = signedToken(
                WorkerInstanceRegistrationService.ISSUER,
                WorkerInstanceRegistrationService.AUDIENCE,
                UUID.randomUUID().toString(),
                "worker.read"
        );

        mockMvc.perform(
                        post("/api/v1/worker-instances/refresh")
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        "Bearer " + token
                                )
                )
                .andExpect(status().isForbidden());
    }
}