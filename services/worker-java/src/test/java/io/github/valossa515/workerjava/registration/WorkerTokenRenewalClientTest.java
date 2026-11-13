
package io.github.valossa515.workerjava.registration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class WorkerTokenRenewalClientTest {

    private static final Instant NOW =
            Instant.parse("2030-01-01T00:00:00Z");

    private static final UUID INSTANCE_ID =
            UUID.fromString(
                    "11111111-2222-4333-8444-555555555555"
            );

    private HttpClient http;
    private HttpResponse<String> response;
    private WorkerRegistrationClient client;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        http = mock(HttpClient.class);
        response = mock(HttpResponse.class);

        client = new WorkerRegistrationClient(
                http,
                JsonMapper.builder().build(),
                URI.create("http://127.0.0.1:8080"),
                "worker-java-local",
                "test-password",
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    private WorkerSession current() {
        return new WorkerSession(
                "worker-java",
                INSTANCE_ID,
                "old-token",
                NOW.plusSeconds(240)
        );
    }

    private String validResponse() {
        return """
                {
                  "workerId": "worker-java",
                  "workerInstanceId": "%s",
                  "accessToken": "new-token",
                  "tokenType": "Bearer",
                  "expiresAt": "2030-01-01T01:00:00Z"
                }
                """.formatted(INSTANCE_ID);
    }

    private void respond(int status, String body)
            throws Exception {
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body);

        when(http.send(
                any(HttpRequest.class),
                org.mockito.ArgumentMatchers
                        .<HttpResponse.BodyHandler<String>>any()
        )).thenReturn(response);
    }

    @Test
    void shouldRefreshWithBearerAndKeepIdentity()
            throws Exception {
        respond(200, validResponse());

        WorkerSession renewed = client.refresh(current());

        assertEquals(INSTANCE_ID, renewed.workerInstanceId());
        assertEquals("worker-java", renewed.workerId());
        assertEquals("new-token", renewed.accessToken());

        var captor = org.mockito.ArgumentCaptor.forClass(
                HttpRequest.class
        );

        verify(http).send(captor.capture(), any());

        HttpRequest request = captor.getValue();

        assertEquals("POST", request.method());

        assertEquals(
                "/api/v1/worker-instances/refresh",
                request.uri().getPath()
        );

        assertEquals(
                "Bearer old-token",
                request.headers()
                        .firstValue("Authorization")
                        .orElseThrow()
        );
    }

    @Test
    void shouldRejectDifferentInstanceIdentity()
            throws Exception {
        respond(
                200,
                validResponse().replace(
                        INSTANCE_ID.toString(),
                        UUID.randomUUID().toString()
                )
        );

        var exception = assertThrows(
                WorkerTokenRenewalException.class,
                () -> client.refresh(current())
        );

        assertEquals(
                WorkerTokenRenewalException.Kind.PROTOCOL,
                exception.kind()
        );
    }

    @Test
    void shouldRejectUnchangedAccessToken()
            throws Exception {
        respond(
                200,
                validResponse().replace(
                        "new-token",
                        "old-token"
                )
        );

        var exception = assertThrows(
                WorkerTokenRenewalException.class,
                () -> client.refresh(current())
        );

        assertEquals(
                WorkerTokenRenewalException.Kind.PROTOCOL,
                exception.kind()
        );
    }

    @Test
    void shouldRecognizeSessionLifetimeExhaustion()
            throws Exception {
        respond(
                409,
                """
                {
                  "type": "urn:taskmesh:problem:worker-session-expired",
                  "status": 409
                }
                """
        );

        var exception = assertThrows(
                WorkerTokenRenewalException.class,
                () -> client.refresh(current())
        );

        assertEquals(
                WorkerTokenRenewalException.Kind.SESSION_EXPIRED,
                exception.kind()
        );
    }

    @Test
    void shouldRejectUnauthorizedRenewal()
            throws Exception {
        respond(401, "");

        var exception = assertThrows(
                WorkerTokenRenewalException.class,
                () -> client.refresh(current())
        );

        assertEquals(
                WorkerTokenRenewalException.Kind.REJECTED,
                exception.kind()
        );
    }

    @Test
    void shouldTreatServerFailureAsTransient()
            throws Exception {
        respond(503, "");

        var exception = assertThrows(
                WorkerTokenRenewalException.class,
                () -> client.refresh(current())
        );

        assertEquals(
                WorkerTokenRenewalException.Kind.TRANSIENT,
                exception.kind()
        );
    }

    @Test
    void shouldRejectMalformedSuccessResponse()
            throws Exception {
        respond(200, "invalid-json");

        var exception = assertThrows(
                WorkerTokenRenewalException.class,
                () -> client.refresh(current())
        );

        assertEquals(
                WorkerTokenRenewalException.Kind.PROTOCOL,
                exception.kind()
        );
    }

    @Test
    void shouldTreatLostConnectionAsTransient()
            throws Exception {
        when(http.send(
                any(HttpRequest.class),
                org.mockito.ArgumentMatchers
                        .<HttpResponse.BodyHandler<String>>any()
        )).thenThrow(
                new IOException("Connection closed")
        );

        var exception = assertThrows(
                WorkerTokenRenewalException.class,
                () -> client.refresh(current())
        );

        assertEquals(
                WorkerTokenRenewalException.Kind.TRANSIENT,
                exception.kind()
        );
    }
}