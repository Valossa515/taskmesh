
package io.github.valossa515.workerjava.execution;

import com.sun.net.httpserver.HttpServer;

import io.github.valossa515.workerjava.registration.WorkerSessionManager;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ExecutionApiClientTest {

    private static final UUID JOB_ID =
            UUID.fromString(
                    "11111111-1111-4111-8111-111111111111"
            );

    private static final UUID EVENT_ID =
            UUID.fromString(
                    "22222222-2222-4222-8222-222222222222"
            );

    private static final UUID CLAIM_ID =
            UUID.fromString(
                    "33333333-3333-4333-8333-333333333333"
            );

    private static final UUID EXECUTION_ID =
            UUID.fromString(
                    "44444444-4444-4444-8444-444444444444"
            );

    private HttpServer server;
    private HttpClient http;
    private JsonMapper json;
    private WorkerSessionManager sessions;
    private ExecutionApiClient client;
    private URI baseUri;

    private final AtomicReference<Reply> reply =
            new AtomicReference<>();

    private final AtomicInteger callCount =
            new AtomicInteger();

    private final List<CapturedRequest> requests =
            new CopyOnWriteArrayList<>();

    private record Reply(
            int status,
            String body,
            String retryAfter
    ) {
    }

    private record CapturedRequest(
            String method,
            String path,
            String authorization,
            String contentType,
            String body
    ) {
    }

    @BeforeEach
    void setUp() throws Exception {
        json = JsonMapper.builder().build();

        sessions = mock(WorkerSessionManager.class);

        when(sessions.tokenForNewClaim())
                .thenReturn("test-jwt");

        when(sessions.tokenForOutcome())
                .thenReturn("test-jwt");

        server = HttpServer.create(
                new InetSocketAddress("127.0.0.1", 0),
                0
        );

        reply.set(new Reply(200, "{}", null));

        server.createContext("/", exchange -> {
            try (exchange) {
                callCount.incrementAndGet();

                String body = new String(
                        exchange.getRequestBody().readAllBytes(),
                        StandardCharsets.UTF_8
                );

                requests.add(new CapturedRequest(
                        exchange.getRequestMethod(),
                        exchange.getRequestURI().getPath(),
                        exchange.getRequestHeaders()
                                .getFirst("Authorization"),
                        exchange.getRequestHeaders()
                                .getFirst("Content-Type"),
                        body
                ));

                Reply current = reply.get();

                if (current.retryAfter() != null) {
                    exchange.getResponseHeaders().set(
                            "Retry-After",
                            current.retryAfter()
                    );
                }

                exchange.getResponseHeaders().set(
                        "Content-Type",
                        current.status() == 200
                                ? "application/json"
                                : "application/problem+json"
                );

                byte[] bytes = current.body().getBytes(
                        StandardCharsets.UTF_8
                );

                exchange.sendResponseHeaders(
                        current.status(),
                        bytes.length
                );

                exchange.getResponseBody().write(bytes);
            }
        });

        server.start();

        baseUri = URI.create(
                "http://127.0.0.1:"
                        + server.getAddress().getPort()
        );

        http = HttpClient.newBuilder()
                .followRedirects(
                        HttpClient.Redirect.NEVER
                )
                .build();

        client = new ExecutionApiClient(
                http,
                json,
                sessions,
                baseUri
        );
    }

    @AfterEach
    void tearDown() {
        if (http != null) {
            http.close();
        }

        if (server != null) {
            server.stop(0);
        }
    }

    private void respond(int status, String body) {
        reply.set(new Reply(status, body, null));
    }

    private void respond(
            int status,
            String body,
            String retryAfter
    ) {
        reply.set(new Reply(
                status,
                body,
                retryAfter
        ));
    }

    private void problem(
            int status,
            String suffix
    ) {
        respond(
                status,
                """
                {
                  "type": "urn:taskmesh:problem:%s",
                  "status": %d
                }
                """.formatted(suffix, status)
        );
    }

    private String runningResponse() {
        return """
                {
                  "jobId": "%s",
                  "status": "RUNNING",
                  "executionId": "%s",
                  "expiresAt": "2030-01-01T00:00:00Z",
                  "failureReason": null
                }
                """.formatted(JOB_ID, EXECUTION_ID);
    }

    private String completedStartResponse() {
        return """
                {
                  "jobId": "%s",
                  "status": "COMPLETED",
                  "executionId": null,
                  "expiresAt": null,
                  "failureReason": null
                }
                """.formatted(JOB_ID);
    }

    private String completedOutcomeResponse() {
        return """
                {
                  "jobId": "%s",
                  "status": "COMPLETED",
                  "failureReason": null
                }
                """.formatted(JOB_ID);
    }

    private String failedOutcomeResponse(
            String reason
    ) {
        return """
                {
                  "jobId": "%s",
                  "status": "FAILED",
                  "failureReason": %s
                }
                """.formatted(
                JOB_ID,
                json.writeValueAsString(reason)
        );
    }

    private ExecutionApiClient.StartResult start() {
        return client.start(
                JOB_ID,
                EVENT_ID,
                CLAIM_ID
        );
    }

    private ExecutionApiException startFailure() {
        return assertThrows(
                ExecutionApiException.class,
                this::start
        );
    }

    @Test
    void shouldStartUsingJwtAndOriginalClaimIds()
            throws Exception {
        respond(200, runningResponse());

        var result = start();

        assertEquals(
                ExecutionApiClient.JobStatus.RUNNING,
                result.status()
        );

        assertEquals(EXECUTION_ID, result.executionId());
        assertEquals(JOB_ID, result.jobId());
        assertNotNull(result.expiresAt());

        assertEquals(1, requests.size());

        CapturedRequest captured = requests.getFirst();

        assertEquals("POST", captured.method());

        assertEquals(
                "/api/v1/jobs/" + JOB_ID + "/executions/start",
                captured.path()
        );

        assertEquals(
                "Bearer test-jwt",
                captured.authorization()
        );

        assertTrue(
                captured.contentType().startsWith(
                        "application/json"
                )
        );

        var body = json.readTree(captured.body());

        assertEquals(
                EVENT_ID.toString(),
                body.get("eventId").asString()
        );

        assertEquals(
                CLAIM_ID.toString(),
                body.get("claimId").asString()
        );

        verify(sessions).tokenForNewClaim();
        verify(sessions, never()).tokenForOutcome();
    }

    @Test
    void shouldReturnCompletedTerminalObservation() {
        respond(200, completedStartResponse());

        var result = start();

        assertEquals(
                ExecutionApiClient.JobStatus.COMPLETED,
                result.status()
        );

        assertNull(result.executionId());
        assertNull(result.expiresAt());
        assertNull(result.failureReason());
    }

    @Test
    void shouldReturnFailedTerminalObservation() {
        String body = """
                {
                  "jobId": "%s",
                  "status": "FAILED",
                  "executionId": null,
                  "expiresAt": null,
                  "failureReason": "EXECUTION_LEASE_EXPIRED"
                }
                """.formatted(JOB_ID);

        respond(200, body);

        var result = start();

        assertEquals(
                ExecutionApiClient.JobStatus.FAILED,
                result.status()
        );

        assertEquals(
                "EXECUTION_LEASE_EXPIRED",
                result.failureReason()
        );
    }

    @Test
    void shouldCompleteUsingOriginalExecutionId()
            throws Exception {
        respond(200, completedOutcomeResponse());

        var result = client.complete(
                JOB_ID,
                EVENT_ID,
                EXECUTION_ID
        );

        assertEquals(
                ExecutionApiClient.JobStatus.COMPLETED,
                result.status()
        );

        CapturedRequest captured = requests.getFirst();

        assertEquals(
                "/api/v1/jobs/" + JOB_ID
                        + "/executions/" + EXECUTION_ID
                        + "/complete",
                captured.path()
        );

        var body = json.readTree(captured.body());

        assertEquals(
                EVENT_ID.toString(),
                body.get("eventId").asString()
        );

        verify(sessions).tokenForOutcome();
        verify(sessions, never()).tokenForNewClaim();
    }

    @Test
    void shouldSerializeFailureReasonSafely()
            throws Exception {
        String reason = "Task \"failed\"";

        respond(
                200,
                failedOutcomeResponse(reason)
        );

        var result = client.fail(
                JOB_ID,
                EVENT_ID,
                EXECUTION_ID,
                reason
        );

        assertEquals(
                ExecutionApiClient.JobStatus.FAILED,
                result.status()
        );

        assertEquals(reason, result.failureReason());

        var captured = requests.getFirst();

        assertTrue(
                captured.path().endsWith(
                        "/" + EXECUTION_ID + "/fail"
                )
        );

        var body = json.readTree(captured.body());

        assertEquals(
                reason,
                body.get("reason").asString()
        );
    }

    @Test
    void shouldPreserveClaimIdOnExplicitRetry()
            throws Exception {
        respond(200, runningResponse());

        start();
        start();

        assertEquals(2, callCount.get());

        assertEquals(
                json.readTree(requests.get(0).body()),
                json.readTree(requests.get(1).body())
        );
    }

    @Test
    void shouldClassifyActiveExecutionAndKeepRetryAfter() {
        respond(
                409,
                """
                {
                  "type": "urn:taskmesh:problem:execution-active",
                  "status": 409
                }
                """,
                "5"
        );

        ExecutionApiException exception =
                startFailure();

        assertEquals(
                ExecutionFailureKind.ACTIVE_CLAIM,
                exception.kind()
        );

        assertEquals("5", exception.retryAfter());

        assertEquals(409, exception.httpStatus());
    }

    @Test
    void shouldClassifyStaleExecution() {
        problem(409, "execution-stale");

        ExecutionApiException exception = assertThrows(
                ExecutionApiException.class,
                () -> client.complete(
                        JOB_ID,
                        EVENT_ID,
                        EXECUTION_ID
                )
        );

        assertEquals(
                ExecutionFailureKind.STALE_EXECUTION,
                exception.kind()
        );
    }

    @Test
    void shouldClassifyOutcomeConflict() {
        problem(409, "outcome-conflict");

        ExecutionApiException exception = assertThrows(
                ExecutionApiException.class,
                () -> client.complete(
                        JOB_ID,
                        EVENT_ID,
                        EXECUTION_ID
                )
        );

        assertEquals(
                ExecutionFailureKind.OUTCOME_CONFLICT,
                exception.kind()
        );
    }

    @Test
    void shouldQuarantineKnownIntegrityErrors() {
        problem(409, "submission-mismatch");

        assertEquals(
                ExecutionFailureKind.QUARANTINE,
                startFailure().kind()
        );

        problem(404, "job-not-found");

        assertEquals(
                ExecutionFailureKind.QUARANTINE,
                startFailure().kind()
        );

        problem(422, "unsupported-job-type");

        assertEquals(
                ExecutionFailureKind.QUARANTINE,
                startFailure().kind()
        );

        problem(400, "validation-error");

        assertEquals(
                ExecutionFailureKind.QUARANTINE,
                startFailure().kind()
        );
    }

    @Test
    void shouldPauseOnAuthenticationFailure() {
        respond(401, "{}");

        assertEquals(
                ExecutionFailureKind.AUTHENTICATION,
                startFailure().kind()
        );

        respond(403, "{}");

        assertEquals(
                ExecutionFailureKind.AUTHENTICATION,
                startFailure().kind()
        );
    }

    @Test
    void shouldClassifyTransientErrorsWithoutRetrying() {
        respond(503, "{}");

        ExecutionApiException unavailable =
                startFailure();

        assertEquals(
                ExecutionFailureKind.TRANSIENT,
                unavailable.kind()
        );

        assertEquals(
                1,
                callCount.get(),
                "The HTTP adapter must not retry automatically"
        );

        respond(408, "{}");

        assertEquals(
                ExecutionFailureKind.TRANSIENT,
                startFailure().kind()
        );

        respond(429, "{}");

        assertEquals(
                ExecutionFailureKind.TRANSIENT,
                startFailure().kind()
        );
    }

    @Test
    void shouldRejectUnexpectedHttpResponse() {
        problem(409, "unexpected-error");

        assertEquals(
                ExecutionFailureKind.PROTOCOL,
                startFailure().kind()
        );

        respond(302, "{}");

        assertEquals(
                ExecutionFailureKind.PROTOCOL,
                startFailure().kind()
        );
    }

    @Test
    void shouldRejectMalformedOrInconsistentSuccess()
            throws Exception {
        respond(200, "invalid-json");

        assertEquals(
                ExecutionFailureKind.PROTOCOL,
                startFailure().kind()
        );

        UUID anotherJob = UUID.randomUUID();

        respond(
                200,
                runningResponse().replace(
                        JOB_ID.toString(),
                        anotherJob.toString()
                )
        );

        assertEquals(
                ExecutionFailureKind.PROTOCOL,
                startFailure().kind()
        );

        respond(
                200,
                runningResponse().replace(
                        "\"RUNNING\"",
                        "\"PENDING\""
                )
        );

        assertEquals(
                ExecutionFailureKind.PROTOCOL,
                startFailure().kind()
        );
    }

    @Test
    void shouldNotSendWhenSessionIsUnavailable() {
        when(sessions.tokenForNewClaim())
                .thenThrow(new IllegalStateException(
                        "Worker is not registered"
                ));

        ExecutionApiException exception =
                startFailure();

        assertEquals(
                ExecutionFailureKind.AUTHENTICATION,
                exception.kind()
        );

        assertEquals(0, callCount.get());
    }

    @Test
    void shouldRejectManagerOwnedFailureReason() {
        assertThrows(
                IllegalArgumentException.class,
                () -> client.fail(
                        JOB_ID,
                        EVENT_ID,
                        EXECUTION_ID,
                        "EXECUTION_LEASE_EXPIRED"
                )
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> client.fail(
                        JOB_ID,
                        EVENT_ID,
                        EXECUTION_ID,
                        " "
                )
        );

        assertEquals(0, callCount.get());
    }

    @Test
    void shouldRejectInsecureRemoteManagerUri() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ExecutionApiClient(
                        http,
                        json,
                        sessions,
                        URI.create(
                                "http://job-manager.example.com"
                        )
                )
        );
    }

    @Test
    void shouldTreatNetworkFailureAsUnknownOutcome()
            throws Exception {
        HttpClient brokenHttp = mock(HttpClient.class);

        when(brokenHttp.send(
                any(HttpRequest.class),
                org.mockito.ArgumentMatchers
                        .<HttpResponse.BodyHandler<String>>any()
        )).thenThrow(
                new IOException("Connection closed")
        );

        ExecutionApiClient brokenClient =
                new ExecutionApiClient(
                        brokenHttp,
                        json,
                        sessions,
                        baseUri
                );

        ExecutionApiException exception = assertThrows(
                ExecutionApiException.class,
                () -> brokenClient.complete(
                        JOB_ID,
                        EVENT_ID,
                        EXECUTION_ID
                )
        );

        assertEquals(
                ExecutionFailureKind.TRANSIENT,
                exception.kind()
        );

        assertNull(exception.httpStatus());

        verify(brokenHttp, times(1)).send(
                any(HttpRequest.class),
                org.mockito.ArgumentMatchers
                        .<HttpResponse.BodyHandler<String>>any()
        );
    }
}