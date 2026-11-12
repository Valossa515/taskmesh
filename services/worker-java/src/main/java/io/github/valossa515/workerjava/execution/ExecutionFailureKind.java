package io.github.valossa515.workerjava.execution;

public enum ExecutionFailureKind {
    ACTIVE_CLAIM,
    STALE_EXECUTION,
    OUTCOME_CONFLICT,
    QUARANTINE,
    AUTHENTICATION,
    TRANSIENT,
    PROTOCOL
}
