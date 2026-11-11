
package io.github.valossa515.jobmanager.application.job.create;

import io.github.valossa515.jobmanager.application.port.IJobRepository;
import io.github.valossa515.jobmanager.domain.model.Job;
import io.github.valossa515.jobmanager.domain.model.JobStatus;
import io.github.valossa515.jobmanager.support.IntegrationTestConfig;
import io.github.valossa515.spring_courier.core.Courier;
import io.github.valossa515.spring_courier.core.support.Response;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Import(IntegrationTestConfig.class)
@Transactional
class CreateJobCourierIntegrationTest {

    @Autowired
    private Courier courier;

    @Autowired
    private IJobRepository jobRepository;

    @Test
    void shouldDispatchCreateJobCommand() {
        CreateJobCommand command = new CreateJobCommand(
                "NO_OP",
                "{}"
        );

        Response<Job> response = courier.send(command);

        assertTrue(
                response.isSuccess(),
                () -> "Courier failed: " + response.getError()
        );

        Job createdJob = response.getData();

        assertNotNull(createdJob);

        assertAll(
                () -> assertNotNull(createdJob.getId()),
                () -> assertEquals(
                        "NO_OP",
                        createdJob.getType()
                ),
                () -> assertEquals(
                        JobStatus.PENDING,
                        createdJob.getStatus()
                )
        );

        UUID jobId = createdJob.getId();

        Job persistedJob = jobRepository.findById(jobId)
                .orElseThrow();

        assertAll(
                () -> assertEquals(
                        createdJob.getId(),
                        persistedJob.getId()
                ),
                () -> assertEquals(
                        createdJob.getType(),
                        persistedJob.getType()
                ),
                () -> assertEquals(
                        createdJob.getStatus(),
                        persistedJob.getStatus()
                ),
                () -> assertNotNull(
                        persistedJob.getCreatedAt()
                )
        );
    }
}