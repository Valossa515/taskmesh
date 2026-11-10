
package io.github.valossa515.jobmanager.infrastructure.persistence;

import io.github.valossa515.jobmanager.application.port.IJobRepository;
import io.github.valossa515.jobmanager.application.port.IOutboxRepository;
import io.github.valossa515.jobmanager.application.port.IJobExecutionRepository;

import io.github.valossa515.jobmanager.domain.model.Job;
import io.github.valossa515.jobmanager.domain.model.JobExecution;
import io.github.valossa515.jobmanager.domain.model.JobExecutionStatus;
import io.github.valossa515.jobmanager.domain.model.OutboxEvent;

import io.github.valossa515.jobmanager.support.IntegrationTestConfig;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Import(IntegrationTestConfig.class)
@Transactional
class JobExecutionRepositoryIntegrationTest {

    @Autowired
    private IJobRepository jobRepository;

    @Autowired
    private IOutboxRepository outboxRepository;

    @Autowired
    private IJobExecutionRepository executionRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    void shouldPersistAndRestoreExecution() {
        Job job = Job.create("NO_OP", "{}");
        job.start();
        jobRepository.save(job);

        OutboxEvent event = OutboxEvent.create(
                job.getId(),
                "job.submitted",
                "job.submitted",
                "{}"
        );

        outboxRepository.save(event);

        JobExecution execution = JobExecution.grant(
                job.getId(),
                event.getId(),
                UUID.randomUUID(),
                "worker-java",
                "instance-1",
                Instant.now(),
                Duration.ofSeconds(30)
        );

        executionRepository.save(execution);

        entityManager.flush();
        entityManager.clear();

        JobExecution restored = executionRepository
                .findByJobId(job.getId())
                .orElseThrow();

        assertAll(
                () -> assertEquals(
                        execution.getExecutionId(),
                        restored.getExecutionId()
                ),
                () -> assertEquals(
                        execution.getEventId(),
                        restored.getEventId()
                ),
                () -> assertEquals(
                        execution.getClaimId(),
                        restored.getClaimId()
                ),
                () -> assertEquals(
                        execution.getWorkerInstanceId(),
                        restored.getWorkerInstanceId()
                ),
                () -> assertEquals(
                        JobExecutionStatus.ACTIVE,
                        restored.getStatus()
                ),
                () -> assertTrue(
                        Duration.between(
                                execution.getExpiresAt(),
                                restored.getExpiresAt()
                        ).abs().compareTo(
                                Duration.ofMillis(1)
                        ) < 0
                )
        );
    }

    @Test
    void shouldReturnEmptyForUnknownJob() {
        assertTrue(
                executionRepository
                        .findByJobId(UUID.randomUUID())
                        .isEmpty()
        );
    }
}