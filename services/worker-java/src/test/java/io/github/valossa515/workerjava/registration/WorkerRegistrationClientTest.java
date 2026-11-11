
package io.github.valossa515.workerjava.registration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

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

class WorkerRegistrationClientTest {

    private static final Instant NOW =
            Instant.parse("2030-01-01T00:00:00Z");

    private static final UUID INSTANCE_ID =
            UUID.fromString(
                    "11111111-2222-4333-8444-555555555555"
            );

    private HttpClient httpClient;
    private HttpResponse<String> response;
    private JsonMapper jsonMapper;
    private Clock clock;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        httpClient = mock(HttpClient.class);
        response = mock(HttpResponse.class);
        jsonMapper = JsonMapper.builder().build();
        clock = Clock.fixed(NOW, ZoneOffset.UTC);
    }

    private WorkerRegistrationClient client(String password) {
        return new WorkerRegistrationClient(
                httpClient,
                jsonMapper,
                URI.create("http://127.0.0.1:8080"),
                "worker-java-local",
                password,
                clock
        );
    }

    private String validResponse() {
        return """
                {
                  "workerId": "worker-java",
                  "workerInstanceId": "%s",
                  "accessToken": "signed-test-token",
                  "tokenType": "Bearer",
                  "expiresAt": "2030-01-01T01:00:00Z"
                }
                """.formatted(INSTANCE_ID);
    }

    private void mockResponse(
            int status,
            String body
    ) throws Exception {
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body);

        when(httpClient.send(
                any(HttpRequest.class),
                org.mockito.ArgumentMatchers
                        .<HttpResponse.BodyHandler<String>>any()
        )).thenReturn(response);
    }

    @Test
    void shouldRegisterAndSendBasicCredentials()
            throws Exception {
        mockResponse(200, validResponse());

        WorkerSession session = client("test-password")
                .register();

        assertEquals("worker-java", session.workerId());
        assertEquals(INSTANCE_ID, session.workerInstanceId());
        assertEquals(
                "signed-test-token",
                session.accessToken()
        );

        var requestCaptor =
                org.mockito.ArgumentCaptor.forClass(
                        HttpRequest.class
                );

        verify(httpClient).send(
                requestCaptor.capture(),
                any()
        );

        HttpRequest request = requestCaptor.getValue();

        assertEquals("POST", request.method());

        assertEquals(
                "/api/v1/worker-instances/register",
                request.uri().getPath()
        );

        String expectedCredentials =
                java.util.Base64.getEncoder()
                        .encodeToString(
                                "worker-java-local:test-password"
                                        .getBytes(
                                                java.nio.charset.StandardCharsets.UTF_8
                                        )
                        );

        assertEquals(
                "Basic " + expectedCredentials,
                request.headers()
                        .firstValue("Authorization")
                        .orElseThrow()
        );

        assertFalse(
                session.toString().contains("signed-test-token")
        );
    }

    @Test
    void shouldRejectMissingCredentialsWithoutHttpCall() {
        assertThrows(
                WorkerRegistrationClient
                        .WorkerRegistrationException.class,
                () -> client("").register()
        );

        verifyNoInteractions(httpClient);
    }

    @Test
    void shouldRejectInvalidCredentials() throws Exception {
        mockResponse(401, "");

        assertThrows(
                WorkerRegistrationClient
                        .WorkerRegistrationException.class,
                () -> client("wrong-password").register()
        );
    }

    @Test
    void shouldRejectServerFailure() throws Exception {
        mockResponse(503, "");

        assertThrows(
                WorkerRegistrationClient
                        .WorkerRegistrationException.class,
                () -> client("test-password").register()
        );
    }

    @Test
    void shouldRejectUnexpectedRedirect() throws Exception {
        mockResponse(302, "");

        assertThrows(
                WorkerRegistrationClient
                        .WorkerRegistrationException.class,
                () -> client("test-password").register()
        );
    }

    @Test
    void shouldRejectMalformedResponse() throws Exception {
        mockResponse(200, "not-valid-json");

        assertThrows(
                WorkerRegistrationClient
                        .WorkerRegistrationException.class,
                () -> client("test-password").register()
        );
    }

    @Test
    void shouldRejectDifferentWorkerIdentity()
            throws Exception {
        mockResponse(
                200,
                validResponse().replace(
                        "\"worker-java\"",
                        "\"worker-dotnet\""
                )
        );

        assertThrows(
                WorkerRegistrationClient
                        .WorkerRegistrationException.class,
                () -> client("test-password").register()
        );
    }

    @Test
    void shouldRejectPlainHttpOutsideLocalhost() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new WorkerRegistrationClient(
                        httpClient,
                        jsonMapper,
                        URI.create(
                                "http://job-manager.example.com"
                        ),
                        "worker-java-local",
                        "test-password",
                        clock
                )
        );
    }
}