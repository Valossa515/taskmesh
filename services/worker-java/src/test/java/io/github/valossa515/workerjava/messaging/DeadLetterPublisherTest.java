
package io.github.valossa515.workerjava.messaging;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DeadLetterPublisherTest {

    private RabbitTemplate template;
    private CachingConnectionFactory factory;
    private DeadLetterPublisher publisher;

    private final AtomicReference<Message> sent =
            new AtomicReference<>();

    @BeforeEach
    void setUp() {
        template = mock(RabbitTemplate.class);
        factory = mock(CachingConnectionFactory.class);

        when(factory.isPublisherConfirms())
                .thenReturn(true);

        when(factory.isPublisherReturns())
                .thenReturn(true);

        when(template.isMandatoryFor(any(Message.class)))
                .thenReturn(true);

        publisher = new DeadLetterPublisher(
                template,
                factory,
                DeadLetterPublisher.DEAD_QUEUE,
                30
        );
    }

    private Message originalMessage() {
        MessageProperties properties =
                new MessageProperties();

        properties.setMessageId("original-event-id");
        properties.setContentType("application/json");

        return new Message(
                new byte[] {
                        (byte) 0xC3,
                        (byte) 0x28,
                        0x00
                },
                properties
        );
    }

    private void brokerResponds(
            boolean ack,
            boolean returned
    ) {
        doAnswer(invocation -> {
            Message message = invocation.getArgument(2);
            CorrelationData correlation =
                    invocation.getArgument(3);

            sent.set(message);

            if (returned) {
                correlation.setReturned(
                        new ReturnedMessage(
                                message,
                                312,
                                "NO_ROUTE",
                                "",
                                DeadLetterPublisher.DEAD_QUEUE
                        )
                );
            }

            correlation.getFuture().complete(
                    new CorrelationData.Confirm(
                            ack,
                            ack ? null : "broker-nack"
                    )
            );

            return null;

        }).when(template).send(
                eq(""),
                eq(DeadLetterPublisher.DEAD_QUEUE),
                any(Message.class),
                any(CorrelationData.class)
        );
    }

    @Test
    void shouldPublishOriginalBytesWithMetadata() {
        brokerResponds(true, false);

        Message original = originalMessage();

        assertDoesNotThrow(
                () -> publisher.publish(
                        original,
                        "MALFORMED_JSON"
                )
        );

        Message published = sent.get();

        assertNotNull(published);

        assertArrayEquals(
                original.getBody(),
                published.getBody()
        );

        assertNotSame(
                original.getBody(),
                published.getBody()
        );

        var properties = published.getMessageProperties();

        assertEquals(
                MessageDeliveryMode.PERSISTENT,
                properties.getDeliveryMode()
        );

        assertEquals(
                "application/octet-stream",
                properties.getContentType()
        );

        assertEquals(
                "original-event-id",
                properties.getMessageId()
        );

        assertEquals(
                "MALFORMED_JSON",
                properties.getHeaders().get(
                        "x-taskmesh-quarantine-reason"
                )
        );

        assertEquals(
                "taskmesh.jobs.pending",
                properties.getHeaders().get(
                        "x-taskmesh-source-queue"
                )
        );

        assertNotNull(
                properties.getHeaders().get(
                        "x-taskmesh-quarantine-id"
                )
        );
    }

    @Test
    void shouldHandleMalformedBytesWithoutMessageId() {
        brokerResponds(true, false);

        Message original = new Message(
                new byte[] {
                        (byte) 0xFF,
                        (byte) 0xFE
                },
                new MessageProperties()
        );

        publisher.publish(
                original,
                "MISSING_MESSAGE_ID"
        );

        assertArrayEquals(
                original.getBody(),
                sent.get().getBody()
        );

        assertNotNull(
                sent.get().getMessageProperties()
                        .getMessageId()
        );
    }

    @Test
    void shouldRejectBrokerNack() {
        brokerResponds(false, false);

        var exception = assertThrows(
                QuarantinePublicationException.class,
                () -> publisher.publish(
                        originalMessage(),
                        "SCHEMA_VIOLATION"
                )
        );

        assertEquals(
                QuarantinePublicationException.Failure.NACK,
                exception.failure()
        );
    }

    @Test
    void shouldRejectReturnedMessageEvenWithBrokerAck() {
        brokerResponds(true, true);

        var exception = assertThrows(
                QuarantinePublicationException.class,
                () -> publisher.publish(
                        originalMessage(),
                        "SCHEMA_VIOLATION"
                )
        );

        assertEquals(
                QuarantinePublicationException.Failure.RETURNED,
                exception.failure()
        );
    }

    @Test
    void shouldRejectUnconfirmedPublication() {
        // The mocked broker never completes
        // the confirmation future.
        var exception = assertThrows(
                QuarantinePublicationException.class,
                () -> publisher.publish(
                        originalMessage(),
                        "SCHEMA_VIOLATION"
                )
        );

        assertEquals(
                QuarantinePublicationException.Failure.TIMEOUT,
                exception.failure()
        );
    }

    @Test
    void shouldRejectNonMandatoryPublication() {
        when(template.isMandatoryFor(any(Message.class)))
                .thenReturn(false);

        var exception = assertThrows(
                QuarantinePublicationException.class,
                () -> publisher.publish(
                        originalMessage(),
                        "SCHEMA_VIOLATION"
                )
        );

        assertEquals(
                QuarantinePublicationException.Failure.CONFIGURATION,
                exception.failure()
        );

        verify(template, never()).send(
                anyString(),
                anyString(),
                any(Message.class),
                any(CorrelationData.class)
        );
    }

    @Test
    void shouldRequirePublisherConfirmsAndReturns() {
        when(factory.isPublisherReturns())
                .thenReturn(false);

        assertThrows(
                IllegalStateException.class,
                () -> new DeadLetterPublisher(
                        template,
                        factory,
                        DeadLetterPublisher.DEAD_QUEUE,
                        1000
                )
        );
    }

    @Test
    void shouldRejectTransportFailure() {
        doThrow(new AmqpException(
                "Simulated connection failure"
        )).when(template).send(
                eq(""),
                eq(DeadLetterPublisher.DEAD_QUEUE),
                any(Message.class),
                any(CorrelationData.class)
        );

        var exception = assertThrows(
                QuarantinePublicationException.class,
                () -> publisher.publish(
                        originalMessage(),
                        "SCHEMA_VIOLATION"
                )
        );

        assertEquals(
                QuarantinePublicationException.Failure.TRANSPORT,
                exception.failure()
        );
    }
}