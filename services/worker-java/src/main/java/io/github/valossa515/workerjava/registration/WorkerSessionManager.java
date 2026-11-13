
package io.github.valossa515.workerjava.registration;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

@Component
public class WorkerSessionManager {

    private static final Duration RENEW_BEFORE =
            Duration.ofMinutes(5);

    private final WorkerRegistrationClient registrationClient;
    private final Clock clock;

    private volatile WorkerSession session;

    private volatile WorkerTokenRenewalException.Kind
            intakeStopKind;

    public WorkerSessionManager(
            WorkerRegistrationClient registrationClient,
            Clock clock
    ) {
        this.registrationClient =
                Objects.requireNonNull(registrationClient);

        this.clock = Objects.requireNonNull(clock);
    }

    public synchronized WorkerSession registerOnce() {
        if (session == null) {
            session = registrationClient.register();
        }

        return session;
    }

    public WorkerSession requireSession() {
        WorkerSession current = session;

        if (current == null) {
            throw new IllegalStateException(
                    "Worker instance is not registered"
            );
        }

        return current;
    }

    public synchronized WorkerSession refreshIfNeeded() {
        WorkerSession current = requireSession();
        Instant now = clock.instant();

        if (intakeStopKind != null) {
            throw new WorkerTokenRenewalException(
                    intakeStopKind,
                    "Worker intake is stopped"
            );
        }

        if (!current.expiresAt().isAfter(now)) {
            intakeStopKind =
                    WorkerTokenRenewalException.Kind.SESSION_EXPIRED;

            throw new WorkerTokenRenewalException(
                    intakeStopKind,
                    "Worker token has expired"
            );
        }

        // Avoid unnecessary HTTP calls.
        if (current.expiresAt().isAfter(
                now.plus(RENEW_BEFORE)
        )) {
            return current;
        }

        try {
            WorkerSession renewed =
                    registrationClient.refresh(current);

            if (renewed == null
                    || !current.workerId().equals(
                    renewed.workerId()
            )
                    || !current.workerInstanceId().equals(
                    renewed.workerInstanceId()
            )
                    || current.accessToken().equals(
                    renewed.accessToken()
            )) {
                throw new WorkerTokenRenewalException(
                        WorkerTokenRenewalException.Kind.PROTOCOL,
                        "Renewal did not preserve worker identity"
                );
            }

            // At the eight-hour session boundary, the
            // manager may not extend the token deadline.
            if (!renewed.expiresAt().isAfter(
                    current.expiresAt()
            )) {
                throw new WorkerTokenRenewalException(
                        WorkerTokenRenewalException.Kind.SESSION_EXPIRED,
                        "Worker session cannot be extended"
                );
            }

            // Publish the new immutable session only
            // after every check succeeds.
            session = renewed;

            return renewed;

        } catch (WorkerTokenRenewalException exception) {
            if (exception.kind()
                    != WorkerTokenRenewalException.Kind.TRANSIENT) {
                intakeStopKind = exception.kind();
            }

            throw exception;
        }
    }

    public String tokenForNewClaim() {
        WorkerSession current = requireSession();

        if (intakeStopKind != null) {
            throw new IllegalStateException(
                    "Worker intake is stopped"
            );
        }

        if (!current.canStartNewClaim(clock.instant())) {
            throw new IllegalStateException(
                    "Worker token is nearing expiration. "
                            + "Stop intake until credentials are renewed."
            );
        }

        return current.accessToken();
    }

    public String tokenForOutcome() {
        WorkerSession current = requireSession();

        // Existing executions may still be reported even
        // when new claims have been disabled.
        if (!current.canReportOutcome(clock.instant())) {
            throw new IllegalStateException(
                    "Worker token is expired or too close "
                            + "to expiration"
            );
        }

        return current.accessToken();
    }
}