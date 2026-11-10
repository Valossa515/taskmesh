
package io.github.valossa515.jobmanager.infrastructure.config;

import io.github.valossa515.jobmanager.application.job.execution.ExecutionExpirationSweepService;
import io.github.valossa515.jobmanager.infrastructure.scheduling.ExecutionExpirationScheduler;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(
        prefix = "taskmesh.execution.expiration.scheduling",
        name = "enabled",
        havingValue = "true"
)
public class ExecutionExpirationSchedulingConfiguration {

    @Bean
    public ExecutionExpirationScheduler executionExpirationScheduler(
            ExecutionExpirationSweepService sweepService,
            @Value("${taskmesh.execution.expiration.batch-size:100}")
            int batchSize
    ) {
        return new ExecutionExpirationScheduler(
                sweepService,
                batchSize
        );
    }
}