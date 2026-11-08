package io.github.valossa515.jobmanager.infrastructure.observability;

import io.github.valossa515.jobmanager.application.port.IOutboxMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class MicrometerOutboxMetrics implements IOutboxMetrics {

    private final Counter published;
    private final Counter failed;
    private final Counter leaseLost;
    private final Timer batchDuration;

    public MicrometerOutboxMetrics(MeterRegistry registry) {
        this.published = Counter.builder("taskmesh.outbox.published")
                .description("Confirmed outbox publications")
                .register(registry);

        this.failed = Counter.builder("taskmesh.outbox.failed")
                .description("Failed RabbitMQ publications")
                .register(registry);

        this.leaseLost = Counter.builder("taskmesh.outbox.lease.lost")
                .description("Rejected outbox lease confirmations")
                .register(registry);

        this.batchDuration = Timer.builder("taskmesh.outbox.batch.duration")
                .description("Outbox batch processing duration")
                .register(registry);
    }

    @Override
    public void recordPublished() {
        published.increment();
    }

    @Override
    public void recordFailed() {
        failed.increment();
    }

    @Override
    public void recordLeaseLost() {
        leaseLost.increment();
    }

    @Override
    public void recordBatchDuration(Duration duration) {
        batchDuration.record(duration);
    }
}