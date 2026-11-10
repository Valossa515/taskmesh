
package io.github.valossa515.jobmanager.application.job.execution;

import io.github.valossa515.jobmanager.application.port.IJobExecutionRepository;
import io.github.valossa515.jobmanager.application.port.IJobRepository;
import io.github.valossa515.jobmanager.application.port.IOutboxRepository;
import io.github.valossa515.jobmanager.domain.model.*;
import io.github.valossa515.jobmanager.support.IntegrationTestConfig;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.util.UUID;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {
        "taskmesh.publisher.scheduling.enabled=false"
})
@Import(IntegrationTestConfig.class)
class ExecutionClaimServiceIntegrationTest {

    @Autowired
    private ExecutionClaimService claimService;

    @Autowired
    private IJobRepository jobRepository;

    @Autowired
    private IOutboxRepository outboxRepository;

    @Autowired
    private IJobExecutionRepository executionRepository;

    private record Fixture(
            UUID jobId,
            UUID eventId
    ) {}

    private record ClaimAttempt(
            boolean granted,
            UUID executionId
    ) {}

    private Fixture createFixture() {
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

        return new Fixture(job.getId(), event.getId());
    }

    @Test
    void shouldGrantExecutionForPendingJob() {
        Fixture fixture = createFixture();

        var result = claimService.start(
                fixture.jobId(),
                fixture.eventId(),
                UUID.randomUUID(),
                "worker-java",
                "instance-1"
        );

        Job job = jobRepository
                .findById(fixture.jobId())
                .orElseThrow();

        JobExecution execution = executionRepository
                .findByJobId(fixture.jobId())
                .orElseThrow();

        assertAll(
                () -> assertEquals(
                        JobStatus.RUNNING,
                        job.getStatus()
                ),
                () -> assertNotNull(job.getStartedAt()),
                () -> assertEquals(
                        JobExecutionStatus.ACTIVE,
                        execution.getStatus()
                ),
                () -> assertEquals(
                        execution.getExecutionId(),
                        result.executionId()
                ),
                () -> assertEquals(
                        execution.getExpiresAt(),
                        result.expiresAt()
                ),
                () -> assertEquals(
                        fixture.eventId(),
                        execution.getEventId()
                )
        );
    }

    @Test
    void shouldReturnSameGrantForSameClaim() {
        Fixture fixture = createFixture();

        UUID claimId = UUID.randomUUID();

        var first = claimService.start(
                fixture.jobId(),
                fixture.eventId(),
                claimId,
                "worker-java",
                "instance-1"
        );

        var second = claimService.start(
                fixture.jobId(),
                fixture.eventId(),
                claimId,
                "worker-java",
                "instance-1"
        );

        assertAll(
                () -> assertEquals(
                        first.executionId(),
                        second.executionId()
                ),
                () -> assertEquals(
                        first.expiresAt(),
                        second.expiresAt()
                ),
                () -> assertEquals(
                        JobStatus.RUNNING,
                        second.status()
                )
        );
    }

    @Test
    void shouldRejectDifferentClaim() {
        Fixture fixture = createFixture();

        claimService.start(
                fixture.jobId(),
                fixture.eventId(),
                UUID.randomUUID(),
                "worker-java",
                "instance-1"
        );

        assertThrows(
                ExecutionClaimService
                        .ExecutionAlreadyClaimedException.class,
                () -> claimService.start(
                        fixture.jobId(),
                        fixture.eventId(),
                        UUID.randomUUID(),
                        "worker-dotnet",
                        "instance-2"
                )
        );

        Job job = jobRepository
                .findById(fixture.jobId())
                .orElseThrow();

        JobExecution execution = executionRepository
                .findByJobId(fixture.jobId())
                .orElseThrow();

        assertEquals(JobStatus.RUNNING, job.getStatus());
        assertEquals(
                JobExecutionStatus.ACTIVE,
                execution.getStatus()
        );
        assertEquals(
                "worker-java",
                execution.getWorkerId()
        );
    }

    @Test
    void shouldRejectUnrelatedSubmission() {
        Fixture first = createFixture();
        Fixture second = createFixture();

        assertThrows(
                IllegalArgumentException.class,
                () -> claimService.start(
                        first.jobId(),
                        second.eventId(),
                        UUID.randomUUID(),
                        "worker-java",
                        "instance-1"
                )
        );

        Job job = jobRepository
                .findById(first.jobId())
                .orElseThrow();

        assertEquals(JobStatus.PENDING, job.getStatus());

        assertTrue(
                executionRepository
                        .findByJobId(first.jobId())
                        .isEmpty()
        );
    }

    @Test
    void shouldGrantOnlyOneConcurrentClaim() throws Exception {
        Fixture fixture = createFixture();

        CyclicBarrier barrier = new CyclicBarrier(2);

        ExecutorService executor =
                Executors.newFixedThreadPool(2);

        try {
            Future<ClaimAttempt> javaWorker = executor.submit(
                    () -> attemptClaim(
                            fixture,
                            UUID.randomUUID(),
                            "worker-java",
                            "instance-java",
                            barrier
                    )
            );

            Future<ClaimAttempt> dotnetWorker = executor.submit(
                    () -> attemptClaim(
                            fixture,
                            UUID.randomUUID(),
                            "worker-dotnet",
                            "instance-dotnet",
                            barrier
                    )
            );

            ClaimAttempt first =
                    javaWorker.get(15, TimeUnit.SECONDS);

            ClaimAttempt second =
                    dotnetWorker.get(15, TimeUnit.SECONDS);

            long winners =
                    (first.granted() ? 1 : 0)
                            + (second.granted() ? 1 : 0);

            assertEquals(1, winners);

            Job job = jobRepository
                    .findById(fixture.jobId())
                    .orElseThrow();

            JobExecution execution = executionRepository
                    .findByJobId(fixture.jobId())
                    .orElseThrow();

            assertEquals(JobStatus.RUNNING, job.getStatus());
            assertEquals(
                    JobExecutionStatus.ACTIVE,
                    execution.getStatus()
            );

            UUID winningExecutionId = first.granted()
                    ? first.executionId()
                    : second.executionId();

            assertEquals(
                    winningExecutionId,
                    execution.getExecutionId()
            );

        } finally {
            executor.shutdownNow();
        }
    }

    private ClaimAttempt attemptClaim(
            Fixture fixture,
            UUID claimId,
            String workerId,
            String instanceId,
            CyclicBarrier barrier
    ) throws Exception {

        // Both threads reach the claim concurrently.
        barrier.await(10, TimeUnit.SECONDS);

        try {
            var result = claimService.start(
                    fixture.jobId(),
                    fixture.eventId(),
                    claimId,
                    workerId,
                    instanceId
            );

            return new ClaimAttempt(
                    true,
                    result.executionId()
            );

        } catch (
                ExecutionClaimService
                        .ExecutionAlreadyClaimedException ex
        ) {
            return new ClaimAttempt(false, null);
        }
    }
}
