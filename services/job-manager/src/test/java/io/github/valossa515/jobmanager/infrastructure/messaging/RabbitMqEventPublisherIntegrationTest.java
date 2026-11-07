
package io.github.valossa515.jobmanager.infrastructure.messaging;

import io.github.valossa515.jobmanager.application.exception.EventPublicationException;
import io.github.valossa515.jobmanager.application.port.IEventPublisher;
import io.github.valossa515.jobmanager.domain.model.OutboxEvent;
import io.github.valossa515.jobmanager.support.IntegrationTestConfig;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.testcontainers.containers.RabbitMQContainer;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {
        "taskmesh.rabbitmq.initialize-on-startup=true",
        "spring.rabbitmq.publisher-confirm-type=correlated",
        "spring.rabbitmq.publisher-returns=true",
        "spring.rabbitmq.template.mandatory=true",
        "taskmesh.publisher.confirm-timeout-seconds=10"
})
@Import({
        IntegrationTestConfig.class,
        RabbitMqEventPublisherIntegrationTest.RabbitTestConfig.class
})
class RabbitMqEventPublisherIntegrationTest {

    private static final String QUEUE =
            "taskmesh.jobs.pending";

    @Autowired
    private IEventPublisher eventPublisher;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @TestConfiguration(proxyBeanMethods = false)
    static class RabbitTestConfig {

        @Bean
        @ServiceConnection
        RabbitMQContainer rabbitMQContainer() {
            return new RabbitMQContainer("rabbitmq:4-management");
        }
    }

    @Test
    void shouldPublishEventSuccessfully() {
        String payload = """
                {
                  "eventType": "job.submitted",
                  "schemaVersion": "1.0"
                }
                """;

        OutboxEvent event = OutboxEvent.create(
                UUID.randomUUID(),
                "job.submitted",
                "job.submitted",
                payload
        );

        assertDoesNotThrow(
                () -> eventPublisher.publish(event)
        );

        Message received = rabbitTemplate.receive(
                QUEUE,
                3000
        );

        assertNotNull(
                received,
                "Message should reach the pending queue"
        );

        assertEquals(
                payload,
                new String(
                        received.getBody(),
                        StandardCharsets.UTF_8
                )
        );

        assertEquals(
                event.getId().toString(),
                received.getMessageProperties().getMessageId()
        );
    }

    @Test
    void shouldRejectUnroutableEvent() {
        OutboxEvent event = OutboxEvent.create(
                UUID.randomUUID(),
                "job.submitted",
                "unroutable." + UUID.randomUUID(),
                "{}"
        );

        EventPublicationException exception =
                assertThrows(
                        EventPublicationException.class,
                        () -> eventPublisher.publish(event)
                );

        assertTrue(
                exception.getMessage().contains(
                        "could not route"
                )
        );
    }
}