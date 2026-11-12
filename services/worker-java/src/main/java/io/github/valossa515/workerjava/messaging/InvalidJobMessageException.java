
package io.github.valossa515.workerjava.messaging;

import java.util.Objects;

public class InvalidJobMessageException extends RuntimeException {

    private final Reason reason;

    public InvalidJobMessageException(
            Reason reason,
            String message
    ) {
        super(message);
        this.reason = Objects.requireNonNull(reason);
    }

    public Reason reason() {
        return reason;
    }

    public enum Reason {
        MALFORMED_JSON,
        SCHEMA_VIOLATION,
        UNSUPPORTED_JOB_TYPE,
        MISSING_MESSAGE_ID,
        INVALID_MESSAGE_ID,
        MESSAGE_ID_MISMATCH
    }
}