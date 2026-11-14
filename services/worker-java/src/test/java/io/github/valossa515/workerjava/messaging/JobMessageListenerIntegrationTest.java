
package io.github.valossa515.workerjava.messaging;

import org.junit.jupiter.api.Test;

import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import org.testcontainers.containers.RabbitMQContainer;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import static io.github.valossa515.workerjava.messaging
        .JobProcessingDeferredException.Phase;
import static io.github.valossa515.workerjava.messaging
        .JobProcessingDeferredException.Recovery;

@SpringBootTest(properties = {
        "taskmesh.worker.consumer.enabled=false",
        "taskmesh.worker.registration-on-startup=false",
        "spring.rabbitmq.listener.simple.auto-startup=false"
})
@Import(JobMessageListenerIntegrationTest.BrokerConfig.class)
class JobMessageListenerIntegrationTest {

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
    private RabbitListenerEndpointRegistry registry;

    @Autowired
    private JobMessageListener listener;

    @MockitoBean
    private JobDeliveryProcessor processor;

    private Message message(String messageId) {
        MessageProperties properties =
                new MessageProperties();

        properties.setMessageId(messageId);

        return new Message(
                messageId.getBytes(StandardCharsets.UTF_8),
                properties
        );
    }

    private void awaitPause() throws InterruptedException {
        long deadline = System.nanoTime()
                + TimeUnit.SECONDS.toNanos(8);

        while (!listener.isPaused()
                && System.nanoTime() < deadline) {
            Thread.sleep(25);
        }

        assertTrue(
                listener.isPaused(),
                "Listener should be paused"
        );
    }

    @Test
    void shouldAckSuccessfulMessagesAndRedeliverUnackedFailure()
            throws Exception {

        String queue = JobMessageListener.PENDING_QUEUE;

        RabbitAdmin admin = new RabbitAdmin(factory);

        admin.declareQueue(
                new Queue(queue, true, false, false)
        );

        rabbitTemplate.execute(channel -> {
            channel.queuePurge(queue);
            return null;
        });

        doAnswer(invocation -> {
            Message received = invocation.getArgument(0);

            if ("failed".equals(
                    received.getMessageProperties().getMessageId()
            )) {
                throw new JobProcessingDeferredException(
                        Recovery.PAUSE_INTAKE,
                        Phase.COMPLETE,
                        null
                );
            }

            return null;

        }).when(processor).process(any(Message.class));

        var container = registry.getListenerContainer(
                JobMessageListener.LISTENER_ID
        );

        assertNotNull(container);

        try {
            container.start();

            // Two deliveries must be ACKed, otherwise
            // prefetch=1 prevents the second from arriving.
            rabbitTemplate.send(
                    "",
                    queue,
                    message("success-1")
            );

            rabbitTemplate.send(
                    "",
                    queue,
                    message("success-2")
            );

            verify(
                    processor,
                    timeout(8_000).times(2)
            ).process(any(Message.class));

            // This delivery fails and must remain unacked.
            rabbitTemplate.send(
                    "",
                    queue,
                    message("failed")
            );

            verify(
                    processor,
                    timeout(8_000).times(3)
            ).process(any(Message.class));

            awaitPause();

            // The failed delivery is still held by the
            // running consumer, not available for polling.
            assertNull(
                    rabbitTemplate.receive(queue, 300)
            );

            // Closing the consumer channel makes RabbitMQ
            // requeue its outstanding delivery.
            container.stop();

            Message recovered =
                    rabbitTemplate.receive(queue, 3000);

            assertNotNull(
                    recovered,
                    "Unacked message must be recoverable"
            );

            assertEquals(
                    "failed",
                    recovered.getMessageProperties()
                            .getMessageId()
            );

        } finally {
            container.stop();
        }
    }
}