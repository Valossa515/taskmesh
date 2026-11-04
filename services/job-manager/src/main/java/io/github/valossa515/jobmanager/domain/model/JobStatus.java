package io.github.valossa515.jobmanager.domain.model;

/**
 * Lifecycle states of a {@link Job}: {@code PENDING -> RUNNING -> COMPLETED | FAILED}.
 *
 * <p>In the initial version, FAILED represents an execution failure and is only reachable
 * from RUNNING. Invalid requests are rejected before job creation. Cancellation and
 * pre-execution failures are outside the initial scope and may introduce additional states
 * or transitions in future versions.
 */
public enum JobStatus {
    /** Accepted by the system and waiting to be processed. */
    PENDING,
    /** Processing has started. */
    RUNNING,
    /** Processing finished successfully. */
    COMPLETED,
    /** Processing started but finished with an error. */
    FAILED
}
