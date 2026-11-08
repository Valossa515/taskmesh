
package io.github.valossa515.jobmanager.infrastructure.observability;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class MicrometerOutboxMetricsTest {

    private SimpleMeterRegistry registry;
    private MicrometerOutboxMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new MicrometerOutboxMetrics(registry);
    }

    @Test
    void shouldCountPublishedEvents() {
        metrics.recordPublished();
        metrics.recordPublished();

        double count = registry
                .get("taskmesh.outbox.published")
                .counter()
                .count();

        assertEquals(2.0, count);
    }

    @Test
    void shouldCountFailedPublications() {
        metrics.recordFailed();

        double count = registry
                .get("taskmesh.outbox.failed")
                .counter()
                .count();

        assertEquals(1.0, count);
    }

    @Test
    void shouldCountLostLeases() {
        metrics.recordLeaseLost();

        double count = registry
                .get("taskmesh.outbox.lease.lost")
                .counter()
                .count();

        assertEquals(1.0, count);
    }

    @Test
    void shouldRecordBatchDuration() {
        metrics.recordBatchDuration(
                Duration.ofMillis(125)
        );

        var timer = registry
                .get("taskmesh.outbox.batch.duration")
                .timer();

        assertEquals(1, timer.count());

        assertEquals(
                125.0,
                timer.totalTime(TimeUnit.MILLISECONDS),
                0.001
        );
    }
}