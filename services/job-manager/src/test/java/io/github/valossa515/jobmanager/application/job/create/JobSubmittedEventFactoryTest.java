package io.github.valossa515.jobmanager.application.job.create;
import io.github.valossa515.jobmanager.domain.model.Job;
import io.github.valossa515.jobmanager.domain.model.OutboxEvent;
import io.github.valossa515.jobmanager.domain.model.OutboxStatus;
import io.github.valossa515.jobmanager.job.create.JobSubmittedEventFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

public class JobSubmittedEventFactoryTest {
    private JsonMapper jsonMapper;
    private JobSubmittedEventFactory factory;

    @BeforeEach
    void setUp() {
        jsonMapper = JsonMapper.builder().build();
        factory = new JobSubmittedEventFactory(jsonMapper);
    }

    @Test
    void shouldCreateJobSubmittedEvent() {
        Job job = Job.create(
                "NO_OP",
                """
                {
                  "message": "TaskMesh test"
                }
                """
        );

        OutboxEvent event = factory.create(job);

        JsonNode json = jsonMapper.readTree(
                event.getPayload()
        );

        assertEquals(
                event.getId().toString(),
                json.get("eventId").asString()
        );

        assertEquals(
                "job.submitted",
                json.get("eventType").asString()
        );

        assertEquals(
                "1.0",
                json.get("schemaVersion").asString()
        );

        assertEquals(
                event.getCreatedAt(),
                Instant.parse(json.get("occurredAt").asString())
        );

        assertEquals(
                job.getId().toString(),
                json.get("data").get("jobId").asString()
        );

        assertEquals(
                "NO_OP",
                json.get("data").get("jobType").asString()
        );

        assertEquals(
                "TaskMesh test",
                json.get("data")
                        .get("payload")
                        .get("message")
                        .asString()
        );
    }

    @Test
    void shouldCreatePendingOutboxEvent() {
        Job job = Job.create("NO_OP", "{}");

        OutboxEvent event = factory.create(job);

        assertEquals(job.getId(), event.getAggregateId());
        assertEquals("job.submitted", event.getEventType());
        assertEquals("job.submitted", event.getRoutingKey());
        assertEquals(OutboxStatus.PENDING, event.getStatus());
        assertEquals(0, event.getAttempts());
        assertNull(event.getPublishedAt());
    }

    @Test
    void shouldRejectNonObjectPayload() {
        Job job = Job.create("NO_OP", "[]");

        assertThrows(
                IllegalArgumentException.class,
                () -> factory.create(job)
        );
    }

    @Test
    void shouldGenerateDifferentEventIds() {
        Job job = Job.create("NO_OP", "{}");

        OutboxEvent first = factory.create(job);
        OutboxEvent second = factory.create(job);

        assertNotEquals(first.getId(), second.getId());
        assertEquals(first.getAggregateId(), second.getAggregateId());
    }
}