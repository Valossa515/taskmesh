
package io.github.valossa515.workerjava.execution;

import java.util.Objects;

public class ExecutionApiException extends RuntimeException {

    private final ExecutionFailureKind kind;
    private final Integer httpStatus;
    private final String problemType;
    private final String retryAfter;

    public ExecutionApiException(
            ExecutionFailureKind kind,
            Integer httpStatus,
            String problemType,
            String retryAfter,
            Throwable cause
    ) {
        super(
                "Execution API failure: " + kind
                        + (httpStatus == null
                        ? ""
                        : " (HTTP " + httpStatus + ")"),
                cause
        );

        this.kind = Objects.requireNonNull(kind);
        this.httpStatus = httpStatus;
        this.problemType = problemType;
        this.retryAfter = retryAfter;
    }

    public ExecutionFailureKind kind() {
        return kind;
    }

    public Integer httpStatus() {
        return httpStatus;
    }

    public String problemType() {
        return problemType;
    }

    public String retryAfter() {
        return retryAfter;
    }
}