
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

import static io.github.valossa515.jobmanager.application.job.execution.ExecutionOutcomeService.Decision.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {
        "taskmesh.publisher.scheduling.enabled=false"
})
@Import(IntegrationTestConfig.class)
class ExecutionOutcomeServiceIntegrationTest {

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

    private Fixture createClaimedJob() {
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

    private ExecutionOutcomeService.OutcomeResult complete(
            Fixture fixture
    ) {
        return outcomes.complete(
                fixture.jobId(),
                fixture.eventId(),
                fixture.executionId(),
                "worker-java",
                "instance-1"
        );
    }

    private ExecutionOutcomeService.OutcomeResult fail(
            Fixture fixture,
            String reason
    ) {
        return outcomes.fail(
                fixture.jobId(),
                fixture.eventId(),
                fixture.executionId(),
                "worker-java",
                "instance-1",
                reason
        );
    }

    @Test
    void shouldCompleteRunningExecution() {
        Fixture fixture = createClaimedJob();

        var result = complete(fixture);

        assertEquals(RECORDED, result.decision());
        assertEquals(JobStatus.COMPLETED, result.status());

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
    void shouldReturnSameResultWhenCompletionIsRetried() {
        Fixture fixture = createClaimedJob();

        var first = complete(fixture);

        var before = jobs.findById(fixture.jobId())
                .orElseThrow().getCompletedAt();

        var second = complete(fixture);

        var after = jobs.findById(fixture.jobId())
                .orElseThrow().getCompletedAt();

        assertEquals(RECORDED, first.decision());
        assertEquals(first, second);
        assertEquals(before, after);
    }

    @Test
    void shouldFailRunningExecution() {
        Fixture fixture = createClaimedJob();

        var result = fail(fixture, "SIMULATED_FAILURE");

        assertEquals(RECORDED, result.decision());
        assertEquals(JobStatus.FAILED, result.status());

        var job = jobs.findById(fixture.jobId())
                .orElseThrow();

        var execution = executions
                .findByJobId(fixture.jobId())
                .orElseThrow();

        assertEquals("SIMULATED_FAILURE", job.getFailureReason());
        assertEquals(JobExecutionStatus.FAILED, execution.getStatus());
        assertEquals(
                "SIMULATED_FAILURE",
                execution.getFailureReason()
        );
    }

    @Test
    void shouldReturnSameFailureWhenRetried() {
        Fixture fixture = createClaimedJob();

        var first = fail(fixture, "SIMULATED_FAILURE");

        var before = executions
                .findByJobId(fixture.jobId())
                .orElseThrow().getFinishedAt();

        var second = fail(fixture, "SIMULATED_FAILURE");

        var after = executions
                .findByJobId(fixture.jobId())
                .orElseThrow().getFinishedAt();

        assertEquals(first, second);
        assertEquals(before, after);
    }

    @Test
    void shouldRejectContradictoryTerminalReport() {
        Fixture fixture = createClaimedJob();

        complete(fixture);

        assertThrows(
                ExecutionOutcomeService.OutcomeConflictException.class,
                () -> fail(fixture, "SIMULATED_FAILURE")
        );

        assertEquals(
                JobStatus.COMPLETED,
                jobs.findById(fixture.jobId())
                        .orElseThrow().getStatus()
        );
    }

    @Test
    void shouldRejectUnknownExecutionAndWorker() {
        Fixture fixture = createClaimedJob();

        assertThrows(
                ExecutionOutcomeService.ExecutionStaleException.class,
                () -> outcomes.complete(
                        fixture.jobId(),
                        fixture.eventId(),
                        UUID.randomUUID(),
                        "worker-java",
                        "instance-1"
                )
        );

        assertThrows(
                ExecutionOutcomeService.ExecutionStaleException.class,
                () -> outcomes.complete(
                        fixture.jobId(),
                        fixture.eventId(),
                        fixture.executionId(),
                        "worker-dotnet",
                        "instance-2"
                )
        );

        assertEquals(
                JobStatus.RUNNING,
                jobs.findById(fixture.jobId())
                        .orElseThrow().getStatus()
        );
    }

    @Test
    void shouldExpireLateCompletionAndPersistFailure() {
        Fixture fixture = createClaimedJob();

        // Simulate an expired persisted lease without Thread.sleep().
        jdbc.update("""
                UPDATE job_executions
                SET granted_at = clock_timestamp() - INTERVAL '90 seconds',
                    expires_at = clock_timestamp() - INTERVAL '60 seconds'
                WHERE job_id = ?
                """, fixture.jobId());

        var result = complete(fixture);

        assertEquals(STALE, result.decision());
        assertEquals(JobStatus.FAILED, result.status());

        var job = jobs.findById(fixture.jobId())
                .orElseThrow();

        var execution = executions
                .findByJobId(fixture.jobId())
                .orElseThrow();

        assertEquals(
                JobExecution.LEASE_EXPIRED_REASON,
                job.getFailureReason()
        );
        assertEquals(
                JobExecutionStatus.EXPIRED,
                execution.getStatus()
        );

        // A repeated late report remains stale.
        assertEquals(STALE, complete(fixture).decision());
    }

    @Test
    void shouldPersistOnlyOneOfTwoRacingOutcomes()
            throws Exception {

        Fixture fixture = createClaimedJob();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);

        try {
            Future<Boolean> completion = pool.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);

                try {
                    return complete(fixture).decision() == RECORDED;
                } catch (
                        ExecutionOutcomeService
                                .OutcomeConflictException ex
                ) {
                    return false;
                }
            });

            Future<Boolean> failure = pool.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);

                try {
                    return fail(
                            fixture,
                            "SIMULATED_FAILURE"
                    ).decision() == RECORDED;
                } catch (
                        ExecutionOutcomeService
                                .OutcomeConflictException ex
                ) {
                    return false;
                }
            });

            boolean completeWon =
                    completion.get(15, TimeUnit.SECONDS);

            boolean failWon =
                    failure.get(15, TimeUnit.SECONDS);

            assertTrue(completeWon ^ failWon);

            Job job = jobs.findById(fixture.jobId())
                    .orElseThrow();

            JobExecution execution = executions
                    .findByJobId(fixture.jobId())
                    .orElseThrow();

            if (completeWon) {
                assertEquals(
                        JobStatus.COMPLETED,
                        job.getStatus()
                );
                assertEquals(
                        JobExecutionStatus.COMPLETED,
                        execution.getStatus()
                );
            } else {
                assertEquals(JobStatus.FAILED, job.getStatus());
                assertEquals(
                        JobExecutionStatus.FAILED,
                        execution.getStatus()
                );
            }
        } finally {
            pool.shutdownNow();
        }
    }
}