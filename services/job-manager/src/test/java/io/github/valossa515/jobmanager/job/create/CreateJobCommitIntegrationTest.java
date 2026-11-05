package io.github.valossa515.jobmanager.job.create;

import io.github.valossa515.jobmanager.domain.model.Job;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest
public class CreateJobCommitIntegrationTest {

    @Autowired
    private CreateJobHandler handler;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private JsonMapper jsonMapper;

    @Test
    void shouldPersistJobAndOutboxEventTogether() {
        String jobType = "COMMIT_" + UUID.randomUUID();

        Job job = handler.handle(
                new CreateJobCommand(jobType, "{}")
        );

        Integer jobCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM jobs WHERE id = ?",
                Integer.class,
                job.getId()
        );

        assertEquals(1, jobCount);

        String eventPayload = jdbcTemplate.queryForObject(
                """
                SELECT payload::text
                FROM outbox_events
                WHERE aggregate_id = ?
                """,
                String.class,
                job.getId()
        );

        assertNotNull(eventPayload);

        JsonNode event = jsonMapper.readTree(eventPayload);

        assertEquals(
                job.getId().toString(),
                event.get("data").get("jobId").asString()
        );

        UUID eventId = UUID.fromString(
                event.get("eventId").asString()
        );

        Integer outboxCount = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM outbox_events
                WHERE id = ?
                  AND aggregate_id = ?
                  AND status = 'PENDING'
                  AND attempts = 0
                  AND published_at IS NULL
                """,
                Integer.class,
                eventId,
                job.getId()
        );

        assertEquals(1, outboxCount);
    }
}
