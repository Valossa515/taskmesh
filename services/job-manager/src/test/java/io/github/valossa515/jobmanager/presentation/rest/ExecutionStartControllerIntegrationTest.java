
package io.github.valossa515.jobmanager.presentation.rest;

import io.github.valossa515.jobmanager.application.port.*;
import io.github.valossa515.jobmanager.domain.model.*;
import io.github.valossa515.jobmanager.support.IntegrationTestConfig;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

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
class ExecutionStartControllerIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private IJobRepository jobs;
    @Autowired private IOutboxRepository events;
    @Autowired private IJobExecutionRepository executions;

    private record Fixture(UUID jobId, UUID eventId) {}

    private Fixture fixture() {
        Job job = jobs.save(Job.create("NO_OP", "{}"));

        OutboxEvent event = events.save(
                OutboxEvent.create(
                        job.getId(),
                        "job.submitted",
                        "job.submitted",
                        "{}"
                )
        );

        return new Fixture(job.getId(), event.getId());
    }

    private String body(UUID eventId, UUID claimId) {
        return """
                {
                  "eventId": "%s",
                  "claimId": "%s"
                }
                """.formatted(eventId, claimId);
    }

    private String url(UUID jobId) {
        return "/api/v1/jobs/" + jobId + "/executions/start";
    }

    @Test
    void shouldRejectUnauthenticatedWorker() throws Exception {
        Fixture f = fixture();

        mockMvc.perform(post(url(f.jobId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(f.eventId(), UUID.randomUUID())))
                .andExpect(status().isUnauthorized());

        assertEquals(
                JobStatus.PENDING,
                jobs.findById(f.jobId()).orElseThrow().getStatus()
        );
    }

    @Test
    void shouldStartJobWithAuthenticatedWorker() throws Exception {
        Fixture f = fixture();

        mockMvc.perform(post(url(f.jobId()))
                        .with(httpBasic(
                                "worker-java-local",
                                "java-test-password"
                        ))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(f.eventId(), UUID.randomUUID())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RUNNING"))
                .andExpect(jsonPath("$.executionId").exists())
                .andExpect(jsonPath("$.expiresAt").exists());

        JobExecution execution = executions
                .findByJobId(f.jobId())
                .orElseThrow();

        assertEquals("worker-java", execution.getWorkerId());
        assertEquals("java-local", execution.getWorkerInstanceId());
    }

    @Test
    void shouldReturnSameGrantForRetriedClaim() throws Exception {
        Fixture f = fixture();
        UUID claimId = UUID.randomUUID();

        String first = mockMvc.perform(post(url(f.jobId()))
                        .with(httpBasic(
                                "worker-java-local",
                                "java-test-password"
                        ))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(f.eventId(), claimId)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String second = mockMvc.perform(post(url(f.jobId()))
                        .with(httpBasic(
                                "worker-java-local",
                                "java-test-password"
                        ))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(f.eventId(), claimId)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertEquals(first, second);
    }

    @Test
    void shouldRejectCompetingWorker() throws Exception {
        Fixture f = fixture();

        mockMvc.perform(post(url(f.jobId()))
                        .with(httpBasic(
                                "worker-java-local",
                                "java-test-password"
                        ))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(f.eventId(), UUID.randomUUID())))
                .andExpect(status().isOk());

        mockMvc.perform(post(url(f.jobId()))
                        .with(httpBasic(
                                "worker-dotnet-local",
                                "dotnet-test-password"
                        ))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(f.eventId(), UUID.randomUUID())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value(
                        "urn:taskmesh:problem:execution-active"
                ));
    }

    @Test
    void shouldRejectUnrelatedSubmission() throws Exception {
        Fixture first = fixture();
        Fixture second = fixture();

        mockMvc.perform(post(url(first.jobId()))
                        .with(httpBasic(
                                "worker-java-local",
                                "java-test-password"
                        ))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(
                                second.eventId(),
                                UUID.randomUUID()
                        )))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value(
                        "urn:taskmesh:problem:submission-mismatch"
                ));

        assertEquals(
                JobStatus.PENDING,
                jobs.findById(first.jobId())
                        .orElseThrow().getStatus()
        );
    }
}