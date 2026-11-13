
package io.github.valossa515.workerjava.registration;

import java.util.Objects;

public final class WorkerTokenRenewalException
        extends IllegalStateException {

    public enum Kind {
        TRANSIENT,
        REJECTED,
        SESSION_EXPIRED,
        PROTOCOL
    }

    private final Kind kind;

    public WorkerTokenRenewalException(
            Kind kind,
            String message
    ) {
        this(kind, message, null);
    }

    public WorkerTokenRenewalException(
            Kind kind,
            String message,
            Throwable cause
    ) {
        super(message, cause);
        this.kind = Objects.requireNonNull(kind);
    }

    public Kind kind() {
        return kind;
    }
}