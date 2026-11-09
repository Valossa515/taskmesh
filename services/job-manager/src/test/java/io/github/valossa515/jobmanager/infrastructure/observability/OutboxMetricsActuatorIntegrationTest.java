
package io.github.valossa515.jobmanager.infrastructure.observability;

import io.github.valossa515.jobmanager.application.port.IOutboxMetrics;
import io.github.valossa515.jobmanager.support.IntegrationTestConfig;

import io.micrometer.core.instrument.MeterRegistry;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "management.endpoints.web.exposure.include=health,info,metrics",
        "taskmesh.rabbitmq.initialize-on-startup=false",
        "taskmesh.publisher.scheduling.enabled=false"
})
@AutoConfigureMockMvc
@Import(IntegrationTestConfig.class)
class OutboxMetricsActuatorIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private IOutboxMetrics outboxMetrics;

    @Autowired
    private MeterRegistry meterRegistry;

    @Test
    void shouldExposeOutboxMetricsAndUpdatedCounter()
            throws Exception {

        // Verify metric names are exposed by Actuator.
        mockMvc.perform(get("/actuator/metrics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath(
                        "$.names",
                        hasItem("taskmesh.outbox.published")
                ))
                .andExpect(jsonPath(
                        "$.names",
                        hasItem("taskmesh.outbox.failed")
                ))
                .andExpect(jsonPath(
                        "$.names",
                        hasItem("taskmesh.outbox.lease.lost")
                ))
                .andExpect(jsonPath(
                        "$.names",
                        hasItem("taskmesh.outbox.batch.duration")
                ));

        // Read current value from the real MeterRegistry.
        double before = meterRegistry
                .get("taskmesh.outbox.published")
                .counter()
                .count();

        // Update the counter through the application port.
        outboxMetrics.recordPublished();

        double expected = before + 1.0;

        // Verify HTTP response reflects the new value.
        mockMvc.perform(
                        get("/actuator/metrics/taskmesh.outbox.published")
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath(
                        "$.name",
                        is("taskmesh.outbox.published")
                ))
                .andExpect(jsonPath(
                        "$.measurements[0].statistic",
                        is("COUNT")
                ))
                .andExpect(jsonPath(
                        "$.measurements[0].value",
                        is(expected)
                ));
    }
}
