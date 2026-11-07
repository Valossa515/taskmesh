
package io.github.valossa515.jobmanager.infrastructure.config;

import io.github.valossa515.jobmanager.application.service.OutboxPublishingService;
import io.github.valossa515.jobmanager.infrastructure.scheduling.OutboxPublishingScheduler;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class OutboxSchedulingConfigurationTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withUserConfiguration(
                            OutboxSchedulingConfiguration.class
                    )
                    .withBean(
                            OutboxPublishingService.class,
                            () -> mock(OutboxPublishingService.class)
                    );

    @Test
    void shouldRegisterSchedulerWhenEnabled() {
        contextRunner
                .withPropertyValues(
                        "taskmesh.publisher.scheduling.enabled=true",
                        "taskmesh.publisher.scheduling.fixed-delay-ms=5000",
                        "taskmesh.publisher.scheduling.initial-delay-ms=60000"
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();

                    assertThat(context)
                            .hasSingleBean(
                                    OutboxPublishingScheduler.class
                            );
                });
    }

    @Test
    void shouldNotRegisterSchedulerWhenDisabled() {
        contextRunner
                .withPropertyValues(
                        "taskmesh.publisher.scheduling.enabled=false"
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();

                    assertThat(context)
                            .doesNotHaveBean(
                                    OutboxPublishingScheduler.class
                            );
                });
    }
}