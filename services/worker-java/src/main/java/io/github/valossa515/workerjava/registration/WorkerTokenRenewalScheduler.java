
package io.github.valossa515.workerjava.registration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        prefix = "taskmesh.worker",
        name = "registration-on-startup",
        havingValue = "true"
)
public class WorkerTokenRenewalScheduler {

    private static final Logger logger =
            LoggerFactory.getLogger(
                    WorkerTokenRenewalScheduler.class
            );

    private final WorkerSessionManager sessions;

    private boolean permanentFailureLogged;

    public WorkerTokenRenewalScheduler(
            WorkerSessionManager sessions
    ) {
        this.sessions = sessions;
    }

    @Scheduled(
            initialDelayString =
                    "${taskmesh.worker.renewal.initial-delay-ms:60000}",
            fixedDelayString =
                    "${taskmesh.worker.renewal.check-interval-ms:60000}"
    )
    public void check() {
        try {
            WorkerSession before = sessions.requireSession();

            WorkerSession after =
                    sessions.refreshIfNeeded();

            if (before != after) {
                logger.info(
                        "Worker JWT renewed for instance {}. "
                                + "New expiry: {}",
                        after.workerInstanceId(),
                        after.expiresAt()
                );
            }

        } catch (WorkerTokenRenewalException exception) {
            if (exception.kind()
                    == WorkerTokenRenewalException.Kind.TRANSIENT) {
                logger.warn(
                        "Temporary worker JWT renewal failure; "
                                + "will retry at the next check"
                );
                return;
            }

            if (!permanentFailureLogged) {
                permanentFailureLogged = true;

                logger.error(
                        "Worker token renewal stopped intake: {}",
                        exception.kind()
                );
            }
        }
    }
}