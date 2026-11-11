
package io.github.valossa515.jobmanager.presentation.rest;

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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
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
class ExecutionStartControllerIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private WorkerInstanceRegistrationService registrations;
    @Autowired private IJobRepository jobs;
    @Autowired private IOutboxRepository events;
    @Autowired private IJobExecutionRepository executions;

    private record Fixture(
            UUID jobId,
            UUID eventId
    ) {}

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

    private String url(UUID jobId) {
        return "/api/v1/jobs/" + jobId
                + "/executions/start";
    }

    private String body(UUID eventId, UUID claimId) {
        return """
                {
                  "eventId": "%s",
                  "claimId": "%s"
                }
                """.formatted(eventId, claimId);
    }

    private ResultActions start(
            Fixture fixture,
            UUID claimId,
            String token
    ) throws Exception {
        return mockMvc.perform(
                post(url(fixture.jobId()))
                        .header(
                                HttpHeaders.AUTHORIZATION,
                                "Bearer " + token
                        )
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(fixture.eventId(), claimId))
        );
    }

    @Test
    void shouldRejectUnauthenticatedWorker() throws Exception {
        Fixture fixture = fixture();

        mockMvc.perform(
                        post(url(fixture.jobId()))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body(
                                        fixture.eventId(),
                                        UUID.randomUUID()
                                ))
                )
                .andExpect(status().isUnauthorized());

        assertEquals(
                JobStatus.PENDING,
                jobs.findById(fixture.jobId())
                        .orElseThrow().getStatus()
        );
    }

    @Test
    void shouldRejectBasicAuthenticationOnExecutionRoute()
            throws Exception {
        Fixture fixture = fixture();

        mockMvc.perform(
                        post(url(fixture.jobId()))
                                .with(httpBasic(
                                        "worker-java-local",
                                        "java-test-password"
                                ))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body(
                                        fixture.eventId(),
                                        UUID.randomUUID()
                                ))
                )
                .andExpect(status().isUnauthorized());

        assertEquals(
                JobStatus.PENDING,
                jobs.findById(fixture.jobId())
                        .orElseThrow().getStatus()
        );
    }

    @Test
    void shouldStartJobWithAuthenticatedWorker() throws Exception {
        Fixture fixture = fixture();

        var registration = registrations.register(
                "worker-java"
        );

        start(
                fixture,
                UUID.randomUUID(),
                registration.accessToken()
        )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RUNNING"))
                .andExpect(jsonPath("$.executionId").exists())
                .andExpect(jsonPath("$.expiresAt").exists());

        JobExecution execution = executions
                .findByJobId(fixture.jobId())
                .orElseThrow();

        assertEquals(
                "worker-java",
                execution.getWorkerId()
        );

        assertEquals(
                registration.workerInstanceId().toString(),
                execution.getWorkerInstanceId()
        );

        assertEquals(
                JobStatus.RUNNING,
                jobs.findById(fixture.jobId())
                        .orElseThrow().getStatus()
        );
    }

    @Test
    void shouldReturnSameGrantForRetriedClaim()
            throws Exception {
        Fixture fixture = fixture();

        String token = registrations
                .register("worker-java")
                .accessToken();

        UUID claimId = UUID.randomUUID();

        String first = start(fixture, claimId, token)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String second = start(fixture, claimId, token)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertEquals(first, second);
    }

    @Test
    void shouldRejectCompetingWorker() throws Exception {
        Fixture fixture = fixture();

        String javaToken = registrations
                .register("worker-java")
                .accessToken();

        String dotnetToken = registrations
                .register("worker-dotnet")
                .accessToken();

        start(
                fixture,
                UUID.randomUUID(),
                javaToken
        ).andExpect(status().isOk());

        start(
                fixture,
                UUID.randomUUID(),
                dotnetToken
        )
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value(
                        "urn:taskmesh:problem:execution-active"
                ));
    }

    @Test
    void shouldRejectDifferentInstanceOfSameWorker()
            throws Exception {
        Fixture fixture = fixture();

        var firstInstance = registrations.register(
                "worker-java"
        );

        var secondInstance = registrations.register(
                "worker-java"
        );

        UUID claimId = UUID.randomUUID();

        start(
                fixture,
                claimId,
                firstInstance.accessToken()
        ).andExpect(status().isOk());

        start(
                fixture,
                claimId,
                secondInstance.accessToken()
        )
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value(
                        "urn:taskmesh:problem:execution-active"
                ));

        JobExecution execution = executions
                .findByJobId(fixture.jobId())
                .orElseThrow();

        assertEquals(
                firstInstance.workerInstanceId().toString(),
                execution.getWorkerInstanceId()
        );
    }

    @Test
    void shouldRejectUnrelatedSubmission()
            throws Exception {
        Fixture first = fixture();
        Fixture second = fixture();

        String token = registrations
                .register("worker-java")
                .accessToken();

        mockMvc.perform(
                        post(url(first.jobId()))
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        "Bearer " + token
                                )
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body(
                                        second.eventId(),
                                        UUID.randomUUID()
                                ))
                )
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