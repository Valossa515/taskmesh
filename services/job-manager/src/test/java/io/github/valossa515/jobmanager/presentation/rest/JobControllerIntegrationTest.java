
package io.github.valossa515.jobmanager.presentation.rest;

import io.github.valossa515.jobmanager.support.IntegrationTestConfig;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Import(IntegrationTestConfig.class)
class JobControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void shouldCreateJob() throws Exception {
        String request = """
                {
                    "type": "REPORT_GENERATION",
                    "payload": {
                        "reportId": "report-123",
                        "format": "PDF"
                    }
                }
                """;

        mockMvc.perform(post("/api/v1/jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(header().string(
                        "Location",
                        org.hamcrest.Matchers.matchesPattern(
                                "/api/v1/jobs/[0-9a-fA-F-]{36}"
                        )
                ))
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.type")
                        .value("REPORT_GENERATION"))
                .andExpect(jsonPath("$.status")
                        .value("PENDING"))
                .andExpect(jsonPath("$.createdAt").exists());
    }

    @Test
    void shouldRejectBlankType() throws Exception {
        String request = """
            {
                "type": "",
                "payload": {}
            }
            """;

        mockMvc.perform(post("/api/v1/jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(
                        MediaType.APPLICATION_PROBLEM_JSON
                ))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.title")
                        .value("Validation Failed"))
                .andExpect(jsonPath("$.type")
                        .value("urn:taskmesh:problem:validation-error"))
                .andExpect(jsonPath("$.instance")
                        .value("/api/v1/jobs"));
    }

    @Test
    void shouldRejectMissingPayload() throws Exception {
        String request = """
                {
                    "type": "REPORT_GENERATION"
                }
                """;

        mockMvc.perform(post("/api/v1/jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isBadRequest());
    }

    @Test
    void shouldRejectNonObjectPayload() throws Exception {
        String request = """
                {
                    "type": "REPORT_GENERATION",
                    "payload": "invalid"
                }
                """;

        mockMvc.perform(post("/api/v1/jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isBadRequest());
    }

    @Test
    void shouldRejectMalformedJson() throws Exception {
        String request = """
            {
                "type": "REPORT_GENERATION",
                "payload":
            }
            """;

        mockMvc.perform(post("/api/v1/jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.title")
                        .value("Invalid Request Body"))
                .andExpect(jsonPath("$.type")
                        .value("urn:taskmesh:problem:invalid-request-body"));
    }
}