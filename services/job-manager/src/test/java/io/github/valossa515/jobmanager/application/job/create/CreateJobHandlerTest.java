package io.github.valossa515.jobmanager.application.job.create;

import io.github.valossa515.jobmanager.application.port.IJobRepository;
import io.github.valossa515.jobmanager.domain.model.Job;
import io.github.valossa515.jobmanager.domain.model.JobStatus;
import io.github.valossa515.jobmanager.job.create.CreateJobCommand;
import io.github.valossa515.jobmanager.job.create.CreateJobHandler;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class CreateJobHandlerTest {
    @Test
    void shouldCreateAndPersistPendingJob() {
        // Arrange
        IJobRepository repository = mock(IJobRepository.class);

        when(repository.save(any(Job.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        CreateJobHandler handler = new CreateJobHandler(repository);

        CreateJobCommand command = new CreateJobCommand(
                "REPORT_GENERATION",
                "{}"
        );

        // Act
        Job result = handler.handle(command);

        // Assert
        assertNotNull(result);
        assertEquals(JobStatus.PENDING, result.getStatus());
        assertEquals("REPORT_GENERATION", result.getType());
        assertEquals("{}", result.getPayload());

        verify(repository, times(1)).save(any(Job.class));
        verifyNoMoreInteractions(repository);
    }

    @Test
    void shouldRejectInvalidCommandWithoutPersisting() {
        // Arrange
        IJobRepository repository = mock(IJobRepository.class);
        CreateJobHandler handler = new CreateJobHandler(repository);

        CreateJobCommand command = new CreateJobCommand(
                "",
                "{}"
        );

        // Act & Assert
        assertThrows(
                IllegalArgumentException.class,
                () -> handler.handle(command)
        );

        verifyNoInteractions(repository);
    }
}
