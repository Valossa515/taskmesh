
package io.github.valossa515.workerjava.registration;

import org.springframework.stereotype.Component;

import java.time.Clock;

@Component
public class WorkerSessionManager {

    private final WorkerRegistrationClient registrationClient;
    private final Clock clock;

    private volatile WorkerSession session;

    public WorkerSessionManager(
            WorkerRegistrationClient registrationClient,
            Clock clock
    ) {
        this.registrationClient = registrationClient;
        this.clock = clock;
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

    public String tokenForNewClaim() {
        WorkerSession current = requireSession();

        if (!current.canStartNewClaim(clock.instant())) {
            throw new IllegalStateException(
                    "Worker token is nearing expiration. "
                            + "Stop intake and drain before restarting."
            );
        }

        return current.accessToken();
    }

    public String tokenForOutcome() {
        WorkerSession current = requireSession();

        if (!current.canReportOutcome(clock.instant())) {
            throw new IllegalStateException(
                    "Worker token is expired or too close "
                            + "to expiration"
            );
        }

        return current.accessToken();
    }
}