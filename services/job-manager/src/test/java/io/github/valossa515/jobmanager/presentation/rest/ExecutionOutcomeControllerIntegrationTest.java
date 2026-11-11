
package io.github.valossa515.jobmanager.presentation.rest;

import io.github.valossa515.jobmanager.application.job.execution.ExecutionClaimService;
import io.github.valossa515.jobmanager.application.port.IJobExecutionRepository;
import io.github.valossa515.jobmanager.application.port.IJobRepository;
import io.github.valossa515.jobmanager.application.port.IOutboxRepository;
import io.github.valossa515.jobmanager.domain.model.*;
import io.github.valossa515.jobmanager.support.IntegrationTestConfig;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "taskmesh.security.java-password=java-test-password",
        "taskmesh.security.dotnet-password=dotnet-test-password",
        "taskmesh.publisher.scheduling.enabled=false",
        "taskmesh.execution.expiration.scheduling.enabled=false"
})
@AutoConfigureMockMvc
@Import(IntegrationTestConfig.class)
class ExecutionOutcomeControllerIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ExecutionClaimService claimService;
    @Autowired private IJobRepository jobs;
    @Autowired private IOutboxRepository events;
    @Autowired private IJobExecutionRepository executions;
    @Autowired private JdbcTemplate jdbc;

    private record Fixture(
            UUID jobId,
            UUID eventId,
            UUID executionId
    ) {}

    private Fixture createRunningJob() {
        Job job = jobs.save(Job.create("NO_OP", "{}"));

        OutboxEvent event = events.save(
                OutboxEvent.create(
                        job.getId(),
                        "job.submitted",
                        "job.submitted",
                        "{}"
                )
        );

        var grant = claimService.start(
                job.getId(),
                event.getId(),
                UUID.randomUUID(),
                "worker-java",
                "java-local"
        );

        return new Fixture(
                job.getId(),
                event.getId(),
                grant.executionId()
        );
    }

    private String outcomeUrl(
            Fixture f,
            UUID executionId,
            String operation
    ) {
        return "/api/v1/jobs/" + f.jobId()
                + "/executions/" + executionId
                + "/" + operation;
    }

    private ResultActions sendComplete(
            Fixture f,
            UUID executionId,
            String username,
            String password
    ) throws Exception {
        return mockMvc.perform(
                post(outcomeUrl(f, executionId, "complete"))
                        .with(httpBasic(username, password))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"eventId":"%s"}
                                """.formatted(f.eventId()))
        );
    }

    private ResultActions sendComplete(Fixture f)
            throws Exception {
        return sendComplete(
                f,
                f.executionId(),
                "worker-java-local",
                "java-test-password"
        );
    }

    private ResultActions sendFail(
            Fixture f,
            String reason
    ) throws Exception {
        return mockMvc.perform(
                post(outcomeUrl(
                        f, f.executionId(), "fail"
                ))
                        .with(httpBasic(
                                "worker-java-local",
                                "java-test-password"
                        ))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "eventId":"%s",
                                  "reason":"%s"
                                }
                                """.formatted(f.eventId(), reason))
        );
    }

    @Test
    void shouldCompleteExecution() throws Exception {
        Fixture f = createRunningJob();

        sendComplete(f)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobId")
                        .value(f.jobId().toString()))
                .andExpect(jsonPath("$.status")
                        .value("COMPLETED"));

        assertEquals(
                JobStatus.COMPLETED,
                jobs.findById(f.jobId()).orElseThrow().getStatus()
        );

        assertEquals(
                JobExecutionStatus.COMPLETED,
                executions.findByJobId(f.jobId())
                        .orElseThrow().getStatus()
        );
    }

    @Test
    void shouldReturnSameCompletionOnRetry() throws Exception {
        Fixture f = createRunningJob();

        String first = sendComplete(f)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        var completedAt = jobs.findById(f.jobId())
                .orElseThrow().getCompletedAt();

        String second = sendComplete(f)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertEquals(first, second);
        assertEquals(
                completedAt,
                jobs.findById(f.jobId())
                        .orElseThrow().getCompletedAt()
        );
    }

    @Test
    void shouldFailExecution() throws Exception {
        Fixture f = createRunningJob();

        sendFail(f, "SIMULATED_FAILURE")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.failureReason")
                        .value("SIMULATED_FAILURE"));

        Job job = jobs.findById(f.jobId()).orElseThrow();
        JobExecution execution = executions
                .findByJobId(f.jobId()).orElseThrow();

        assertEquals(JobStatus.FAILED, job.getStatus());
        assertEquals(
                JobExecutionStatus.FAILED,
                execution.getStatus()
        );
        assertEquals(
                "SIMULATED_FAILURE",
                execution.getFailureReason()
        );
    }

    @Test
    void shouldReturnSameFailureOnRetry() throws Exception {
        Fixture f = createRunningJob();

        String first = sendFail(f, "SIMULATED_FAILURE")
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        var finishedAt = executions
                .findByJobId(f.jobId())
                .orElseThrow().getFinishedAt();

        String second = sendFail(f, "SIMULATED_FAILURE")
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertEquals(first, second);
        assertEquals(
                finishedAt,
                executions.findByJobId(f.jobId())
                        .orElseThrow().getFinishedAt()
        );
    }

    @Test
    void shouldRejectContradictoryOutcomes() throws Exception {
        Fixture completed = createRunningJob();

        sendComplete(completed)
                .andExpect(status().isOk());

        sendFail(completed, "SIMULATED_FAILURE")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value(
                        "urn:taskmesh:problem:outcome-conflict"
                ));

        Fixture failed = createRunningJob();

        sendFail(failed, "SIMULATED_FAILURE")
                .andExpect(status().isOk());

        sendComplete(failed)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value(
                        "urn:taskmesh:problem:outcome-conflict"
                ));

        assertEquals(
                JobStatus.COMPLETED,
                jobs.findById(completed.jobId())
                        .orElseThrow().getStatus()
        );

        assertEquals(
                JobStatus.FAILED,
                jobs.findById(failed.jobId())
                        .orElseThrow().getStatus()
        );
    }

    @Test
    void shouldRejectDifferentWorkerAndExecutionToken()
            throws Exception {
        Fixture f = createRunningJob();

        sendComplete(
                f,
                UUID.randomUUID(),
                "worker-java-local",
                "java-test-password"
        )
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value(
                        "urn:taskmesh:problem:execution-stale"
                ));

        sendComplete(
                f,
                f.executionId(),
                "worker-dotnet-local",
                "dotnet-test-password"
        )
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value(
                        "urn:taskmesh:problem:execution-stale"
                ));

        assertEquals(
                JobStatus.RUNNING,
                jobs.findById(f.jobId()).orElseThrow().getStatus()
        );
    }

    @Test
    void shouldPersistExpirationBeforeReturningConflict()
            throws Exception {
        Fixture f = createRunningJob();

        jdbc.update("""
                UPDATE job_executions
                SET granted_at =
                        clock_timestamp() - INTERVAL '90 seconds',
                    expires_at =
                        clock_timestamp() - INTERVAL '60 seconds'
                WHERE job_id = ?
                """, f.jobId());

        sendComplete(f)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value(
                        "urn:taskmesh:problem:execution-stale"
                ));

        Job job = jobs.findById(f.jobId()).orElseThrow();
        JobExecution execution = executions
                .findByJobId(f.jobId()).orElseThrow();

        assertEquals(JobStatus.FAILED, job.getStatus());
        assertEquals(
                JobExecutionStatus.EXPIRED,
                execution.getStatus()
        );
        assertEquals(
                JobExecution.LEASE_EXPIRED_REASON,
                job.getFailureReason()
        );
    }

    @Test
    void shouldRejectUnauthenticatedOutcome() throws Exception {
        Fixture f = createRunningJob();

        mockMvc.perform(
                        post(outcomeUrl(
                                f, f.executionId(), "complete"
                        ))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                {"eventId":"%s"}
                                """.formatted(f.eventId()))
                )
                .andExpect(status().isUnauthorized());

        assertEquals(
                JobStatus.RUNNING,
                jobs.findById(f.jobId()).orElseThrow().getStatus()
        );
    }

    @Test
    void shouldRejectInvalidFailureReason() throws Exception {
        Fixture f = createRunningJob();

        sendFail(f, " ")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value(
                        "urn:taskmesh:problem:validation-error"
                ));

        sendFail(f, JobExecution.LEASE_EXPIRED_REASON)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value(
                        "urn:taskmesh:problem:validation-error"
                ));

        assertEquals(
                JobStatus.RUNNING,
                jobs.findById(f.jobId()).orElseThrow().getStatus()
        );
    }
}