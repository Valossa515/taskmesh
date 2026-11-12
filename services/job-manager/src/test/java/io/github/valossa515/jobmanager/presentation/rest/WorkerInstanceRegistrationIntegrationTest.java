
package io.github.valossa515.jobmanager.presentation.rest;

import io.github.valossa515.jobmanager.application.job.execution.WorkerInstanceRegistrationService;
import io.github.valossa515.jobmanager.support.IntegrationTestConfig;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.web.servlet.MockMvc;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import javax.crypto.spec.SecretKeySpec;
import java.util.Base64;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.springframework.http.HttpHeaders;
import java.time.Instant;

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
class WorkerInstanceRegistrationIntegrationTest {

    private static final String REGISTER_URL =
            "/api/v1/worker-instances/register";

    private static final String TEST_KEY =
            "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";

    private static final String REFRESH_URL =
            "/api/v1/worker-instances/refresh";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JsonMapper jsonMapper;

    @Autowired
    private WorkerInstanceRegistrationService registrations;

    private JsonNode register(
            String username,
            String password
    ) throws Exception {
        String response = mockMvc.perform(
                        post(REGISTER_URL)
                                .with(httpBasic(username, password))
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").exists())
                .andExpect(jsonPath("$.tokenType")
                        .value("Bearer"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        return jsonMapper.readTree(response);
    }

    private JwtDecoder decoder() {
        byte[] key = Base64.getDecoder().decode(TEST_KEY);

        return NimbusJwtDecoder.withSecretKey(
                        new SecretKeySpec(key, "HmacSHA256")
                )
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
    }

    @Test
    void shouldRegisterJavaWorkerWithSignedToken()
            throws Exception {

        JsonNode response = register(
                "worker-java-local",
                "java-test-password"
        );

        String instanceId =
                response.get("workerInstanceId").asString();

        UUID.fromString(instanceId);

        Jwt jwt = decoder().decode(
                response.get("accessToken").asString()
        );

        assertAll(
                () -> assertEquals(
                        "worker-java",
                        response.get("workerId").asString()
                ),
                () -> assertEquals(
                        "worker-java",
                        jwt.getSubject()
                ),
                () -> assertEquals(
                        instanceId,
                        jwt.getClaimAsString("worker_instance_id")
                ),
                () -> assertEquals(
                        "worker.execute",
                        jwt.getClaimAsString("scope")
                ),
                () -> assertTrue(
                        jwt.getAudience().contains(
                                WorkerInstanceRegistrationService.AUDIENCE
                        )
                ),
                () -> assertNotNull(jwt.getExpiresAt())
        );
    }

    @Test
    void shouldAssignDifferentIdsToDifferentInstances()
            throws Exception {

        JsonNode first = register(
                "worker-java-local",
                "java-test-password"
        );

        JsonNode second = register(
                "worker-java-local",
                "java-test-password"
        );

        assertNotEquals(
                first.get("workerInstanceId").asString(),
                second.get("workerInstanceId").asString()
        );

        assertNotEquals(
                first.get("accessToken").asString(),
                second.get("accessToken").asString()
        );

        assertEquals(
                first.get("workerId").asString(),
                second.get("workerId").asString()
        );
    }

    @Test
    void shouldRegisterDotnetWorker() throws Exception {
        JsonNode response = register(
                "worker-dotnet-local",
                "dotnet-test-password"
        );

        Jwt jwt = decoder().decode(
                response.get("accessToken").asString()
        );

        assertEquals("worker-dotnet", jwt.getSubject());

        assertEquals(
                response.get("workerInstanceId").asString(),
                jwt.getClaimAsString("worker_instance_id")
        );
    }

    @Test
    void shouldRejectUnauthenticatedRegistration()
            throws Exception {

        mockMvc.perform(post(REGISTER_URL))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldRejectInvalidBootstrapCredentials()
            throws Exception {

        mockMvc.perform(
                        post(REGISTER_URL)
                                .with(httpBasic(
                                        "worker-java-local",
                                        "wrong-password"
                                ))
                )
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldRejectTamperedToken() throws Exception {
        JsonNode response = register(
                "worker-java-local",
                "java-test-password"
        );

        String token =
                response.get("accessToken").asString();

        int signatureStart = token.lastIndexOf('.') + 1;

        char firstCharacter = token.charAt(signatureStart);
        char replacement =
                firstCharacter == 'A' ? 'B' : 'A';

        String tamperedToken =
                token.substring(0, signatureStart)
                        + replacement
                        + token.substring(signatureStart + 1);

        assertThrows(
                JwtException.class,
                () -> decoder().decode(tamperedToken)
        );
    }


    @Test
    void shouldRenewJwtKeepingTheSameWorkerIdentity()
            throws Exception {

        JsonNode original = register(
                "worker-java-local",
                "java-test-password"
        );

        String oldToken =
                original.get("accessToken").asString();

        String responseBody = mockMvc.perform(
                        post(REFRESH_URL)
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        "Bearer " + oldToken
                                )
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workerId")
                        .value("worker-java"))
                .andExpect(jsonPath("$.tokenType")
                        .value("Bearer"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode refreshed = jsonMapper.readTree(responseBody);

        assertEquals(
                original.get("workerInstanceId").asString(),
                refreshed.get("workerInstanceId").asString()
        );

        String newToken =
                refreshed.get("accessToken").asString();

        assertNotEquals(oldToken, newToken);

        Jwt oldJwt = decoder().decode(oldToken);
        Jwt newJwt = decoder().decode(newToken);

        assertEquals(
                oldJwt.getSubject(),
                newJwt.getSubject()
        );

        assertEquals(
                oldJwt.getClaimAsString("worker_instance_id"),
                newJwt.getClaimAsString("worker_instance_id")
        );

        assertEquals(
                oldJwt.getClaimAsString("session_expires_at"),
                newJwt.getClaimAsString("session_expires_at")
        );

        assertNotEquals(oldJwt.getId(), newJwt.getId());
    }

    @Test
    void shouldRejectRefreshWithoutBearerToken()
            throws Exception {

        mockMvc.perform(post(REFRESH_URL))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldRejectBasicCredentialsOnRefresh()
            throws Exception {

        mockMvc.perform(
                        post(REFRESH_URL)
                                .with(httpBasic(
                                        "worker-java-local",
                                        "java-test-password"
                                ))
                )
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldRejectTamperedRefreshToken()
            throws Exception {

        JsonNode registration = register(
                "worker-java-local",
                "java-test-password"
        );

        String token =
                registration.get("accessToken").asString();

        String[] parts = token.split("\\.");

        String signature = parts[2];

        char replacement =
                signature.charAt(0) == 'A' ? 'B' : 'A';

        String tampered = parts[0]
                + "." + parts[1]
                + "." + replacement
                + signature.substring(1);

        mockMvc.perform(
                        post(REFRESH_URL)
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        "Bearer " + tampered
                                )
                )
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldRejectRenewalNearSessionDeadline() {

        assertThrows(
                WorkerInstanceRegistrationService
                        .WorkerSessionExpiredException.class,
                () -> registrations.refresh(
                        "worker-java",
                        UUID.randomUUID(),
                        Instant.now().plusSeconds(30)
                )
        );
    }
}