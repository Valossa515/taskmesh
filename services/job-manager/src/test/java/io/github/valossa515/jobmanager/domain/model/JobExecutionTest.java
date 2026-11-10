package io.github.valossa515.jobmanager.domain.model;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

public class JobExecutionTest {
    private final Instant grantedAt =
            Instant.parse("2026-11-10T12:00:00Z");

    private JobExecution newExecution() {
        return JobExecution.grant(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "worker-java",
                "instance-1",
                grantedAt,
                Duration.ofSeconds(30)
        );
    }

    @Test
    void grantCreatesActiveExecution() {
        JobExecution execution = newExecution();

        assertEquals(JobExecutionStatus.ACTIVE, execution.getStatus());
        assertEquals(
                grantedAt.plusSeconds(30),
                execution.getExpiresAt()
        );
        assertNull(execution.getFinishedAt());
    }

    @Test
    void completeBeforeDeadline() {
        JobExecution execution = newExecution();

        execution.complete(grantedAt.plusSeconds(10));

        assertEquals(
                JobExecutionStatus.COMPLETED,
                execution.getStatus()
        );
    }

    @Test
    void completionAtDeadlineIsRejected() {
        JobExecution execution = newExecution();

        assertThrows(
                IllegalStateException.class,
                () -> execution.complete(grantedAt.plusSeconds(30))
        );

        assertEquals(
                JobExecutionStatus.ACTIVE,
                execution.getStatus()
        );
    }

    @Test
    void expirationAtDeadlineIsAccepted() {
        JobExecution execution = newExecution();

        execution.expire(grantedAt.plusSeconds(30));

        assertEquals(
                JobExecutionStatus.EXPIRED,
                execution.getStatus()
        );
        assertEquals(
                JobExecution.LEASE_EXPIRED_REASON,
                execution.getFailureReason()
        );
    }

    @Test
    void terminalExecutionCannotTransitionAgain() {
        JobExecution execution = newExecution();

        execution.complete(grantedAt.plusSeconds(5));

        assertThrows(
                IllegalStateException.class,
                () -> execution.fail(
                        "Unexpected failure",
                        grantedAt.plusSeconds(10)
                )
        );
    }

    @Test
    void failedExecutionRequiresReason() {
        JobExecution execution = newExecution();

        assertThrows(
                IllegalArgumentException.class,
                () -> execution.fail(
                        "",
                        grantedAt.plusSeconds(5)
                )
        );

        assertEquals(
                JobExecutionStatus.ACTIVE,
                execution.getStatus()
        );
    }
}
