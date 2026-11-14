
package io.github.valossa515.workerjava.messaging;

import io.github.valossa515.workerjava.registration.WorkerSessionManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.amqp.autoconfigure.RabbitProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Objects;

@Component
@ConditionalOnProperty(
        prefix = "taskmesh.worker.consumer",
        name = "enabled",
        havingValue = "true"
)
public class JobConsumerBootstrap implements ApplicationRunner {

    private static final Logger logger =
            LoggerFactory.getLogger(
                    JobConsumerBootstrap.class
            );

    private final WorkerSessionManager sessions;
    private final RabbitTemplate rabbitTemplate;
    private final RabbitListenerEndpointRegistry registry;
    private final RabbitProperties properties;
    private final boolean registrationOnStartup;

    public JobConsumerBootstrap(
            WorkerSessionManager sessions,
            RabbitTemplate rabbitTemplate,
            RabbitListenerEndpointRegistry registry,
            RabbitProperties properties,
            @Value(
                    "${taskmesh.worker.registration-on-startup:false}"
            )
            boolean registrationOnStartup
    ) {
        this.sessions = Objects.requireNonNull(sessions);
        this.rabbitTemplate = Objects.requireNonNull(rabbitTemplate);
        this.registry = Objects.requireNonNull(registry);
        this.properties = Objects.requireNonNull(properties);
        this.registrationOnStartup = registrationOnStartup;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!registrationOnStartup) {
            throw new IllegalStateException(
                    "RabbitMQ consumer requires worker "
                            + "registration-on-startup=true"
            );
        }

        verifyListenerConfiguration();
        verifyQueues();

        // Registration may have already happened in
        // another ApplicationRunner. It is idempotent
        // within the current process.
        var session = sessions.registerOnce();

        // Fail before opening intake if the worker
        // cannot initiate a new claim safely.
        sessions.tokenForNewClaim();

        var container = registry.getListenerContainer(
                JobMessageListener.LISTENER_ID
        );

        if (container == null) {
            throw new IllegalStateException(
                    "Job message listener was not registered"
            );
        }

        container.start();

        logger.info(
                "Job consumer started for worker instance {}",
                session.workerInstanceId()
        );
    }

    private void verifyListenerConfiguration() {
        var listener = properties.getListener().getSimple();

        if (listener.getAcknowledgeMode()
                != AcknowledgeMode.MANUAL
                || listener.getPrefetch() != 1
                || listener.getConcurrency() != 1
                || listener.getMaxConcurrency() != 1
                || listener.isAutoStartup()) {

            throw new IllegalStateException(
                    "Unsafe RabbitMQ consumer configuration: "
                            + "manual ACK, prefetch=1, "
                            + "concurrency=1 and disabled "
                            + "auto-startup are required"
            );
        }
    }

    private void verifyQueues() {
        rabbitTemplate.execute(channel -> {
            channel.queueDeclarePassive(
                    JobMessageListener.PENDING_QUEUE
            );

            channel.queueDeclarePassive(
                    DeadLetterPublisher.DEAD_QUEUE
            );

            return null;
        });
    }
}