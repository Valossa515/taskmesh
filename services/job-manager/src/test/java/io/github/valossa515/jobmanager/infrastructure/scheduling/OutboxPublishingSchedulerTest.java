
package io.github.valossa515.jobmanager.infrastructure.scheduling;

import io.github.valossa515.jobmanager.application.service.OutboxPublishingService;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.*;

class OutboxPublishingSchedulerTest {

    @Test
    void shouldDelegatePublishingToApplicationService() {
        OutboxPublishingService publishingService =
                mock(OutboxPublishingService.class);

        when(publishingService.publishBatch())
                .thenReturn(2);

        OutboxPublishingScheduler scheduler =
                new OutboxPublishingScheduler(publishingService);

        scheduler.publishPending();

        verify(publishingService, times(1))
                .publishBatch();
    }

    @Test
    void shouldHandlePublishingFailure() {
        OutboxPublishingService publishingService =
                mock(OutboxPublishingService.class);

        when(publishingService.publishBatch())
                .thenThrow(
                        new IllegalStateException(
                                "Simulated database failure"
                        )
                );

        OutboxPublishingScheduler scheduler =
                new OutboxPublishingScheduler(publishingService);

        assertDoesNotThrow(scheduler::publishPending);

        verify(publishingService, times(1))
                .publishBatch();
    }
}