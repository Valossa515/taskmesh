
package io.github.valossa515.jobmanager.infrastructure.persistence;

import io.github.valossa515.jobmanager.application.port.IJobRepository;
import io.github.valossa515.jobmanager.application.port.IOutboxRepository;
import io.github.valossa515.jobmanager.domain.model.Job;
import io.github.valossa515.jobmanager.domain.model.OutboxEvent;
import io.github.valossa515.jobmanager.support.IntegrationTestConfig;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

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
}