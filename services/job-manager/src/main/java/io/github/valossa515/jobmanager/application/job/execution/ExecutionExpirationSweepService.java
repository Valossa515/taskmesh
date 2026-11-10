
package io.github.valossa515.jobmanager.application.job.execution;

import io.github.valossa515.jobmanager.application.port.IJobExecutionRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class ExecutionExpirationSweepService {

    private static final Logger log =
            LoggerFactory.getLogger(
                    ExecutionExpirationSweepService.class
            );

    private final IJobExecutionRepository executions;
    private final ExecutionExpirationService expirationService;

    public ExecutionExpirationSweepService(
            IJobExecutionRepository executions,
            ExecutionExpirationService expirationService
    ) {
        this.executions = executions;
        this.expirationService = expirationService;
    }

    public int sweep(int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException(
                    "Batch size must be greater than zero"
            );
        }

        int expired = 0;

        for (UUID jobId : executions.findExpiredJobIds(limit)) {
            try {
                if (expirationService.expireIfDue(jobId)) {
                    expired++;
                }
            } catch (RuntimeException exception) {
                log.error(
                        "Failed to expire execution for job {}",
                        jobId,
                        exception
                );
            }
        }

        return expired;
    }
}