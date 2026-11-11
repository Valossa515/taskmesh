
package io.github.valossa515.jobmanager.presentation.rest;

import io.github.valossa515.jobmanager.application.job.execution.ExecutionClaimService;
import io.github.valossa515.jobmanager.application.job.execution.WorkerInstanceRegistrationService;
import io.github.valossa515.jobmanager.application.port.*;
import io.github.valossa515.jobmanager.domain.model.*;
import io.github.valossa515.jobmanager.support.IntegrationTestConfig;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "taskmesh.security.worker-instance-registration.enabled=true",
        "taskmesh.security.worker-instance-registration.signing-key-base64=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "taskmesh.security.java-password=java-test-password",
        "taskmesh.security.dotnet-password=dotnet-test-password",
        "taskmesh.publisher.scheduling.enabled=false",
        "taskmesh.execution.expiration.scheduling.enabled=false"
})
@AutoConfigureMockMvc
@Import(IntegrationTestConfig.class)
class ExecutionOutcomeControllerIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ExecutionClaimService claims;
    @Autowired private WorkerInstanceRegistrationService registrations;
    @Autowired private IJobRepository jobs;
    @Autowired private IOutboxRepository events;
    @Autowired private IJobExecutionRepository executions;
    @Autowired private JdbcTemplate jdbc;

    private record Fixture(
            UUID jobId,
            UUID eventId,
            UUID executionId,
            String ownerToken
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

        var registration = registrations.register(
                "worker-java"
        );

        var grant = claims.start(
                job.getId(),
                event.getId(),
                UUID.randomUUID(),
                registration.workerId(),
                registration.workerInstanceId().toString()
        );

        return new Fixture(
                job.getId(),
                event.getId(),
                grant.executionId(),
                registration.accessToken()
        );
    }

    private String outcomeUrl(
            Fixture fixture,
            UUID executionId,
            String operation
    ) {
        return "/api/v1/jobs/" + fixture.jobId()
                + "/executions/" + executionId
                + "/" + operation;
    }

    private ResultActions sendComplete(
            Fixture fixture,
            UUID executionId,
            String token
    ) throws Exception {
        return mockMvc.perform(
                post(outcomeUrl(
                        fixture,
                        executionId,
                        "complete"
                ))
                        .header(
                                HttpHeaders.AUTHORIZATION,
                                "Bearer " + token
                        )
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"eventId":"%s"}
                                """.formatted(fixture.eventId()))
        );
    }

    private ResultActions sendComplete(Fixture fixture)
            throws Exception {
        return sendComplete(
                fixture,
                fixture.executionId(),
                fixture.ownerToken()
        );
    }

    private ResultActions sendFail(
            Fixture fixture,
            String reason
    ) throws Exception {
        return mockMvc.perform(
                post(outcomeUrl(
                        fixture,
                        fixture.executionId(),
                        "fail"
                ))
                        .header(
                                HttpHeaders.AUTHORIZATION,
                                "Bearer " + fixture.ownerToken()
                        )
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "eventId":"%s",
                                  "reason":"%s"
                                }
                                """.formatted(
                                fixture.eventId(),
                                reason
                        ))
        );
    }

    @Test
    void shouldCompleteExecution() throws Exception {
        Fixture fixture = createRunningJob();

        sendComplete(fixture)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobId")
                        .value(fixture.jobId().toString()))
                .andExpect(jsonPath("$.status")
                        .value("COMPLETED"));

        assertEquals(
                JobStatus.COMPLETED,
                jobs.findById(fixture.jobId())
                        .orElseThrow().getStatus()
        );

        assertEquals(
                JobExecutionStatus.COMPLETED,
                executions.findByJobId(fixture.jobId())
                        .orElseThrow().getStatus()
        );
    }

    @Test
    void shouldReturnSameCompletionOnRetry()
            throws Exception {
        Fixture fixture = createRunningJob();

        String first = sendComplete(fixture)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        var completedAt = jobs.findById(fixture.jobId())
                .orElseThrow().getCompletedAt();

        String second = sendComplete(fixture)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertEquals(first, second);
        assertEquals(
                completedAt,
                jobs.findById(fixture.jobId())
                        .orElseThrow().getCompletedAt()
        );
    }

    @Test
    void shouldFailExecution() throws Exception {
        Fixture fixture = createRunningJob();

        sendFail(fixture, "SIMULATED_FAILURE")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.failureReason")
                        .value("SIMULATED_FAILURE"));

        Job job = jobs.findById(fixture.jobId())
                .orElseThrow();

        JobExecution execution = executions
                .findByJobId(fixture.jobId())
                .orElseThrow();

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
    void shouldReturnSameFailureOnRetry()
            throws Exception {
        Fixture fixture = createRunningJob();

        String first = sendFail(
                fixture,
                "SIMULATED_FAILURE"
        )
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        var finishedAt = executions
                .findByJobId(fixture.jobId())
                .orElseThrow().getFinishedAt();

        String second = sendFail(
                fixture,
                "SIMULATED_FAILURE"
        )
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertEquals(first, second);
        assertEquals(
                finishedAt,
                executions.findByJobId(fixture.jobId())
                        .orElseThrow().getFinishedAt()
        );
    }

    @Test
    void shouldRejectContradictoryOutcomes()
            throws Exception {
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
        Fixture fixture = createRunningJob();

        sendComplete(
                fixture,
                UUID.randomUUID(),
                fixture.ownerToken()
        )
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value(
                        "urn:taskmesh:problem:execution-stale"
                ));

        String dotnetToken = registrations
                .register("worker-dotnet")
                .accessToken();

        sendComplete(
                fixture,
                fixture.executionId(),
                dotnetToken
        )
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value(
                        "urn:taskmesh:problem:execution-stale"
                ));

        assertEquals(
                JobStatus.RUNNING,
                jobs.findById(fixture.jobId())
                        .orElseThrow().getStatus()
        );
    }

    @Test
    void shouldRejectAnotherInstanceOfSameWorker()
            throws Exception {
        Fixture fixture = createRunningJob();

        String secondJavaToken = registrations
                .register("worker-java")
                .accessToken();

        sendComplete(
                fixture,
                fixture.executionId(),
                secondJavaToken
        )
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value(
                        "urn:taskmesh:problem:execution-stale"
                ));

        assertEquals(
                JobStatus.RUNNING,
                jobs.findById(fixture.jobId())
                        .orElseThrow().getStatus()
        );
    }

    @Test
    void shouldPersistExpirationBeforeReturningConflict()
            throws Exception {
        Fixture fixture = createRunningJob();

        jdbc.update("""
                UPDATE job_executions
                SET granted_at =
                        clock_timestamp() - INTERVAL '90 seconds',
                    expires_at =
                        clock_timestamp() - INTERVAL '60 seconds'
                WHERE job_id = ?
                """, fixture.jobId());

        sendComplete(fixture)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value(
                        "urn:taskmesh:problem:execution-stale"
                ));

        Job job = jobs.findById(fixture.jobId())
                .orElseThrow();

        JobExecution execution = executions
                .findByJobId(fixture.jobId())
                .orElseThrow();

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
    void shouldRejectUnauthenticatedOutcome()
            throws Exception {
        Fixture fixture = createRunningJob();

        mockMvc.perform(
                        post(outcomeUrl(
                                fixture,
                                fixture.executionId(),
                                "complete"
                        ))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                {"eventId":"%s"}
                                """.formatted(fixture.eventId()))
                )
                .andExpect(status().isUnauthorized());

        assertEquals(
                JobStatus.RUNNING,
                jobs.findById(fixture.jobId())
                        .orElseThrow().getStatus()
        );
    }

    @Test
    void shouldRejectInvalidFailureReason()
            throws Exception {
        Fixture fixture = createRunningJob();

        sendFail(fixture, " ")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value(
                        "urn:taskmesh:problem:validation-error"
                ));

        sendFail(
                fixture,
                JobExecution.LEASE_EXPIRED_REASON
        )
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value(
                        "urn:taskmesh:problem:validation-error"
                ));

        assertEquals(
                JobStatus.RUNNING,
                jobs.findById(fixture.jobId())
                        .orElseThrow().getStatus()
        );
    }
}