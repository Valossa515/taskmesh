
package io.github.valossa515.workerjava.registration;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record WorkerSession(
        String workerId,
        UUID workerInstanceId,
        String accessToken,
        Instant expiresAt
) {
    private static final Duration CLAIM_SAFETY_MARGIN =
            Duration.ofSeconds(90);

    private static final Duration OUTCOME_SAFETY_MARGIN =
            Duration.ofSeconds(5);

    public WorkerSession {
        if (!"worker-java".equals(workerId)) {
            throw new IllegalArgumentException(
                    "Expected worker-java identity"
            );
        }

        Objects.requireNonNull(
                workerInstanceId,
                "workerInstanceId is required"
        );

        if (accessToken == null || accessToken.isBlank()) {
            throw new IllegalArgumentException(
                    "Access token is required"
            );
        }

        Objects.requireNonNull(
                expiresAt,
                "expiresAt is required"
        );
    }

    public boolean canStartNewClaim(Instant now) {
        return expiresAt.isAfter(
                now.plus(CLAIM_SAFETY_MARGIN)
        );
    }

    public boolean canReportOutcome(Instant now) {
        return expiresAt.isAfter(
                now.plus(OUTCOME_SAFETY_MARGIN)
        );
    }

    @Override
    public String toString() {
        return "WorkerSession[workerId=" + workerId
                + ", workerInstanceId=" + workerInstanceId
                + ", accessToken=<redacted>"
                + ", expiresAt=" + expiresAt + "]";
    }
}