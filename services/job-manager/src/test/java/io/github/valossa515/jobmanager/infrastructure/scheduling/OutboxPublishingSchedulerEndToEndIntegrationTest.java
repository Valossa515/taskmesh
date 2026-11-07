
package io.github.valossa515.jobmanager.infrastructure.scheduling;

import io.github.valossa515.jobmanager.application.port.IJobRepository;
import io.github.valossa515.jobmanager.application.port.IOutboxRepository;
import io.github.valossa515.jobmanager.domain.model.Job;
import io.github.valossa515.jobmanager.domain.model.OutboxEvent;
import io.github.valossa515.jobmanager.domain.model.OutboxStatus;
import io.github.valossa515.jobmanager.support.IntegrationTestConfig;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;

import org.testcontainers.containers.RabbitMQContainer;

import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {
        "taskmesh.rabbitmq.initialize-on-startup=true",

        "spring.rabbitmq.publisher-confirm-type=correlated",
        "spring.rabbitmq.publisher-returns=true",
        "spring.rabbitmq.template.mandatory=true",

        "taskmesh.publisher.id=scheduler-e2e-publisher",
        "taskmesh.publisher.batch-size=1",
        "taskmesh.publisher.lease-seconds=30",
        "taskmesh.publisher.confirm-timeout-seconds=10",

        "taskmesh.publisher.scheduling.enabled=true",
        "taskmesh.publisher.scheduling.fixed-delay-ms=200",
        "taskmesh.publisher.scheduling.initial-delay-ms=1500"
})
@Import({
        IntegrationTestConfig.class,
        OutboxPublishingSchedulerEndToEndIntegrationTest.RabbitTestConfig.class
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OutboxPublishingSchedulerEndToEndIntegrationTest {

    private static final String QUEUE =
            "taskmesh.jobs.pending";

    private static final Duration TIMEOUT =
            Duration.ofSeconds(15);

    @Autowired
    private IJobRepository jobRepository;

    @Autowired
    private IOutboxRepository outboxRepository;

    @Autowired
    private RabbitTemplate rabbitTemplate;

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

    @Test
    @Timeout(30)
    void shouldAutomaticallyPublishPendingOutboxEvent()
            throws Exception {

        // Arrange: persist a job.
        Job job = jobRepository.save(
                Job.create("NO_OP", "{}")
        );

        UUID eventId = UUID.randomUUID();

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
                eventId,
                job.getId()
        );

        OutboxEvent event = OutboxEvent.create(
                eventId,
                job.getId(),
                "job.submitted",
                "job.submitted",
                payload,
                Instant.now()
        );

        outboxRepository.save(event);

        OutboxEvent initial = outboxRepository
                .findById(eventId)
                .orElseThrow();

        assertEquals(
                OutboxStatus.PENDING,
                initial.getStatus()
        );

        // Act: do NOT call publishBatch().
        // Wait for Spring's scheduler to publish the event.
        Message received = rabbitTemplate.receive(
                QUEUE,
                10000
        );

        assertNotNull(
                received,
                "Scheduler should automatically publish the event"
        );

        // Assert: verify the delivered message.
        assertEquals(
                eventId.toString(),
                received.getMessageProperties().getMessageId()
        );

        assertEquals(
                MessageProperties.CONTENT_TYPE_JSON,
                received.getMessageProperties().getContentType()
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
                "Published event must preserve JSON content"
        );

        // The RabbitMQ delivery can occur just before
        // the PostgreSQL confirmation is committed.
        OutboxEvent published = awaitPublished(eventId);

        assertEquals(
                OutboxStatus.PUBLISHED,
                published.getStatus()
        );

        assertNotNull(published.getPublishedAt());

        assertNull(published.getLockedBy());
        assertNull(published.getLockedUntil());
        assertNull(published.getLeaseToken());
    }

    private OutboxEvent awaitPublished(UUID eventId)
            throws InterruptedException {

        long deadline = System.nanoTime()
                + TIMEOUT.toNanos();

        while (System.nanoTime() < deadline) {

            OutboxEvent event = outboxRepository
                    .findById(eventId)
                    .orElseThrow();

            if (event.getStatus() == OutboxStatus.PUBLISHED) {
                return event;
            }

            Thread.sleep(100);
        }

        fail(
                "Outbox event did not reach PUBLISHED "
                        + "within the expected timeout"
        );

        throw new IllegalStateException(
                "Unreachable"
        );
    }
}