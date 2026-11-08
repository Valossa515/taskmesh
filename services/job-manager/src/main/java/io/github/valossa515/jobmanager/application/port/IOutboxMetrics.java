package io.github.valossa515.jobmanager.application.port;

import java.time.Duration;

public interface IOutboxMetrics {
    void recordPublished();
    void recordFailed();
    void recordLeaseLost();
    void recordBatchDuration(Duration duration);
}
