
package io.github.valossa515.workerjava.execution;

import io.github.valossa515.workerjava.registration.WorkerSessionManager;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public class ExecutionApiClient {

    private static final String PROBLEM_PREFIX =
            "urn:taskmesh:problem:";

    private static final String LEASE_EXPIRED_REASON =
            "EXECUTION_LEASE_EXPIRED";

    private final HttpClient http;
    private final JsonMapper json;
    private final WorkerSessionManager sessions;
    private final URI managerUri;

    public ExecutionApiClient(
            HttpClient http,
            JsonMapper json,
            WorkerSessionManager sessions,
            URI managerUri
    ) {
        this.http = Objects.requireNonNull(http);
        this.json = Objects.requireNonNull(json);
        this.sessions = Objects.requireNonNull(sessions);
        this.managerUri = validateManagerUri(managerUri);
    }

    public StartResult start(
            UUID jobId,
            UUID eventId,
            UUID claimId
    ) {
        Objects.requireNonNull(jobId, "jobId is required");
        Objects.requireNonNull(eventId, "eventId is required");
        Objects.requireNonNull(claimId, "claimId is required");

        String body = post(
                executionPath(jobId) + "/start",
                Map.of(
                        "eventId", eventId.toString(),
                        "claimId", claimId.toString()
                ),
                true
        );

        StartResult result = deserialize(
                body,
                StartResult.class
        );

        validateStartResult(jobId, result);

        return result;
    }

    public OutcomeResult complete(
            UUID jobId,
            UUID eventId,
            UUID executionId
    ) {
        Objects.requireNonNull(jobId, "jobId is required");
        Objects.requireNonNull(eventId, "eventId is required");
        Objects.requireNonNull(
                executionId,
                "executionId is required"
        );

        String body = post(
                executionPath(jobId)
                        + "/" + executionId + "/complete",
                Map.of("eventId", eventId.toString()),
                false
        );

        OutcomeResult result = deserialize(
                body,
                OutcomeResult.class
        );

        validateOutcomeResult(
                jobId,
                JobStatus.COMPLETED,
                null,
                result
        );

        return result;
    }

    public OutcomeResult fail(
            UUID jobId,
            UUID eventId,
            UUID executionId,
            String reason
    ) {
        Objects.requireNonNull(jobId, "jobId is required");
        Objects.requireNonNull(eventId, "eventId is required");
        Objects.requireNonNull(
                executionId,
                "executionId is required"
        );

        if (reason == null
                || reason.isBlank()
                || LEASE_EXPIRED_REASON.equals(reason)) {
            throw new IllegalArgumentException(
                    "Invalid worker failure reason"
            );
        }

        String body = post(
                executionPath(jobId)
                        + "/" + executionId + "/fail",
                Map.of(
                        "eventId", eventId.toString(),
                        "reason", reason
                ),
                false
        );

        OutcomeResult result = deserialize(
                body,
                OutcomeResult.class
        );

        validateOutcomeResult(
                jobId,
                JobStatus.FAILED,
                reason,
                result
        );

        return result;
    }

    private String executionPath(UUID jobId) {
        return "/api/v1/jobs/" + jobId + "/executions";
    }

    private String post(
            String path,
            Map<String, String> command,
            boolean newClaim
    ) {
        String token;

        try {
            token = newClaim
                    ? sessions.tokenForNewClaim()
                    : sessions.tokenForOutcome();

        } catch (IllegalStateException exception) {
            throw failure(
                    ExecutionFailureKind.AUTHENTICATION,
                    null,
                    null,
                    null,
                    exception
            );
        }

        String payload = json.writeValueAsString(command);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(managerUri.resolve(path))
                .timeout(Duration.ofSeconds(5))
                .header(
                        "Authorization",
                        "Bearer " + token
                )
                .header(
                        "Content-Type",
                        "application/json"
                )
                .header("Accept", "application/json")
                .POST(
                        HttpRequest.BodyPublishers.ofString(
                                payload,
                                StandardCharsets.UTF_8
                        )
                )
                .build();

        HttpResponse<String> response;

        try {
            response = http.send(
                    request,
                    HttpResponse.BodyHandlers.ofString(
                            StandardCharsets.UTF_8
                    )
            );

        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();

            throw failure(
                    ExecutionFailureKind.TRANSIENT,
                    null,
                    null,
                    null,
                    exception
            );

        } catch (IOException exception) {
            // The Job Manager may have committed before
            // the connection failed. Outcome is unknown.
            throw failure(
                    ExecutionFailureKind.TRANSIENT,
                    null,
                    null,
                    null,
                    exception
            );
        }

        if (response.statusCode() != 200) {
            throw classifyFailure(response);
        }

        return response.body();
    }

    private ExecutionApiException classifyFailure(
            HttpResponse<String> response
    ) {
        int status = response.statusCode();

        String retryAfter = response.headers()
                .firstValue("Retry-After")
                .orElse(null);

        if (status == 401 || status == 403) {
            return failure(
                    ExecutionFailureKind.AUTHENTICATION,
                    status,
                    null,
                    retryAfter,
                    null
            );
        }

        if (status == 408
                || status == 429
                || status >= 500 && status <= 599) {
            return failure(
                    ExecutionFailureKind.TRANSIENT,
                    status,
                    null,
                    retryAfter,
                    null
            );
        }

        String problemType = readProblemType(
                response.body()
        );

        ExecutionFailureKind kind = switch (status) {
            case 409 -> {
                if (matches(
                        problemType,
                        "execution-active"
                )) {
                    yield ExecutionFailureKind.ACTIVE_CLAIM;
                }

                if (matches(
                        problemType,
                        "execution-stale"
                )) {
                    yield ExecutionFailureKind.STALE_EXECUTION;
                }

                if (matches(
                        problemType,
                        "outcome-conflict"
                )) {
                    yield ExecutionFailureKind.OUTCOME_CONFLICT;
                }

                if (matches(
                        problemType,
                        "submission-mismatch"
                )) {
                    yield ExecutionFailureKind.QUARANTINE;
                }

                yield ExecutionFailureKind.PROTOCOL;
            }

            case 400 -> {
                if (matches(problemType, "validation-error")
                        || matches(
                        problemType,
                        "invalid-request-body"
                )) {
                    yield ExecutionFailureKind.QUARANTINE;
                }

                yield ExecutionFailureKind.PROTOCOL;
            }

            case 404 -> matches(
                    problemType,
                    "job-not-found"
            )
                    ? ExecutionFailureKind.QUARANTINE
                    : ExecutionFailureKind.PROTOCOL;

            case 422 -> matches(
                    problemType,
                    "unsupported-job-type"
            )
                    ? ExecutionFailureKind.QUARANTINE
                    : ExecutionFailureKind.PROTOCOL;

            default -> ExecutionFailureKind.PROTOCOL;
        };

        return failure(
                kind,
                status,
                problemType,
                retryAfter,
                null
        );
    }

    private String readProblemType(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }

        try {
            JsonNode node = json.readTree(body);

            if (node == null || !node.isObject()) {
                return null;
            }

            JsonNode type = node.get("type");

            return type == null
                    ? null
                    : type.asString();

        } catch (Exception exception) {
            return null;
        }
    }

    private boolean matches(
            String actual,
            String expectedSuffix
    ) {
        return (PROBLEM_PREFIX + expectedSuffix)
                .equals(actual);
    }

    private <T> T deserialize(
            String body,
            Class<T> type
    ) {
        if (body == null || body.isBlank()) {
            throw protocolFailure();
        }

        try {
            return json.readValue(body, type);

        } catch (Exception exception) {
            throw failure(
                    ExecutionFailureKind.PROTOCOL,
                    200,
                    null,
                    null,
                    exception
            );
        }
    }

    private void validateStartResult(
            UUID expectedJobId,
            StartResult result
    ) {
        if (result == null
                || !expectedJobId.equals(result.jobId())
                || result.status() == null) {
            throw protocolFailure();
        }

        if (result.status() == JobStatus.RUNNING) {
            if (result.executionId() == null
                    || result.expiresAt() == null
                    || result.failureReason() != null) {
                throw protocolFailure();
            }

            return;
        }

        // Terminal observations have no live execution grant.
        if (result.executionId() != null
                || result.expiresAt() != null) {
            throw protocolFailure();
        }

        if (result.status() == JobStatus.COMPLETED
                && result.failureReason() != null) {
            throw protocolFailure();
        }

        if (result.status() == JobStatus.FAILED
                && (result.failureReason() == null
                || result.failureReason().isBlank())) {
            throw protocolFailure();
        }
    }

    private void validateOutcomeResult(
            UUID expectedJobId,
            JobStatus expectedStatus,
            String expectedReason,
            OutcomeResult result
    ) {
        if (result == null
                || !expectedJobId.equals(result.jobId())
                || result.status() != expectedStatus
                || !Objects.equals(
                expectedReason,
                result.failureReason()
        )) {
            throw protocolFailure();
        }
    }

    private ExecutionApiException protocolFailure() {
        return failure(
                ExecutionFailureKind.PROTOCOL,
                200,
                null,
                null,
                null
        );
    }

    private ExecutionApiException failure(
            ExecutionFailureKind kind,
            Integer status,
            String problemType,
            String retryAfter,
            Throwable cause
    ) {
        return new ExecutionApiException(
                kind,
                status,
                problemType,
                retryAfter,
                cause
        );
    }

    private static URI validateManagerUri(URI uri) {
        Objects.requireNonNull(
                uri,
                "Job Manager URI is required"
        );

        String scheme = uri.getScheme();
        String host = uri.getHost();

        if (scheme == null
                || host == null
                || uri.getRawUserInfo() != null
                || uri.getRawQuery() != null
                || uri.getRawFragment() != null
                || uri.getPath() != null
                && !uri.getPath().isEmpty()
                && !"/".equals(uri.getPath())) {
            throw new IllegalArgumentException(
                    "Invalid Job Manager base URL"
            );
        }

        boolean https =
                "https".equalsIgnoreCase(scheme);

        boolean localHttp =
                "http".equalsIgnoreCase(scheme)
                        && Set.of(
                        "localhost",
                        "127.0.0.1",
                        "[::1]"
                ).contains(host);

        if (!https && !localHttp) {
            throw new IllegalArgumentException(
                    "HTTPS is required outside localhost"
            );
        }

        return uri;
    }

    public enum JobStatus {
        RUNNING,
        COMPLETED,
        FAILED
    }

    public record StartResult(
            UUID jobId,
            JobStatus status,
            UUID executionId,
            Instant expiresAt,
            String failureReason
    ) {
    }

    public record OutcomeResult(
            UUID jobId,
            JobStatus status,
            String failureReason
    ) {
    }
}