package io.github.valossa515.jobmanager.application.job.create;

import io.github.valossa515.jobmanager.domain.model.Job;
import io.github.valossa515.jobmanager.domain.model.OutboxEvent;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Component
public class JobSubmittedEventFactory {

    private static final String EVENT_TYPE = "job.submitted";
    private static final String SCHEMA_VERSION = "1.0";

    private final JsonMapper jsonMapper;

    public JobSubmittedEventFactory(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    public OutboxEvent create(Job job) {
        Objects.requireNonNull(job, "job must not be null");

        UUID eventId = UUID.randomUUID();
        Instant occurredAt = Instant.now();

        JsonNode jobPayload = jsonMapper.readTree(
                job.getPayload()
        );

        if (!jobPayload.isObject()) {
            throw new IllegalArgumentException(
                    "Job payload must be a JSON object"
            );
        }

        Map<String, Object> data = Map.of(
                "jobId", job.getId().toString(),
                "jobType", job.getType(),
                "payload", jobPayload
        );

        Map<String, Object> envelope = Map.of(
                "eventId", eventId.toString(),
                "eventType", EVENT_TYPE,
                "schemaVersion", SCHEMA_VERSION,
                "occurredAt", occurredAt.toString(),
                "data", data
        );

        String payload = jsonMapper.writeValueAsString(
                envelope
        );

        return OutboxEvent.create(
                eventId,
                job.getId(),
                EVENT_TYPE,
                EVENT_TYPE,
                payload,
                occurredAt
        );
    }
}