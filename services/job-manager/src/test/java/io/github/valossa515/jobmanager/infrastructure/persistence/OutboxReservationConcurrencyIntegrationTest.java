
package io.github.valossa515.jobmanager.infrastructure.persistence;

import io.github.valossa515.jobmanager.application.port.IJobRepository;
import io.github.valossa515.jobmanager.application.port.IOutboxRepository;
import io.github.valossa515.jobmanager.domain.model.Job;
import io.github.valossa515.jobmanager.domain.model.OutboxEvent;
import io.github.valossa515.jobmanager.domain.model.OutboxStatus;
import io.github.valossa515.jobmanager.support.IntegrationTestConfig;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.concurrent.ExecutionException;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Import(IntegrationTestConfig.class)
class OutboxReservationConcurrencyIntegrationTest {

    @Autowired
    private IJobRepository jobRepository;

    @Autowired
    private IOutboxRepository outboxRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;


    @Test
    @Timeout(20)
    void shouldNotReserveSameEventForTwoPublishers()
            throws Exception {

        Job job = jobRepository.save(
                Job.create("NO_OP", "{}")
        );

        OutboxEvent event = OutboxEvent.create(
                UUID.randomUUID(),
                job.getId(),
                "job.submitted",
                "job.submitted",
                "{}",
                Instant.parse("2000-01-01T00:00:00Z")
        );

        outboxRepository.save(event);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        ExecutorService executor =
                Executors.newFixedThreadPool(2);

        try {
            Future<List<OutboxEvent>> resultA =
                    executor.submit(() -> {
                        ready.countDown();
                        if (!start.await(
                                10, TimeUnit.SECONDS
                        )) {
                            throw new IllegalStateException(
                                    "Start signal timed out"
                            );
                        }

                        return outboxRepository.reservePending(
                                "publisher-A",
                                1,
                                Duration.ofSeconds(30)
                        );
                    });

            Future<List<OutboxEvent>> resultB =
                    executor.submit(() -> {
                        ready.countDown();
                        if (!start.await(
                                10, TimeUnit.SECONDS
                        )) {
                            throw new IllegalStateException(
                                    "Start signal timed out"
                            );
                        }

                        return outboxRepository.reservePending(
                                "publisher-B",
                                1,
                                Duration.ofSeconds(30)
                        );
                    });

            assertTrue(
                    ready.await(10, TimeUnit.SECONDS),
                    "Both publishers should be ready"
            );

            start.countDown();

            List<OutboxEvent> reservedA =
                    resultA.get(10, TimeUnit.SECONDS);

            List<OutboxEvent> reservedB =
                    resultB.get(10, TimeUnit.SECONDS);

            long reservationsForEvent =
                    java.util.stream.Stream.concat(
                                    reservedA.stream(),
                                    reservedB.stream()
                            )
                            .filter(reserved ->
                                    reserved.getId().equals(event.getId())
                            )
                            .count();

            assertEquals(
                    1,
                    reservationsForEvent,
                    "An event must be reserved by only one publisher"
            );

            OutboxEvent restored = outboxRepository
                    .findById(event.getId())
                    .orElseThrow();

            assertNotNull(restored.getLeaseToken());
            assertNotNull(restored.getLockedUntil());

            assertTrue(
                    "publisher-A".equals(restored.getLockedBy())
                            || "publisher-B".equals(restored.getLockedBy())
            );

        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }


    @Test
    @Timeout(20)
    void shouldSkipLockedEventAndReserveNextAvailable()
            throws Exception {

        Job job = jobRepository.save(
                Job.create("NO_OP", "{}")
        );

        OutboxEvent lockedEvent = OutboxEvent.create(
                UUID.randomUUID(),
                job.getId(),
                "job.submitted",
                "job.submitted",
                "{}",
                Instant.parse("1990-01-01T00:00:00Z")
        );

        OutboxEvent availableEvent = OutboxEvent.create(
                UUID.randomUUID(),
                job.getId(),
                "job.submitted",
                "job.submitted",
                "{}",
                Instant.parse("1990-01-01T00:00:01Z")
        );

        outboxRepository.save(lockedEvent);
        outboxRepository.save(availableEvent);

        CountDownLatch lockAcquired = new CountDownLatch(1);
        CountDownLatch releaseLock = new CountDownLatch(1);

        ExecutorService executor =
                Executors.newFixedThreadPool(2);

        TransactionTemplate transactionTemplate =
                new TransactionTemplate(transactionManager);

        try {
            Future<?> lockingTransaction =
                    executor.submit(() -> {
                        transactionTemplate.executeWithoutResult(
                                status -> {
                                    UUID lockedId =
                                            jdbcTemplate.queryForObject(
                                                    """
                                                    SELECT id
                                                    FROM outbox_events
                                                    WHERE id = ?
                                                    FOR UPDATE
                                                    """,
                                                    UUID.class,
                                                    lockedEvent.getId()
                                            );

                                    if (!lockedEvent.getId().equals(lockedId)) {
                                        throw new IllegalStateException(
                                                "Unexpected locked event"
                                        );
                                    }

                                    lockAcquired.countDown();

                                    try {
                                        if (!releaseLock.await(
                                                10,
                                                TimeUnit.SECONDS
                                        )) {
                                            throw new IllegalStateException(
                                                    "Lock release timed out"
                                            );
                                        }
                                    } catch (InterruptedException e) {
                                        Thread.currentThread().interrupt();
                                        throw new IllegalStateException(
                                                "Interrupted while holding lock",
                                                e
                                        );
                                    }
                                }
                        );
                    });

            assertTrue(
                    lockAcquired.await(5, TimeUnit.SECONDS),
                    "Transaction A must acquire the row lock"
            );

            Future<List<OutboxEvent>> reservation =
                    executor.submit(() ->
                            outboxRepository.reservePending(
                                    "publisher-B",
                                    1,
                                    Duration.ofSeconds(30)
                            )
                    );

            // Must complete while transaction A still holds the lock.
            List<OutboxEvent> reserved =
                    reservation.get(5, TimeUnit.SECONDS);

            assertEquals(1, reserved.size());

            assertEquals(
                    availableEvent.getId(),
                    reserved.getFirst().getId()
            );

            assertEquals(
                    "publisher-B",
                    reserved.getFirst().getLockedBy()
            );

            assertNotNull(
                    reserved.getFirst().getLeaseToken()
            );

            // Release transaction A only after checking B's result.
            releaseLock.countDown();

            // Propagate any failure from transaction A.
            lockingTransaction.get(5, TimeUnit.SECONDS);

            OutboxEvent untouched = outboxRepository
                    .findById(lockedEvent.getId())
                    .orElseThrow();

            assertNull(untouched.getLeaseToken());
            assertNull(untouched.getLockedBy());

        } finally {
            releaseLock.countDown();
            executor.shutdownNow();
        }
    }


    @Test
    void shouldRecoverExpiredLeaseWithNewPublisher() {
        // Arrange: create a persisted job.
        Job job = jobRepository.save(
                Job.create("NO_OP", "{}")
        );

        UUID eventId = UUID.randomUUID();
        UUID oldToken = UUID.randomUUID();

        Instant createdAt = Instant.parse(
                "1900-01-01T00:00:00Z"
        );

        Instant expiredAt = Instant.now()
                .minusSeconds(120);

        // Restore an event with an already expired lease.
        OutboxEvent expiredEvent = OutboxEvent.restore(
                eventId,
                job.getId(),
                "job.submitted",
                "job.submitted",
                "{}",
                OutboxStatus.PENDING,
                0,
                createdAt,
                null,
                "publisher-A",
                expiredAt,
                oldToken
        );

        outboxRepository.save(expiredEvent);

        // Act: another publisher requests an available event.
        List<OutboxEvent> reserved =
                outboxRepository.reservePending(
                        "publisher-B",
                        1,
                        Duration.ofSeconds(30)
                );

        // Assert: publisher B recovered the expired event.
        assertEquals(1, reserved.size());

        OutboxEvent recovered = reserved.getFirst();

        assertEquals(eventId, recovered.getId());
        assertEquals(
                OutboxStatus.PENDING,
                recovered.getStatus()
        );

        assertEquals(
                "publisher-B",
                recovered.getLockedBy()
        );

        assertNotNull(recovered.getLeaseToken());
        assertNotEquals(
                oldToken,
                recovered.getLeaseToken()
        );

        assertTrue(
                recovered.getLockedUntil()
                        .isAfter(Instant.now())
        );

        // Verify the new reservation is persisted.
        OutboxEvent persisted = outboxRepository
                .findById(eventId)
                .orElseThrow();

        assertEquals(
                "publisher-B",
                persisted.getLockedBy()
        );

        assertEquals(
                recovered.getLeaseToken(),
                persisted.getLeaseToken()
        );

        assertEquals(
                recovered.getLockedUntil(),
                persisted.getLockedUntil()
        );

        // The previous publisher cannot confirm using its old token.
        assertThrows(
                IllegalStateException.class,
                () -> persisted.markPublished(
                        oldToken,
                        Instant.now()
                )
        );

        assertEquals(
                OutboxStatus.PENDING,
                persisted.getStatus()
        );
    }
}