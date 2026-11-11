
package io.github.valossa515.jobmanager.presentation.rest;

import io.github.valossa515.jobmanager.support.IntegrationTestConfig;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Import(IntegrationTestConfig.class)
class JobControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void shouldCreateJob() throws Exception {
        String request = """
                {
                    "type": "NO_OP",
                    "payload": {}
                }
                """;

        mockMvc.perform(
                        post("/api/v1/jobs")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(request)
                )
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(header().string(
                        "Location",
                        org.hamcrest.Matchers.matchesPattern(
                                "/api/v1/jobs/[0-9a-fA-F-]{36}"
                        )
                ))
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.type").value("NO_OP"))
                .andExpect(jsonPath("$.status").value("PENDING"))
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

        mockMvc.perform(
                        post("/api/v1/jobs")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(request)
                )
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(
                        MediaType.APPLICATION_PROBLEM_JSON
                ))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.title")
                        .value("Validation Failed"))
                .andExpect(jsonPath("$.type").value(
                        "urn:taskmesh:problem:validation-error"
                ))
                .andExpect(jsonPath("$.instance")
                        .value("/api/v1/jobs"));
    }

    @Test
    void shouldRejectMissingPayload() throws Exception {
        String request = """
                {
                    "type": "NO_OP"
                }
                """;

        mockMvc.perform(
                        post("/api/v1/jobs")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(request)
                )
                .andExpect(status().isBadRequest());
    }

    @Test
    void shouldRejectNonObjectPayload() throws Exception {
        String request = """
                {
                    "type": "NO_OP",
                    "payload": "invalid"
                }
                """;

        mockMvc.perform(
                        post("/api/v1/jobs")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(request)
                )
                .andExpect(status().isBadRequest());
    }

    @Test
    void shouldRejectMalformedJson() throws Exception {
        String request = """
                {
                    "type": "NO_OP",
                    "payload":
                }
                """;

        mockMvc.perform(
                        post("/api/v1/jobs")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(request)
                )
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.title")
                        .value("Invalid Request Body"))
                .andExpect(jsonPath("$.type").value(
                        "urn:taskmesh:problem:invalid-request-body"
                ));
    }

    @Test
    void shouldRejectUnsupportedTypeWithoutPersisting()
            throws Exception {

        Long jobsBefore = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM jobs",
                Long.class
        );

        Long eventsBefore = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_events",
                Long.class
        );

        mockMvc.perform(
                        post("/api/v1/jobs")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                {
                                    "type": "REPORT_GENERATION",
                                    "payload": {}
                                }
                                """)
                )
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.status").value(422))
                .andExpect(jsonPath("$.type").value(
                        "urn:taskmesh:problem:unsupported-job-type"
                ));

        Long jobsAfter = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM jobs",
                Long.class
        );

        Long eventsAfter = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_events",
                Long.class
        );

        assertEquals(jobsBefore, jobsAfter);
        assertEquals(eventsBefore, eventsAfter);
    }

    @Test
    void shouldRejectLowercaseJobType() throws Exception {
        mockMvc.perform(
                        post("/api/v1/jobs")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                {
                                    "type": "no_op",
                                    "payload": {}
                                }
                                """)
                )
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type").value(
                        "urn:taskmesh:problem:unsupported-job-type"
                ));
    }
}