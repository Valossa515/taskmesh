
package io.github.valossa515.workerjava.messaging;

import java.util.Objects;

public final class JobProcessingDeferredException
        extends RuntimeException {

    public enum Recovery {
        RETRY_SAME_COMMAND,
        WAIT_FOR_ACTIVE_CLAIM,
        RECONCILE_START,
        PAUSE_INTAKE
    }

    public enum Phase {
        START,
        COMPLETE
    }

    private final Recovery recovery;
    private final Phase phase;

    public JobProcessingDeferredException(
            Recovery recovery,
            Phase phase,
            Throwable cause
    ) {
        super(
                "Job processing requires " + recovery
                        + " during " + phase,
                cause
        );

        this.recovery = Objects.requireNonNull(recovery);
        this.phase = Objects.requireNonNull(phase);
    }

    public Recovery recovery() {
        return recovery;
    }

    public Phase phase() {
        return phase;
    }
}