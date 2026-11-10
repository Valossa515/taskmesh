
package io.github.valossa515.jobmanager.infrastructure.scheduling;

import io.github.valossa515.jobmanager.application.job.execution.ExecutionExpirationSweepService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

public class ExecutionExpirationScheduler {

    private static final Logger log =
            LoggerFactory.getLogger(
                    ExecutionExpirationScheduler.class
            );

    private final ExecutionExpirationSweepService sweepService;
    private final int batchSize;

    public ExecutionExpirationScheduler(
            ExecutionExpirationSweepService sweepService,
            int batchSize
    ) {
        if (batchSize <= 0) {
            throw new IllegalArgumentException(
                    "Batch size must be greater than zero"
            );
        }

        this.sweepService = sweepService;
        this.batchSize = batchSize;
    }

    @Scheduled(
            fixedDelayString =
                    "${taskmesh.execution.expiration.scheduling.fixed-delay-ms:5000}",
            initialDelayString =
                    "${taskmesh.execution.expiration.scheduling.initial-delay-ms:5000}"
    )
    public void expireDue() {
        int expired = sweepService.sweep(batchSize);

        if (expired > 0) {
            log.info(
                    "Expired {} job executions",
                    expired
            );
        }
    }
}