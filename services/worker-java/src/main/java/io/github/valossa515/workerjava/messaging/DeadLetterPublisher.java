
package io.github.valossa515.workerjava.messaging;

import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static io.github.valossa515.workerjava.messaging
        .QuarantinePublicationException.Failure;

public class DeadLetterPublisher {

    public static final String DEAD_QUEUE =
            "taskmesh.jobs.dead";

    private static final String SOURCE_QUEUE =
            "taskmesh.jobs.pending";

    private final RabbitTemplate rabbitTemplate;
    private final String destinationQueue;
    private final long confirmTimeoutMillis;

    public DeadLetterPublisher(
            RabbitTemplate rabbitTemplate,
            CachingConnectionFactory connectionFactory,
            String destinationQueue,
            long confirmTimeoutMillis
    ) {
        this.rabbitTemplate = Objects.requireNonNull(
                rabbitTemplate
        );

        Objects.requireNonNull(connectionFactory);

        if (!connectionFactory.isPublisherConfirms()
                || !connectionFactory.isPublisherReturns()) {
            throw new IllegalStateException(
                    "Publisher confirms and returns "
                            + "must both be enabled"
            );
        }

        if (destinationQueue == null
                || destinationQueue.isBlank()) {
            throw new IllegalArgumentException(
                    "Destination queue is required"
            );
        }

        if (confirmTimeoutMillis <= 0) {
            throw new IllegalArgumentException(
                    "Confirm timeout must be positive"
            );
        }

        this.destinationQueue = destinationQueue;
        this.confirmTimeoutMillis = confirmTimeoutMillis;
    }

    public void publish(
            Message original,
            String reason
    ) {
        Objects.requireNonNull(
                original,
                "Original message is required"
        );

        if (reason == null
                || !reason.matches("[A-Z0-9_]{1,64}")) {
            throw new IllegalArgumentException(
                    "Invalid quarantine reason"
            );
        }

        Message deadLetter = buildMessage(
                original,
                reason
        );

        if (!Boolean.TRUE.equals(
                rabbitTemplate.isMandatoryFor(deadLetter)
        )) {
            throw new QuarantinePublicationException(
                    Failure.CONFIGURATION,
                    "Mandatory publication is not enabled"
            );
        }

        CorrelationData correlation =
                new CorrelationData(
                        UUID.randomUUID().toString()
                );

        try {
            rabbitTemplate.send(
                    "",
                    destinationQueue,
                    deadLetter,
                    correlation
            );

            CorrelationData.Confirm confirm =
                    correlation.getFuture().get(
                            confirmTimeoutMillis,
                            TimeUnit.MILLISECONDS
                    );

            if (confirm == null || !confirm.ack()) {
                throw new QuarantinePublicationException(
                        Failure.NACK,
                        "RabbitMQ did not confirm quarantine publication"
                );
            }

            if (correlation.getReturned() != null) {
                throw new QuarantinePublicationException(
                        Failure.RETURNED,
                        "Quarantine publication was not routed"
                );
            }

        } catch (TimeoutException exception) {
            throw new QuarantinePublicationException(
                    Failure.TIMEOUT,
                    "Quarantine publication confirmation timed out",
                    exception
            );

        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();

            throw new QuarantinePublicationException(
                    Failure.INTERRUPTED,
                    "Quarantine publication was interrupted",
                    exception
            );

        } catch (ExecutionException exception) {
            throw new QuarantinePublicationException(
                    Failure.TRANSPORT,
                    "Quarantine publication confirmation failed",
                    exception
            );

        } catch (AmqpException exception) {
            throw new QuarantinePublicationException(
                    Failure.TRANSPORT,
                    "RabbitMQ quarantine publication failed",
                    exception
            );
        }
    }

    private Message buildMessage(
            Message original,
            String reason
    ) {
        MessageProperties source =
                original.getMessageProperties();

        MessageProperties properties =
                new MessageProperties();

        properties.setDeliveryMode(
                MessageDeliveryMode.PERSISTENT
        );

        // Keep the original bytes unchanged, even
        // when the source contains malformed JSON.
        properties.setContentType(
                "application/octet-stream"
        );

        String originalId = source.getMessageId();

        if (originalId != null && !originalId.isBlank()) {
            properties.setMessageId(originalId);
            properties.setHeader(
                    "x-taskmesh-original-message-id",
                    originalId
            );
        } else {
            properties.setMessageId(
                    UUID.randomUUID().toString()
            );
        }

        properties.setHeader(
                "x-taskmesh-quarantine-id",
                UUID.randomUUID().toString()
        );

        properties.setHeader(
                "x-taskmesh-quarantine-reason",
                reason
        );

        properties.setHeader(
                "x-taskmesh-source-queue",
                SOURCE_QUEUE
        );

        String routingKey =
                source.getReceivedRoutingKey();

        if (routingKey != null
                && !routingKey.isBlank()) {
            properties.setHeader(
                    "x-taskmesh-original-routing-key",
                    routingKey
            );
        }

        String originalContentType =
                source.getContentType();

        if (originalContentType != null
                && !originalContentType.isBlank()) {
            properties.setHeader(
                    "x-taskmesh-original-content-type",
                    originalContentType
            );
        }

        return new Message(
                original.getBody().clone(),
                properties
        );
    }
}