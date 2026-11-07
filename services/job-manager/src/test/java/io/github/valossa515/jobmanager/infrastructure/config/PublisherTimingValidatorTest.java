
package io.github.valossa515.jobmanager.infrastructure.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PublisherTimingValidatorTest {

    @Test
    void shouldAcceptValidPublisherTiming() {
        assertDoesNotThrow(
                () -> PublisherTimingValidator.validate(
                        10,
                        30,
                        5
                )
        );
    }

    @Test
    void shouldRejectLeaseShorterThanConfirmTimeout() {
        IllegalArgumentException exception =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> PublisherTimingValidator.validate(
                                60,
                                30,
                                5
                        )
                );

        assertTrue(
                exception.getMessage().contains(
                        "Lease duration must be at least"
                )
        );
    }

    @Test
    void shouldRejectInsufficientSafetyMargin() {
        assertThrows(
                IllegalArgumentException.class,
                () -> PublisherTimingValidator.validate(
                        10,
                        12,
                        5
                )
        );
    }

    @Test
    void shouldAcceptExactMinimumLeaseDuration() {
        assertDoesNotThrow(
                () -> PublisherTimingValidator.validate(
                        10,
                        15,
                        5
                )
        );
    }

    @Test
    void shouldRejectNonPositiveConfirmTimeout() {
        assertThrows(
                IllegalArgumentException.class,
                () -> PublisherTimingValidator.validate(
                        0,
                        30,
                        5
                )
        );
    }

    @Test
    void shouldRejectNegativeSafetyMargin() {
        assertThrows(
                IllegalArgumentException.class,
                () -> PublisherTimingValidator.validate(
                        10,
                        30,
                        -1
                )
        );
    }
}