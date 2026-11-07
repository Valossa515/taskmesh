
package io.github.valossa515.jobmanager.infrastructure.scheduling;

import io.github.valossa515.jobmanager.application.service.OutboxPublishingService;
import io.github.valossa515.jobmanager.infrastructure.config.OutboxSchedulingConfiguration;

import org.junit.jupiter.api.Test;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringJUnitConfig(classes =
        OutboxPublishingSchedulerIntegrationTest.TestConfig.class
)
@TestPropertySource(properties = {
        "taskmesh.publisher.scheduling.enabled=true",
        "taskmesh.publisher.scheduling.fixed-delay-ms=100",
        "taskmesh.publisher.scheduling.initial-delay-ms=100"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OutboxPublishingSchedulerIntegrationTest {

    @TestConfiguration(proxyBeanMethods = false)
    @Import(OutboxSchedulingConfiguration.class)
    static class TestConfig {

        @Bean
        CountDownLatch publishingLatch() {
            return new CountDownLatch(2);
        }

        @Bean
        OutboxPublishingService publishingService(
                CountDownLatch publishingLatch
        ) {
            OutboxPublishingService service =
                    mock(OutboxPublishingService.class);

            when(service.publishBatch()).thenAnswer(
                    invocation -> {
                        publishingLatch.countDown();
                        return 0;
                    }
            );

            return service;
        }
    }

    @org.springframework.beans.factory.annotation.Autowired
    private CountDownLatch publishingLatch;

    @org.springframework.beans.factory.annotation.Autowired
    private OutboxPublishingService publishingService;

    @Test
    void shouldExecutePublishingAutomatically()
            throws InterruptedException {

        boolean executedTwice = publishingLatch.await(
                5,
                TimeUnit.SECONDS
        );

        assertTrue(
                executedTwice,
                "Scheduler should invoke publishBatch at least twice"
        );

        verify(
                publishingService,
                atLeast(2)
        ).publishBatch();
    }
}