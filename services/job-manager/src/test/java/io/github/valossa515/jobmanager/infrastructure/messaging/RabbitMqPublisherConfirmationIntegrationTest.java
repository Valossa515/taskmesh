
package io.github.valossa515.jobmanager.infrastructure.messaging;

import io.github.valossa515.jobmanager.support.IntegrationTestConfig;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.RabbitMQContainer;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {
        "taskmesh.rabbitmq.initialize-on-startup=true",
        "spring.rabbitmq.publisher-confirm-type=correlated",
        "spring.rabbitmq.publisher-returns=true",
        "spring.rabbitmq.template.mandatory=true"
})
@Import({
        IntegrationTestConfig.class,
        RabbitMqPublisherConfirmationIntegrationTest.RabbitTestConfig.class
})
class RabbitMqPublisherConfirmationIntegrationTest {

    private static final String EXCHANGE = "taskmesh.jobs";
    private static final String ROUTING_KEY = "job.submitted";

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
    void shouldConfirmRoutedMessage() throws Exception {
        CorrelationData correlation = new CorrelationData(
                UUID.randomUUID().toString()
        );

        rabbitTemplate.convertAndSend(
                EXCHANGE,
                ROUTING_KEY,
                """
                {"eventType":"job.submitted"}
                """,
                correlation
        );

        CorrelationData.Confirm confirm = correlation
                .getFuture()
                .get(10, TimeUnit.SECONDS);

        assertTrue(
                confirm.isAck(),
                "RabbitMQ should acknowledge the publication"
        );

        assertNull(
                correlation.getReturned(),
                "Message should be routed to the pending queue"
        );
    }

    @Test
    void shouldDetectUnroutableMessage() throws Exception {
        CorrelationData correlation = new CorrelationData(
                UUID.randomUUID().toString()
        );

        String invalidRoutingKey =
                "unroutable." + UUID.randomUUID();

        rabbitTemplate.convertAndSend(
                EXCHANGE,
                invalidRoutingKey,
                """
                {"eventType":"job.submitted"}
                """,
                correlation
        );

        CorrelationData.Confirm confirm = correlation
                .getFuture()
                .get(10, TimeUnit.SECONDS);

        assertTrue(
                confirm.isAck(),
                "The broker may ACK an unroutable message"
        );

        assertNotNull(
                correlation.getReturned(),
                "Unroutable message must be returned"
        );

        assertEquals(
                invalidRoutingKey,
                correlation.getReturned().getRoutingKey()
        );
    }
}