
package io.github.valossa515.jobmanager.infrastructure.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class PublisherTimingValidatorContextTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withUserConfiguration(
                            PublisherTimingValidator.class
                    );

    @Test
    void shouldStartContextWithValidPublisherTiming() {
        contextRunner
                .withPropertyValues(
                        "taskmesh.publisher.confirm-timeout-seconds=10",
                        "taskmesh.publisher.lease-seconds=30",
                        "taskmesh.publisher.lease-safety-margin-seconds=5"
                )
                .run(context -> {
                    assertThat(context)
                            .hasNotFailed();

                    assertThat(context)
                            .hasSingleBean(
                                    PublisherTimingValidator.class
                            );
                });
    }

    @Test
    void shouldFailContextWithInvalidPublisherTiming() {
        contextRunner
                .withPropertyValues(
                        "taskmesh.publisher.confirm-timeout-seconds=60",
                        "taskmesh.publisher.lease-seconds=30",
                        "taskmesh.publisher.lease-safety-margin-seconds=5"
                )
                .run(context -> {
                    assertThat(context)
                            .hasFailed();

                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(
                                    IllegalArgumentException.class
                            )
                            .hasRootCauseMessage(
                                    "Lease duration must be at least "
                                            + "confirm timeout plus safety margin"
                            );
                });
    }
}
