
package io.github.valossa515.workerjava.registration;

import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

public class WorkerRegistrationClient {

    private static final String REGISTRATION_PATH =
            "/api/v1/worker-instances/register";

    private static final String REFRESH_PATH =
            "/api/v1/worker-instances/refresh";

    private final HttpClient httpClient;
    private final JsonMapper jsonMapper;
    private final URI registrationUri;
    private final String username;
    private final String password;
    private final Clock clock;

    public WorkerRegistrationClient(
            HttpClient httpClient,
            JsonMapper jsonMapper,
            URI managerBaseUri,
            String username,
            String password,
            Clock clock
    ) {
        this.httpClient = Objects.requireNonNull(httpClient);
        this.jsonMapper = Objects.requireNonNull(jsonMapper);
        this.registrationUri =
                validateBaseUri(managerBaseUri)
                        .resolve(REGISTRATION_PATH);
        this.username = Objects.requireNonNull(username);
        this.password = Objects.requireNonNull(password);
        this.clock = Objects.requireNonNull(clock);
    }

    public WorkerSession register() {
        if (username.isBlank() || password.isBlank()) {
            throw new WorkerRegistrationException(
                    "Worker registration credentials are not configured"
            );
        }

        String credentials = username + ":" + password;

        String basic = Base64.getEncoder().encodeToString(
                credentials.getBytes(StandardCharsets.UTF_8)
        );

        HttpRequest request = HttpRequest.newBuilder()
                .uri(registrationUri)
                .timeout(Duration.ofSeconds(5))
                .header("Authorization", "Basic " + basic)
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();

        HttpResponse<String> response;

        try {
            response = httpClient.send(
                    request,
                    HttpResponse.BodyHandlers.ofString(
                            StandardCharsets.UTF_8
                    )
            );
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();

            throw new WorkerRegistrationException(
                    "Worker registration was interrupted",
                    exception
            );
        } catch (IOException exception) {
            throw new WorkerRegistrationException(
                    "Job Manager is unavailable during registration",
                    exception
            );
        }

        int status = response.statusCode();

        if (status == 401 || status == 403) {
            throw new WorkerRegistrationException(
                    "Worker registration was rejected: HTTP " + status
            );
        }

        if (status != 200) {
            throw new WorkerRegistrationException(
                    "Unexpected worker registration response: HTTP "
                            + status
            );
        }

        RegistrationResponse data;

        try {
            data = jsonMapper.readValue(
                    response.body(),
                    RegistrationResponse.class
            );
        } catch (Exception exception) {
            throw new WorkerRegistrationException(
                    "Invalid worker registration response",
                    exception
            );
        }

        if (data == null
                || !"worker-java".equals(data.workerId())
                || data.workerInstanceId() == null
                || !"Bearer".equals(data.tokenType())
                || data.accessToken() == null
                || data.accessToken().isBlank()
                || data.expiresAt() == null
                || !data.expiresAt().isAfter(
                clock.instant().plusSeconds(90)
        )) {
            throw new WorkerRegistrationException(
                    "Worker registration response is incomplete or invalid"
            );
        }

        return new WorkerSession(
                data.workerId(),
                data.workerInstanceId(),
                data.accessToken(),
                data.expiresAt()
        );
    }

    private static URI validateBaseUri(URI uri) {
        Objects.requireNonNull(uri, "Manager URI is required");

        String scheme = uri.getScheme();
        String host = uri.getHost();

        if (scheme == null || host == null
                || uri.getRawUserInfo() != null
                || uri.getRawQuery() != null
                || uri.getRawFragment() != null
                || (uri.getPath() != null
                && !uri.getPath().isEmpty()
                && !"/".equals(uri.getPath()))) {
            throw new IllegalArgumentException(
                    "Invalid Job Manager base URL"
            );
        }

        boolean https = "https".equalsIgnoreCase(scheme);

        boolean localHttp =
                "http".equalsIgnoreCase(scheme)
                        && Set.of("localhost", "127.0.0.1", "[::1]")
                        .contains(host);

        if (!https && !localHttp) {
            throw new IllegalArgumentException(
                    "HTTPS is required outside localhost"
            );
        }

        return uri;
    }

    public record RegistrationResponse(
            String workerId,
            UUID workerInstanceId,
            String accessToken,
            String tokenType,
            Instant expiresAt
    ) {
    }

    public static class WorkerRegistrationException
            extends IllegalStateException {

        public WorkerRegistrationException(String message) {
            super(message);
        }

        public WorkerRegistrationException(
                String message,
                Throwable cause
        ) {
            super(message, cause);
        }
    }


    public WorkerSession refresh(WorkerSession current) {
        Objects.requireNonNull(
                current,
                "Current worker session is required"
        );

        HttpRequest request = HttpRequest.newBuilder()
                .uri(registrationUri.resolve(REFRESH_PATH))
                .timeout(Duration.ofSeconds(5))
                .header(
                        "Authorization",
                        "Bearer " + current.accessToken()
                )
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();

        HttpResponse<String> response;

        try {
            response = httpClient.send(
                    request,
                    HttpResponse.BodyHandlers.ofString(
                            StandardCharsets.UTF_8
                    )
            );
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();

            throw new WorkerTokenRenewalException(
                    WorkerTokenRenewalException.Kind.TRANSIENT,
                    "Worker token renewal was interrupted",
                    exception
            );
        } catch (IOException exception) {
            throw new WorkerTokenRenewalException(
                    WorkerTokenRenewalException.Kind.TRANSIENT,
                    "Job Manager is unavailable during token renewal",
                    exception
            );
        }

        int status = response.statusCode();

        if (status == 401 || status == 403) {
            throw new WorkerTokenRenewalException(
                    WorkerTokenRenewalException.Kind.REJECTED,
                    "Worker token renewal was rejected"
            );
        }

        if (status == 409) {
            if (isSessionExpiredProblem(response.body())) {
                throw new WorkerTokenRenewalException(
                        WorkerTokenRenewalException.Kind.SESSION_EXPIRED,
                        "Maximum worker session lifetime reached"
                );
            }

            throw new WorkerTokenRenewalException(
                    WorkerTokenRenewalException.Kind.PROTOCOL,
                    "Unexpected renewal conflict"
            );
        }

        if (status == 408
                || status == 429
                || status >= 500 && status <= 599) {
            throw new WorkerTokenRenewalException(
                    WorkerTokenRenewalException.Kind.TRANSIENT,
                    "Token renewal is temporarily unavailable"
            );
        }

        if (status != 200) {
            throw new WorkerTokenRenewalException(
                    WorkerTokenRenewalException.Kind.PROTOCOL,
                    "Unexpected token renewal HTTP status: " + status
            );
        }

        RegistrationResponse data;

        try {
            data = jsonMapper.readValue(
                    response.body(),
                    RegistrationResponse.class
            );
        } catch (Exception exception) {
            throw new WorkerTokenRenewalException(
                    WorkerTokenRenewalException.Kind.PROTOCOL,
                    "Malformed token renewal response",
                    exception
            );
        }

        if (data == null
                || !current.workerId().equals(data.workerId())
                || !current.workerInstanceId().equals(
                data.workerInstanceId()
        )
                || !"Bearer".equals(data.tokenType())
                || data.accessToken() == null
                || data.accessToken().isBlank()
                || data.accessToken().equals(current.accessToken())
                || data.expiresAt() == null
                || !data.expiresAt().isAfter(
                clock.instant().plusSeconds(90)
        )) {
            throw new WorkerTokenRenewalException(
                    WorkerTokenRenewalException.Kind.PROTOCOL,
                    "Invalid token renewal response or identity mismatch"
            );
        }

        return new WorkerSession(
                data.workerId(),
                data.workerInstanceId(),
                data.accessToken(),
                data.expiresAt()
        );
    }

    private boolean isSessionExpiredProblem(String body) {
        try {
            JsonNode problem = jsonMapper.readTree(body);

            if (problem == null || !problem.isObject()) {
                return false;
            }

            JsonNode type = problem.get("type");

            return type != null
                    && "urn:taskmesh:problem:worker-session-expired"
                    .equals(type.asString());
        } catch (Exception exception) {
            return false;
        }
    }
}