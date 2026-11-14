
package io.github.valossa515.workerjava.messaging;

import com.rabbitmq.client.Channel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class JobMessageListener {

    public static final String LISTENER_ID =
            "taskmeshJobMessageListener";

    public static final String PENDING_QUEUE =
            "taskmesh.jobs.pending";

    private static final Logger logger =
            LoggerFactory.getLogger(
                    JobMessageListener.class
            );

    private final JobDeliveryProcessor processor;
    private final AtomicBoolean paused =
            new AtomicBoolean(false);

    public JobMessageListener(
            JobDeliveryProcessor processor
    ) {
        this.processor = Objects.requireNonNull(processor);
    }

    @RabbitListener(
            id = LISTENER_ID,
            queues = PENDING_QUEUE,
            autoStartup = "false",
            ackMode = "MANUAL",
            concurrency = "1"
    )
    public void onMessage(
            Message message,
            Channel channel
    ) {
        Objects.requireNonNull(message);
        Objects.requireNonNull(channel);

        // Never settle deliveries after intake has paused.
        if (paused.get()) {
            return;
        }

        long deliveryTag =
                message.getMessageProperties()
                        .getDeliveryTag();

        try {
            if (deliveryTag <= 0) {
                throw new IllegalStateException(
                        "Invalid AMQP delivery tag"
                );
            }

            // A normal return means that the job has a
            // confirmed terminal outcome or that its
            // quarantine publication was confirmed.
            processor.process(message);

            // Acknowledge exactly this delivery.
            channel.basicAck(deliveryTag, false);

        } catch (IOException | RuntimeException exception) {
            paused.set(true);

            logger.error(
                    "Worker intake paused. Delivery {} "
                            + "was not acknowledged. Cause: {}",
                    deliveryTag,
                    exception.getClass().getSimpleName(),
                    exception
            );

            // Intentionally no basicAck, basicNack or
            // basicReject. With prefetch=1 this consumer
            // cannot receive further deliveries while
            // the original remains unacknowledged.
        }
    }

    public boolean isPaused() {
        return paused.get();
    }
}