
package io.github.valossa515.jobmanager.infrastructure.persistence;

import io.github.valossa515.jobmanager.application.port.IJobRepository;
import io.github.valossa515.jobmanager.domain.model.Job;
import io.github.valossa515.jobmanager.domain.model.JobStatus;

import io.github.valossa515.jobmanager.support.IntegrationTestConfig;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Import(IntegrationTestConfig.class)
@Transactional
class JobRepositoryIntegrationTest {

    private static final JsonMapper JSON_MAPPER =
            JsonMapper.builder().build();

    @Autowired
    private IJobRepository jobRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManager entityManager;

    @Test
    void shouldSaveAndRetrieveJob() {
        Job job = Job.create(
                "REPORT_GENERATION",
                "{\"reportId\":\"report-123\"}"
        );

        Job saved = jobRepository.save(job);

        entityManager.flush();

        Job restored = jobRepository.findById(saved.getId())
                .orElseThrow();

        // Verify domain data
        assertAll(
                () -> assertEquals(job.getId(), restored.getId()),
                () -> assertEquals(job.getType(), restored.getType()),
                () -> assertEquals(
                        JSON_MAPPER.readTree(job.getPayload()),
                        JSON_MAPPER.readTree(restored.getPayload())
                ),
                () -> assertEquals(JobStatus.PENDING, restored.getStatus()),
                () -> assertTrue(
                        Duration.between(
                                job.getCreatedAt(),
                                restored.getCreatedAt()
                        ).abs().compareTo(Duration.ofNanos(1000)) <= 0
                ),
                () -> assertNull(restored.getStartedAt()),
                () -> assertNull(restored.getCompletedAt()),
                () -> assertNull(restored.getFailureReason())
        );

        // Verify PostgreSQL JSONB storage
        String payloadType = jdbcTemplate.queryForObject(
                "SELECT jsonb_typeof(payload) FROM jobs WHERE id = ?",
                String.class,
                saved.getId()
        );

        assertEquals("object", payloadType);
    }

    @Test
    void shouldReturnEmptyWhenJobDoesNotExist() {
        UUID nonexistentId = UUID.randomUUID();

        assertTrue(
                jobRepository.findById(nonexistentId).isEmpty()
        );
    }
}