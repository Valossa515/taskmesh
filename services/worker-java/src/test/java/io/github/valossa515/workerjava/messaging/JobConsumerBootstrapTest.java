
package io.github.valossa515.workerjava.messaging;

import io.github.valossa515.workerjava.registration.WorkerSessionManager;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.boot.amqp.autoconfigure.RabbitProperties;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class JobConsumerBootstrapTest {

    private final WorkerSessionManager sessions =
            mock(WorkerSessionManager.class);

    private final RabbitTemplate template =
            mock(RabbitTemplate.class);

    private final RabbitListenerEndpointRegistry registry =
            mock(RabbitListenerEndpointRegistry.class);

    @Test
    void shouldRejectConsumerWithoutStartupRegistration() {
        JobConsumerBootstrap bootstrap =
                new JobConsumerBootstrap(
                        sessions,
                        template,
                        registry,
                        new RabbitProperties(),
                        false
                );

        assertThrows(
                IllegalStateException.class,
                () -> bootstrap.run(null)
        );

        verifyNoInteractions(sessions, template, registry);
    }

    @Test
    void shouldRejectUnsafeAcknowledgementConfiguration() {
        // RabbitProperties defaults to AUTO ACK,
        // which is not safe for this protocol.
        JobConsumerBootstrap bootstrap =
                new JobConsumerBootstrap(
                        sessions,
                        template,
                        registry,
                        new RabbitProperties(),
                        true
                );

        assertThrows(
                IllegalStateException.class,
                () -> bootstrap.run(null)
        );

        verifyNoInteractions(sessions, template, registry);
    }
}