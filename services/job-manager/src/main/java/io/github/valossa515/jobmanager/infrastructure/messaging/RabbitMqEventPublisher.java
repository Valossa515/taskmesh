
package io.github.valossa515.jobmanager.infrastructure.messaging;

import io.github.valossa515.jobmanager.application.exception.EventPublicationException;
import io.github.valossa515.jobmanager.application.port.IEventPublisher;
import io.github.valossa515.jobmanager.domain.model.OutboxEvent;

import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
public class RabbitMqEventPublisher implements IEventPublisher {

    private static final String EXCHANGE = "taskmesh.jobs";

    private final RabbitTemplate rabbitTemplate;
    private final long confirmTimeoutSeconds;

    public RabbitMqEventPublisher(
            RabbitTemplate rabbitTemplate,
            @Value("${taskmesh.publisher.confirm-timeout-seconds:10}")
            long confirmTimeoutSeconds
    ) {
        if (confirmTimeoutSeconds <= 0) {
            throw new IllegalArgumentException(
                    "Confirm timeout must be positive"
            );
        }

        this.rabbitTemplate = rabbitTemplate;
        this.confirmTimeoutSeconds = confirmTimeoutSeconds;
    }

    @Override
    public void publish(OutboxEvent event) {
        Objects.requireNonNull(event, "event is required");

        CorrelationData correlation = new CorrelationData(
                UUID.randomUUID().toString()
        );

        MessageProperties properties = new MessageProperties();
        properties.setContentType(
                MessageProperties.CONTENT_TYPE_JSON
        );
        properties.setContentEncoding("UTF-8");
        properties.setMessageId(event.getId().toString());

        Message message = new Message(
                event.getPayload().getBytes(StandardCharsets.UTF_8),
                properties
        );

        try {
            rabbitTemplate.send(
                    EXCHANGE,
                    event.getRoutingKey(),
                    message,
                    correlation
            );

            CorrelationData.Confirm confirm = correlation
                    .getFuture()
                    .get(
                            confirmTimeoutSeconds,
                            TimeUnit.SECONDS
                    );

            if (!confirm.isAck()) {
                throw new EventPublicationException(
                        "RabbitMQ rejected event "
                                + event.getId()
                                + ": " + confirm.getReason()
                );
            }

            if (correlation.getReturned() != null) {
                throw new EventPublicationException(
                        "RabbitMQ could not route event "
                                + event.getId()
                                + ": "
                                + correlation.getReturned()
                                .getReplyText()
                );
            }

        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();

            throw new EventPublicationException(
                    "Publication interrupted for event "
                            + event.getId(),
                    exception
            );

        } catch (TimeoutException exception) {
            throw new EventPublicationException(
                    "RabbitMQ confirm timed out for event "
                            + event.getId(),
                    exception
            );

        } catch (ExecutionException exception) {
            throw new EventPublicationException(
                    "RabbitMQ confirm failed for event "
                            + event.getId(),
                    exception
            );
        } catch (org.springframework.amqp.AmqpException exception) {
            throw new EventPublicationException(
                    "RabbitMQ send failed for event "
                            + event.getId(),
                    exception
            );
        }
    }
}