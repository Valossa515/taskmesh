
package io.github.valossa515.jobmanager.infrastructure.scheduling;

import io.github.valossa515.jobmanager.application.job.execution.ExecutionClaimService;
import io.github.valossa515.jobmanager.application.port.IJobExecutionRepository;
import io.github.valossa515.jobmanager.application.port.IJobRepository;
import io.github.valossa515.jobmanager.application.port.IOutboxRepository;
import io.github.valossa515.jobmanager.domain.model.*;
import io.github.valossa515.jobmanager.support.IntegrationTestConfig;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {
        "taskmesh.publisher.scheduling.enabled=false",
        "taskmesh.execution.expiration.scheduling.enabled=true",
        "taskmesh.execution.expiration.scheduling.fixed-delay-ms=100",
        "taskmesh.execution.expiration.scheduling.initial-delay-ms=100"
})
@Import(IntegrationTestConfig.class)
class ExecutionExpirationSchedulerIntegrationTest {

    @Autowired
    private ExecutionClaimService claimService;

    @Autowired
    private IJobRepository jobRepository;

    @Autowired
    private IOutboxRepository outboxRepository;

    @Autowired
    private IJobExecutionRepository executionRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void shouldAutomaticallyExpireExecution() throws Exception {

        Job job = jobRepository.save(
                Job.create("NO_OP", "{}")
        );

        OutboxEvent event = outboxRepository.save(
                OutboxEvent.create(
                        job.getId(),
                        "job.submitted",
                        "job.submitted",
                        "{}"
                )
        );

        claimService.start(
                job.getId(),
                event.getId(),
                UUID.randomUUID(),
                "worker-java",
                "instance-1"
        );

        // Simulate an expired lease without waiting 30 seconds.
        jdbcTemplate.update("""
                UPDATE job_executions
                SET granted_at =
                        clock_timestamp() - INTERVAL '90 seconds',
                    expires_at =
                        clock_timestamp() - INTERVAL '60 seconds'
                WHERE job_id = ?
                """, job.getId());

        // Wait for the scheduler, not a manual sweep.
        long deadline = System.nanoTime()
                + TimeUnit.SECONDS.toNanos(15);

        while (System.nanoTime() < deadline) {

            Job currentJob = jobRepository
                    .findById(job.getId())
                    .orElseThrow();

            JobExecution execution = executionRepository
                    .findByJobId(job.getId())
                    .orElseThrow();

            if (currentJob.getStatus() == JobStatus.FAILED
                    && execution.getStatus()
                    == JobExecutionStatus.EXPIRED) {

                assertEquals(
                        JobExecution.LEASE_EXPIRED_REASON,
                        currentJob.getFailureReason()
                );

                return;
            }

            Thread.sleep(100);
        }

        fail("Scheduler did not expire the execution");
    }
}