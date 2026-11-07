
package io.github.valossa515.jobmanager.infrastructure.messaging;

import io.github.valossa515.jobmanager.application.exception.EventPublicationException;
import io.github.valossa515.jobmanager.domain.model.OutboxEvent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.net.ConnectException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RabbitMqEventPublisherTest {

    @Mock
    private RabbitTemplate rabbitTemplate;

    private RabbitMqEventPublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new RabbitMqEventPublisher(
                rabbitTemplate,
                1
        );
    }

    private OutboxEvent newEvent() {
        return OutboxEvent.create(
                UUID.randomUUID(),
                "job.submitted",
                "job.submitted",
                """
                {"eventType":"job.submitted"}
                """
        );
    }

    @Test
    void shouldThrowWhenBrokerReturnsNack() {
        doAnswer(invocation -> {
            CorrelationData correlation =
                    invocation.getArgument(3);

            correlation.getFuture().complete(
                    new CorrelationData.Confirm(
                            false,
                            "Simulated broker NACK"
                    )
            );

            return null;
        }).when(rabbitTemplate).send(
                anyString(),
                anyString(),
                any(Message.class),
                any(CorrelationData.class)
        );

        EventPublicationException exception =
                assertThrows(
                        EventPublicationException.class,
                        () -> publisher.publish(newEvent())
                );

        assertTrue(
                exception.getMessage().contains(
                        "RabbitMQ rejected event"
                )
        );

        assertTrue(
                exception.getMessage().contains(
                        "Simulated broker NACK"
                )
        );
    }

    @Test
    void shouldThrowWhenConfirmationTimesOut() {
        // The RabbitTemplate mock does not complete
        // the CorrelationData future.
        EventPublicationException exception =
                assertThrows(
                        EventPublicationException.class,
                        () -> publisher.publish(newEvent())
                );

        assertTrue(
                exception.getMessage().contains(
                        "RabbitMQ confirm timed out"
                )
        );

        assertInstanceOf(
                java.util.concurrent.TimeoutException.class,
                exception.getCause()
        );
    }

    @Test
    void shouldThrowWhenBrokerConnectionFails() {
        doThrow(
                new AmqpConnectException(
                        new ConnectException(
                                "Broker unavailable"
                        )
                )
        ).when(rabbitTemplate).send(
                anyString(),
                anyString(),
                any(Message.class),
                any(CorrelationData.class)
        );

        EventPublicationException exception =
                assertThrows(
                        EventPublicationException.class,
                        () -> publisher.publish(newEvent())
                );

        assertTrue(
                exception.getMessage().contains(
                        "RabbitMQ send failed"
                )
        );

        assertInstanceOf(
                AmqpConnectException.class,
                exception.getCause()
        );
    }

    @Test
    void shouldPreserveThreadInterruption() {
        // The mock leaves the confirmation pending.
        Thread.currentThread().interrupt();

        try {
            EventPublicationException exception =
                    assertThrows(
                            EventPublicationException.class,
                            () -> publisher.publish(newEvent())
                    );

            assertTrue(
                    exception.getMessage().contains(
                            "Publication interrupted"
                    )
            );

            assertInstanceOf(
                    InterruptedException.class,
                    exception.getCause()
            );

            assertTrue(
                    Thread.currentThread().isInterrupted(),
                    "Interrupt flag must be preserved"
            );

        } finally {
            // Prevent interruption from affecting
            // subsequent JUnit tests.
            Thread.interrupted();
        }
    }
}