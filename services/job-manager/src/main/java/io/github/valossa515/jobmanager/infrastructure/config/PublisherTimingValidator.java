package io.github.valossa515.jobmanager.infrastructure.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class PublisherTimingValidator {
    public PublisherTimingValidator(
            @Value("${taskmesh.publisher.confirm-timeout-seconds:10}")
            long confirmTimeoutSeconds,

            @Value("${taskmesh.publisher.lease-seconds:30}")
            long leaseSeconds,

            @Value("${taskmesh.publisher.lease-safety-margin-seconds:5}")
            long safetyMarginSeconds
    ) {
        validate(
                confirmTimeoutSeconds,
                leaseSeconds,
                safetyMarginSeconds
        );
    }

    public static void validate(
            long confirmTimeoutSeconds,
            long leaseSeconds,
            long safetyMarginSeconds
    ) {
        if (confirmTimeoutSeconds <= 0) {
            throw new IllegalArgumentException(
                    "Confirm timeout must be positive"
            );
        }

        if (leaseSeconds <= 0) {
            throw new IllegalArgumentException(
                    "Lease duration must be positive"
            );
        }

        if (safetyMarginSeconds < 0) {
            throw new IllegalArgumentException(
                    "Safety margin cannot be negative"
            );
        }

        if (leaseSeconds < confirmTimeoutSeconds
                || leaseSeconds - confirmTimeoutSeconds
                < safetyMarginSeconds) {
            throw new IllegalArgumentException(
                    "Lease duration must be at least "
                            + "confirm timeout plus safety margin"
            );
        }
    }
}
