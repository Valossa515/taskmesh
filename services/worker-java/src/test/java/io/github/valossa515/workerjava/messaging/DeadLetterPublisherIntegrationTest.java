
package io.github.valossa515.workerjava.messaging;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import org.testcontainers.containers.RabbitMQContainer;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {
        "taskmesh.worker.registration-on-startup=false",
        "spring.rabbitmq.publisher-confirm-type=correlated",
        "spring.rabbitmq.publisher-returns=true",
        "spring.rabbitmq.template.mandatory=true"
})
@Import(
        DeadLetterPublisherIntegrationTest.BrokerConfig.class
)
class DeadLetterPublisherIntegrationTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class BrokerConfig {

        @Bean
        @ServiceConnection
        RabbitMQContainer rabbitMQContainer() {
            return new RabbitMQContainer(
                    "rabbitmq:4-management"
            );
        }
    }

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private CachingConnectionFactory factory;

    @Autowired
    private DeadLetterPublisher publisher;

    @BeforeEach
    void declareQueue() {
        RabbitAdmin admin = new RabbitAdmin(factory);

        admin.declareQueue(
                new Queue(
                        DeadLetterPublisher.DEAD_QUEUE,
                        true,
                        false,
                        false
                )
        );

        // Isolate test observations from previous runs.
        rabbitTemplate.execute(channel -> {
            channel.queuePurge(
                    DeadLetterPublisher.DEAD_QUEUE
            );
            return null;
        });
    }

    private Message originalMessage() {
        MessageProperties properties =
                new MessageProperties();

        properties.setMessageId(
                UUID.randomUUID().toString()
        );

        return new Message(
                new byte[] {
                        (byte) 0xFF,
                        (byte) 0xFE,
                        0x00,
                        0x01
                },
                properties
        );
    }

    @Test
    void shouldDeliverOriginalBytesToDeadLetterQueue() {
        Message original = originalMessage();

        publisher.publish(
                original,
                "MALFORMED_JSON"
        );

        Message received = rabbitTemplate.receive(
                DeadLetterPublisher.DEAD_QUEUE,
                3000
        );

        assertNotNull(
                received,
                "Confirmed quarantine message must be routed"
        );

        assertArrayEquals(
                original.getBody(),
                received.getBody()
        );

        assertEquals(
                original.getMessageProperties().getMessageId(),
                received.getMessageProperties().getMessageId()
        );

        assertEquals(
                "MALFORMED_JSON",
                received.getMessageProperties()
                        .getHeaders()
                        .get("x-taskmesh-quarantine-reason")
        );
    }

    @Test
    void shouldDetectUnroutablePublication() {
        String missingQueue =
                "taskmesh.missing." + UUID.randomUUID();

        DeadLetterPublisher invalidPublisher =
                new DeadLetterPublisher(
                        rabbitTemplate,
                        factory,
                        missingQueue,
                        5000
                );

        var exception = assertThrows(
                QuarantinePublicationException.class,
                () -> invalidPublisher.publish(
                        originalMessage(),
                        "SCHEMA_VIOLATION"
                )
        );

        assertEquals(
                QuarantinePublicationException.Failure.RETURNED,
                exception.failure()
        );
    }
}