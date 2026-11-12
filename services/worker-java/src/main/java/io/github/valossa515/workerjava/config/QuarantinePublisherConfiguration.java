
package io.github.valossa515.workerjava.config;

import io.github.valossa515.workerjava.messaging.DeadLetterPublisher;

import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class QuarantinePublisherConfiguration {

    @Bean
    DeadLetterPublisher deadLetterPublisher(
            RabbitTemplate rabbitTemplate,
            CachingConnectionFactory connectionFactory,

            @Value(
                    "${taskmesh.worker.quarantine.confirm-timeout-ms:10000}"
            )
            long confirmTimeoutMillis
    ) {
        return new DeadLetterPublisher(
                rabbitTemplate,
                connectionFactory,
                DeadLetterPublisher.DEAD_QUEUE,
                confirmTimeoutMillis
        );
    }
}