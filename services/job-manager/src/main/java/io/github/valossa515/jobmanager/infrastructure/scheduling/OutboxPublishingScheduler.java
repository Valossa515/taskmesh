
package io.github.valossa515.jobmanager.infrastructure.scheduling;

import io.github.valossa515.jobmanager.application.service.OutboxPublishingService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

public class OutboxPublishingScheduler {

    private static final Logger log =
            LoggerFactory.getLogger(
                    OutboxPublishingScheduler.class
            );

    private final OutboxPublishingService publishingService;

    public OutboxPublishingScheduler(
            OutboxPublishingService publishingService
    ) {
        this.publishingService = publishingService;
    }

    @Scheduled(
            fixedDelayString =
                    "${taskmesh.publisher.scheduling.fixed-delay-ms:5000}",
            initialDelayString =
                    "${taskmesh.publisher.scheduling.initial-delay-ms:5000}"
    )
    public void publishPending() {
        log.debug("Starting scheduled outbox publishing");

        try {
            int published = publishingService.publishBatch();

            if (published > 0) {
                log.info(
                        "Scheduled outbox publishing completed: {} events",
                        published
                );
            }
        } catch (Exception exception) {
            log.error(
                    "Scheduled outbox publishing failed",
                    exception
            );
        }
    }
}