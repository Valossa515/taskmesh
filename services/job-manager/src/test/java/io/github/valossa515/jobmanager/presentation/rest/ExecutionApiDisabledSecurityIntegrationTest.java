
package io.github.valossa515.jobmanager.presentation.rest;

import io.github.valossa515.jobmanager.support.IntegrationTestConfig;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "taskmesh.security.worker-instance-registration.enabled=false",
        "taskmesh.publisher.scheduling.enabled=false",
        "taskmesh.execution.expiration.scheduling.enabled=false"
})
@AutoConfigureMockMvc
@Import(IntegrationTestConfig.class)
class ExecutionApiDisabledSecurityIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void shouldBlockExecutionWhenRegistrationDisabled()
            throws Exception {

        mockMvc.perform(
                        post(
                                "/api/v1/jobs/"
                                        + UUID.randomUUID()
                                        + "/executions/start"
                        )
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                {
                                  "eventId": "%s",
                                  "claimId": "%s"
                                }
                                """.formatted(
                                        UUID.randomUUID(),
                                        UUID.randomUUID()
                                ))
                )
                .andExpect(status().isForbidden());
    }


    @Test
    void shouldBlockRefreshWhenWorkerAuthenticationIsDisabled()
            throws Exception {

        mockMvc.perform(
                        post("/api/v1/worker-instances/refresh")
                )
                .andExpect(status().isForbidden());
    }
}