package io.github.valossa515.jobmanager.domain.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class JobTest {

    private static final Instant T0 = Instant.parse("2026-01-01T10:00:00Z");
    private static final Instant T1 = T0.plusSeconds(1);
    private static final Instant T2 = T0.plusSeconds(2);

    @Test
    void createStartsPending() {
        Job job = Job.create("REPORT_GENERATION", "{}");

        assertEquals(JobStatus.PENDING, job.getStatus());
        assertNotNull(job.getId());
        assertNotNull(job.getCreatedAt());
        assertNull(job.getStartedAt());
        assertNull(job.getCompletedAt());
        assertNull(job.getFailureReason());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = "   ")
    void createRejectsBlankType(String type) {
        assertThrows(RuntimeException.class, () -> Job.create(type, "{}"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = "   ")
    void createRejectsBlankPayload(String payload) {
        assertThrows(RuntimeException.class, () -> Job.create("REPORT_GENERATION", payload));
    }

    @Test
    void fullLifecycleToCompleted() {
        Job job = Job.create("REPORT_GENERATION", "{}");
        job.start();
        job.complete();

        assertEquals(JobStatus.COMPLETED, job.getStatus());
        assertNotNull(job.getStartedAt());
        assertNotNull(job.getCompletedAt());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = "   ")
    void failRejectsBlankReasonAndKeepsState(String reason) {
        Job job = Job.create("REPORT_GENERATION", "{}");
        job.start();

        assertThrows(IllegalArgumentException.class, () -> job.fail(reason));
        assertEquals(JobStatus.RUNNING, job.getStatus());
        assertNull(job.getCompletedAt());
    }

    @Test
    void failFromPendingIsRejected() {
        Job job = Job.create("REPORT_GENERATION", "{}");

        assertThrows(IllegalStateException.class, () -> job.fail("boom"));
        assertEquals(JobStatus.PENDING, job.getStatus());
        assertNull(job.getCompletedAt());
        assertNull(job.getFailureReason());
    }

    @Test
    void completeFromPendingIsRejected() {
        Job job = Job.create("REPORT_GENERATION", "{}");

        assertThrows(IllegalStateException.class, job::complete);
        assertEquals(JobStatus.PENDING, job.getStatus());
    }

    @Test
    void startFromRunningIsRejected() {
        Job job = Job.create("REPORT_GENERATION", "{}");
        job.start();

        assertThrows(IllegalStateException.class, job::start);
    }

    @Test
    void terminalStatesRejectFurtherTransitions() {
        Job completed = Job.create("REPORT_GENERATION", "{}");
        completed.start();
        completed.complete();

        assertThrows(IllegalStateException.class, completed::start);
        assertThrows(IllegalStateException.class, completed::complete);
        assertThrows(IllegalStateException.class, () -> completed.fail("boom"));

        Job failed = Job.create("REPORT_GENERATION", "{}");
        failed.start();
        failed.fail("timeout");

        assertThrows(IllegalStateException.class, failed::start);
        assertThrows(IllegalStateException.class, failed::complete);
        assertThrows(IllegalStateException.class, () -> failed.fail("again"));
        assertEquals("timeout", failed.getFailureReason());
    }

    @Test
    void restoreAcceptsValidStates() {
        assertDoesNotThrow(() -> restore(JobStatus.PENDING, null, null, null));
        assertDoesNotThrow(() -> restore(JobStatus.RUNNING, T1, null, null));
        assertDoesNotThrow(() -> restore(JobStatus.COMPLETED, T1, T2, null));
        assertDoesNotThrow(() -> restore(JobStatus.FAILED, T1, T2, "timeout"));
    }

    @Test
    void restoreRejectsInconsistentStates() {
        assertThrows(IllegalArgumentException.class, () -> restore(JobStatus.PENDING, T1, null, null));
        assertThrows(IllegalArgumentException.class, () -> restore(JobStatus.RUNNING, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> restore(JobStatus.RUNNING, T1, T2, null));
        assertThrows(IllegalArgumentException.class, () -> restore(JobStatus.COMPLETED, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> restore(JobStatus.COMPLETED, T1, T2, "oops"));
        assertThrows(IllegalArgumentException.class, () -> restore(JobStatus.FAILED, T1, T2, null));
        assertThrows(IllegalArgumentException.class, () -> restore(JobStatus.FAILED, T1, T2, " "));
    }

    @Test
    void restoreRejectsOutOfOrderTimestamps() {
        assertThrows(IllegalArgumentException.class,
                () -> restore(JobStatus.RUNNING, T0.minusSeconds(1), null, null));
        assertThrows(IllegalArgumentException.class,
                () -> restore(JobStatus.COMPLETED, T2, T1, null));
    }

    private static Job restore(JobStatus status, Instant startedAt, Instant completedAt, String failureReason) {
        return Job.restore(UUID.randomUUID(), "REPORT_GENERATION", "{}", status, T0,
                startedAt, completedAt, failureReason);
    }
}
