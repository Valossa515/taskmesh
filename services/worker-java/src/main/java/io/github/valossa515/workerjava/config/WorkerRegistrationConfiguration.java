
package io.github.valossa515.workerjava.config;

import io.github.valossa515.workerjava.registration.WorkerRegistrationClient;
import io.github.valossa515.workerjava.registration.WorkerSessionManager;
import io.github.valossa515.workerjava.execution.ExecutionApiClient;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;


import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;

@Configuration(proxyBeanMethods = false)
public class WorkerRegistrationConfiguration {

    private static final Logger logger =
            LoggerFactory.getLogger(
                    WorkerRegistrationConfiguration.class
            );

    @Bean(destroyMethod = "close")
    HttpClient workerHttpClient() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Bean
    Clock workerClock() {
        return Clock.systemUTC();
    }

    @Bean
    WorkerRegistrationClient workerRegistrationClient(
            HttpClient workerHttpClient,
            JsonMapper jsonMapper,
            Clock workerClock,

            @Value("${taskmesh.job-manager.base-url}")
            String baseUrl,

            @Value("${taskmesh.job-manager.registration.username}")
            String username,

            @Value("${taskmesh.job-manager.registration.password:}")
            String password
    ) {
        return new WorkerRegistrationClient(
                workerHttpClient,
                jsonMapper,
                URI.create(baseUrl),
                username,
                password,
                workerClock
        );
    }

    @Bean
    @ConditionalOnProperty(
            prefix = "taskmesh.worker",
            name = "registration-on-startup",
            havingValue = "true"
    )
    ApplicationRunner registerWorkerOnStartup(
            WorkerSessionManager sessions
    ) {
        return args -> {
            var session = sessions.registerOnce();

            logger.info(
                    "Registered worker {} instance {}. "
                            + "Token expires at {}",
                    session.workerId(),
                    session.workerInstanceId(),
                    session.expiresAt()
            );
        };
    }

    @Bean
    ExecutionApiClient executionApiClient(
            HttpClient workerHttpClient,
            JsonMapper jsonMapper,
            WorkerSessionManager sessions,

            @Value("${taskmesh.job-manager.base-url}")
            String baseUrl
    ) {
        return new ExecutionApiClient(
                workerHttpClient,
                jsonMapper,
                sessions,
                URI.create(baseUrl)
        );
    }
}