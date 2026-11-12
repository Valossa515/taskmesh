
package io.github.valossa515.workerjava.messaging;

import java.util.Objects;

public class QuarantinePublicationException
        extends RuntimeException {

    private final Failure failure;

    public QuarantinePublicationException(
            Failure failure,
            String message
    ) {
        super(message);
        this.failure = Objects.requireNonNull(failure);
    }

    public QuarantinePublicationException(
            Failure failure,
            String message,
            Throwable cause
    ) {
        super(message, cause);
        this.failure = Objects.requireNonNull(failure);
    }

    public Failure failure() {
        return failure;
    }

    public enum Failure {
        CONFIGURATION,
        NACK,
        RETURNED,
        TIMEOUT,
        INTERRUPTED,
        TRANSPORT
    }
}