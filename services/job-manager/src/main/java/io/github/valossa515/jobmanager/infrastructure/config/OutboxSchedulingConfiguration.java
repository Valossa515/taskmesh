
package io.github.valossa515.jobmanager.infrastructure.config;

import io.github.valossa515.jobmanager.application.service.OutboxPublishingService;
import io.github.valossa515.jobmanager.infrastructure.scheduling.OutboxPublishingScheduler;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(
        prefix = "taskmesh.publisher.scheduling",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = false
)
public class OutboxSchedulingConfiguration {

    @Bean
    public OutboxPublishingScheduler outboxPublishingScheduler(
            OutboxPublishingService publishingService
    ) {
        return new OutboxPublishingScheduler(
                publishingService
        );
    }
}