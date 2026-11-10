
package io.github.valossa515.jobmanager.application.job.execution;

import io.github.valossa515.jobmanager.application.port.*;
import io.github.valossa515.jobmanager.domain.model.*;
import io.github.valossa515.jobmanager.support.IntegrationTestConfig;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {
        "taskmesh.publisher.scheduling.enabled=false",
        "taskmesh.execution.expiration.scheduling.enabled=false"
})
@Import(IntegrationTestConfig.class)
class ExecutionExpirationServiceIntegrationTest {

    @Autowired private ExecutionExpirationService expiration;
    @Autowired private ExecutionExpirationSweepService sweep;
    @Autowired private ExecutionClaimService claims;
    @Autowired private ExecutionOutcomeService outcomes;

    @Autowired private IJobRepository jobs;
    @Autowired private IOutboxRepository events;
    @Autowired private IJobExecutionRepository executions;
    @Autowired private JdbcTemplate jdbc;

    private record Fixture(
            UUID jobId,
            UUID eventId,
            UUID executionId
    ) {}

    private Fixture createRunningJob() {
        Job job = jobs.save(Job.create("NO_OP", "{}"));

        OutboxEvent event = events.save(
                OutboxEvent.create(
                        job.getId(),
                        "job.submitted",
                        "job.submitted",
                        "{}"
                )
        );

        var grant = claims.start(
                job.getId(),
                event.getId(),
                UUID.randomUUID(),
                "worker-java",
                "instance-1"
        );

        return new Fixture(
                job.getId(),
                event.getId(),
                grant.executionId()
        );
    }

    private void makeLeaseExpired(UUID jobId) {
        jdbc.update("""
                UPDATE job_executions
                SET granted_at =
                        clock_timestamp() - INTERVAL '90 seconds',
                    expires_at =
                        clock_timestamp() - INTERVAL '60 seconds'
                WHERE job_id = ?
                """, jobId);
    }

    @Test
    void shouldExpireOverdueExecution() {
        Fixture fixture = createRunningJob();
        makeLeaseExpired(fixture.jobId());

        assertTrue(expiration.expireIfDue(fixture.jobId()));

        Job job = jobs.findById(fixture.jobId())
                .orElseThrow();

        JobExecution execution = executions
                .findByJobId(fixture.jobId())
                .orElseThrow();

        assertEquals(JobStatus.FAILED, job.getStatus());
        assertEquals(
                JobExecutionStatus.EXPIRED,
                execution.getStatus()
        );
        assertEquals(
                JobExecution.LEASE_EXPIRED_REASON,
                job.getFailureReason()
        );
        assertNotNull(job.getCompletedAt());
        assertNotNull(execution.getFinishedAt());
    }

    @Test
    void shouldNotExpireValidLease() {
        Fixture fixture = createRunningJob();

        assertFalse(expiration.expireIfDue(fixture.jobId()));

        assertEquals(
                JobStatus.RUNNING,
                jobs.findById(fixture.jobId())
                        .orElseThrow().getStatus()
        );

        assertEquals(
                JobExecutionStatus.ACTIVE,
                executions.findByJobId(fixture.jobId())
                        .orElseThrow().getStatus()
        );
    }

    @Test
    void shouldPreserveCompletedExecution() {
        Fixture fixture = createRunningJob();

        outcomes.complete(
                fixture.jobId(),
                fixture.eventId(),
                fixture.executionId(),
                "worker-java",
                "instance-1"
        );

        assertFalse(expiration.expireIfDue(fixture.jobId()));

        assertEquals(
                JobStatus.COMPLETED,
                jobs.findById(fixture.jobId())
                        .orElseThrow().getStatus()
        );

        assertEquals(
                JobExecutionStatus.COMPLETED,
                executions.findByJobId(fixture.jobId())
                        .orElseThrow().getStatus()
        );
    }

    @Test
    void shouldProcessExpiredCandidateDuringSweep() {
        Fixture fixture = createRunningJob();
        makeLeaseExpired(fixture.jobId());

        assertTrue(
                executions.findExpiredJobIds(1000)
                        .contains(fixture.jobId())
        );

        assertTrue(sweep.sweep(1000) >= 1);

        assertEquals(
                JobStatus.FAILED,
                jobs.findById(fixture.jobId())
                        .orElseThrow().getStatus()
        );

        assertEquals(
                JobExecutionStatus.EXPIRED,
                executions.findByJobId(fixture.jobId())
                        .orElseThrow().getStatus()
        );
    }

    @Test
    void shouldExpireOnlyOnceUnderConcurrentRequests()
            throws Exception {

        Fixture fixture = createRunningJob();
        makeLeaseExpired(fixture.jobId());

        ExecutorService executor =
                Executors.newFixedThreadPool(2);

        CyclicBarrier barrier = new CyclicBarrier(2);

        try {
            Callable<Boolean> task = () -> {
                barrier.await(10, TimeUnit.SECONDS);

                return expiration.expireIfDue(
                        fixture.jobId()
                );
            };

            Future<Boolean> first = executor.submit(task);
            Future<Boolean> second = executor.submit(task);

            boolean firstExpired =
                    first.get(15, TimeUnit.SECONDS);

            boolean secondExpired =
                    second.get(15, TimeUnit.SECONDS);

            int winners =
                    (firstExpired ? 1 : 0)
                            + (secondExpired ? 1 : 0);

            assertEquals(1, winners);

            assertEquals(
                    JobExecutionStatus.EXPIRED,
                    executions.findByJobId(fixture.jobId())
                            .orElseThrow().getStatus()
            );
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void shouldRejectLateWorkerResultAfterSweep() {
        Fixture fixture = createRunningJob();
        makeLeaseExpired(fixture.jobId());

        assertTrue(expiration.expireIfDue(fixture.jobId()));

        var result = outcomes.complete(
                fixture.jobId(),
                fixture.eventId(),
                fixture.executionId(),
                "worker-java",
                "instance-1"
        );

        assertEquals(
                ExecutionOutcomeService.Decision.STALE,
                result.decision()
        );

        assertEquals(
                JobStatus.FAILED,
                jobs.findById(fixture.jobId())
                        .orElseThrow().getStatus()
        );
    }
}