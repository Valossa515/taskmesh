
package io.github.valossa515.jobmanager.application.service;

import io.github.valossa515.jobmanager.application.port.IJobRepository;
import io.github.valossa515.jobmanager.application.port.IOutboxRepository;
import io.github.valossa515.jobmanager.domain.model.Job;
import io.github.valossa515.jobmanager.domain.model.OutboxEvent;
import io.github.valossa515.jobmanager.domain.model.OutboxStatus;
import io.github.valossa515.jobmanager.support.IntegrationTestConfig;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import org.testcontainers.containers.RabbitMQContainer;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {
        "taskmesh.rabbitmq.initialize-on-startup=true",
        "spring.rabbitmq.publisher-confirm-type=correlated",
        "spring.rabbitmq.publisher-returns=true",
        "spring.rabbitmq.template.mandatory=true",
        "taskmesh.publisher.id=integration-publisher",
        "taskmesh.publisher.batch-size=1",
        "taskmesh.publisher.lease-seconds=30",
        "taskmesh.publisher.confirm-timeout-seconds=10"
})
@Import({
        IntegrationTestConfig.class,
        OutboxPublishingServiceIntegrationTest.RabbitTestConfig.class
})
class OutboxPublishingServiceIntegrationTest {

    private static final String QUEUE =
            "taskmesh.jobs.pending";

    @Autowired
    private IJobRepository jobRepository;

    @Autowired
    private IOutboxRepository outboxRepository;

    @Autowired
    private OutboxPublishingService publishingService;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private AmqpAdmin rabbitAdmin;

    @TestConfiguration(proxyBeanMethods = false)
    static class RabbitTestConfig {

        @Bean
        @ServiceConnection
        RabbitMQContainer rabbitMQContainer() {
            return new RabbitMQContainer(
                    "rabbitmq:4-management"
            );
        }
    }

    @BeforeEach
    void cleanQueue() {
        rabbitAdmin.purgeQueue(QUEUE, false);
    }

    @Test
    void shouldPublishOutboxEventAndMarkItAsPublished() {
        // Arrange: create a persisted job.
        Job job = jobRepository.save(
                Job.create("NO_OP", "{}")
        );


        String payload = """
        {
          "eventId": "%s",
          "eventType": "job.submitted",
          "schemaVersion": "1.0",
          "occurredAt": "2026-11-07T12:00:00Z",
          "data": {
            "jobId": "%s",
            "jobType": "NO_OP",
            "payload": {}
          }
        }
        """.formatted(
                UUID.randomUUID(),
                job.getId()
        );


        // An old timestamp makes this event
        // the earliest available in this test.
        OutboxEvent event = OutboxEvent.create(
                UUID.randomUUID(),
                job.getId(),
                "job.submitted",
                "job.submitted",
                payload,
                Instant.parse("1900-01-01T00:00:00Z")
        );

        outboxRepository.save(event);

        OutboxEvent before = outboxRepository
                .findById(event.getId())
                .orElseThrow();

        assertEquals(
                OutboxStatus.PENDING,
                before.getStatus()
        );

        assertNull(before.getPublishedAt());

        // Act: reserve, publish and confirm.
        int published = publishingService.publishBatch();

        // Assert: publishing service confirmed the event.
        assertEquals(1, published);

        // Verify the message reached RabbitMQ.
        Message received = rabbitTemplate.receive(
                QUEUE,
                3000
        );

        assertNotNull(
                received,
                "The message must reach the RabbitMQ queue"
        );

        JsonMapper jsonMapper = new JsonMapper();

        var expectedJson = jsonMapper.readTree(payload);

        var actualJson = jsonMapper.readTree(
                new String(
                        received.getBody(),
                        StandardCharsets.UTF_8
                )
        );

        assertEquals(
                expectedJson,
                actualJson,
                "Published JSON must preserve its structure and values"
        );

        assertEquals(
                event.getId().toString(),
                received.getMessageProperties().getMessageId()
        );

        assertEquals(
                MessageProperties.CONTENT_TYPE_JSON,
                received.getMessageProperties().getContentType()
        );

        // Verify the persisted outbox state.
        OutboxEvent restored = outboxRepository
                .findById(event.getId())
                .orElseThrow();

        assertEquals(
                OutboxStatus.PUBLISHED,
                restored.getStatus()
        );

        assertNotNull(restored.getPublishedAt());

        assertNull(restored.getLockedBy());
        assertNull(restored.getLockedUntil());
        assertNull(restored.getLeaseToken());
    }
}
