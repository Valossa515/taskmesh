
package io.github.valossa515.jobmanager.application.job.create;

import io.github.valossa515.jobmanager.application.port.IJobRepository;
import io.github.valossa515.jobmanager.application.port.IOutboxRepository;
import io.github.valossa515.jobmanager.domain.model.Job;
import io.github.valossa515.jobmanager.domain.model.JobStatus;
import io.github.valossa515.jobmanager.domain.model.OutboxEvent;
import io.github.valossa515.jobmanager.job.create.CreateJobCommand;
import io.github.valossa515.jobmanager.job.create.CreateJobHandler;
import io.github.valossa515.jobmanager.job.create.JobSubmittedEventFactory;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class CreateJobHandlerTest {

    @Test
    void shouldCreateAndPersistPendingJobAndOutboxEvent() {
        IJobRepository jobRepository = mock(IJobRepository.class);
        IOutboxRepository outboxRepository = mock(IOutboxRepository.class);
        JobSubmittedEventFactory eventFactory =
                mock(JobSubmittedEventFactory.class);

        when(jobRepository.save(any(Job.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        when(eventFactory.create(any(Job.class)))
                .thenAnswer(invocation -> {
                    Job job = invocation.getArgument(0);

                    return OutboxEvent.create(
                            job.getId(),
                            "job.submitted",
                            "job.submitted",
                            "{}"
                    );
                });

        CreateJobHandler handler = new CreateJobHandler(
                jobRepository,
                outboxRepository,
                eventFactory
        );

        CreateJobCommand command = new CreateJobCommand(
                "REPORT_GENERATION",
                "{}"
        );

        Job result = handler.handle(command);

        assertNotNull(result);
        assertEquals(JobStatus.PENDING, result.getStatus());
        assertEquals("REPORT_GENERATION", result.getType());
        assertEquals("{}", result.getPayload());

        verify(jobRepository, times(1)).save(any(Job.class));
        verify(eventFactory, times(1)).create(result);
        verify(outboxRepository, times(1))
                .save(any(OutboxEvent.class));

        verifyNoMoreInteractions(
                jobRepository,
                outboxRepository,
                eventFactory
        );
    }

    @Test
    void shouldRejectInvalidCommandWithoutPersisting() {
        IJobRepository jobRepository = mock(IJobRepository.class);
        IOutboxRepository outboxRepository = mock(IOutboxRepository.class);
        JobSubmittedEventFactory eventFactory =
                mock(JobSubmittedEventFactory.class);

        CreateJobHandler handler = new CreateJobHandler(
                jobRepository,
                outboxRepository,
                eventFactory
        );

        CreateJobCommand command = new CreateJobCommand(
                "",
                "{}"
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> handler.handle(command)
        );

        verifyNoInteractions(
                jobRepository,
                outboxRepository,
                eventFactory
        );
    }

    @Test
    void shouldPropagateOutboxPersistenceFailure() {
        IJobRepository jobRepository = mock(IJobRepository.class);
        IOutboxRepository outboxRepository = mock(IOutboxRepository.class);
        JobSubmittedEventFactory eventFactory =
                mock(JobSubmittedEventFactory.class);

        when(jobRepository.save(any(Job.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        when(eventFactory.create(any(Job.class)))
                .thenAnswer(invocation -> {
                    Job job = invocation.getArgument(0);

                    return OutboxEvent.create(
                            job.getId(),
                            "job.submitted",
                            "job.submitted",
                            "{}"
                    );
                });

        when(outboxRepository.save(any(OutboxEvent.class)))
                .thenThrow(new IllegalStateException(
                        "Outbox persistence failed"
                ));

        CreateJobHandler handler = new CreateJobHandler(
                jobRepository,
                outboxRepository,
                eventFactory
        );

        assertThrows(
                IllegalStateException.class,
                () -> handler.handle(
                        new CreateJobCommand("NO_OP", "{}")
                )
        );

        verify(jobRepository).save(any(Job.class));
        verify(outboxRepository).save(any(OutboxEvent.class));
    }
}
