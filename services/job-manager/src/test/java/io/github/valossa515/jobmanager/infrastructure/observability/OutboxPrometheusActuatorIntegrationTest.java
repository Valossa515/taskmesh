
package io.github.valossa515.jobmanager.infrastructure.observability;

import io.github.valossa515.jobmanager.application.port.IOutboxMetrics;
import io.github.valossa515.jobmanager.support.IntegrationTestConfig;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "management.endpoints.web.exposure.include=health,info,metrics,prometheus",
        "management.prometheus.metrics.export.enabled=true",
        "taskmesh.rabbitmq.initialize-on-startup=false",
        "taskmesh.publisher.scheduling.enabled=false"
})
@AutoConfigureMockMvc
@Import(IntegrationTestConfig.class)
class OutboxPrometheusActuatorIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private IOutboxMetrics outboxMetrics;

    @Autowired
    private org.springframework.context.ApplicationContext context;

    @Test
    void shouldExposeOutboxMetricsInPrometheusFormat()
            throws Exception {

        // Arrange: register a publication using the application port.
        outboxMetrics.recordPublished();

        // Act: request the Prometheus scrape endpoint.
        String response = mockMvc.perform(
                        get("/actuator/prometheus")
                )
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        System.out.println("=== OUTBOX PROMETHEUS METRICS ===");

        response.lines()
                .filter(line -> line.contains("taskmesh_outbox"))
                .forEach(System.out::println);

        System.out.println("=== END ===");

        assertTrue(
                context.getBeansOfType(
                                io.micrometer.core.instrument.MeterRegistry.class
                        ).values()
                        .stream()
                        .anyMatch(registry ->
                                registry.getClass()
                                        .getName()
                                        .contains("PrometheusMeterRegistry")
                        ),
                "PrometheusMeterRegistry was not created"
        );

        // Assert: the custom counter is exported.
        assertTrue(
                response.contains("taskmesh_outbox_published_total"),
                () -> "Expected published counter. Actual outbox metrics:\n"
                        + response.lines()
                        .filter(line -> line.contains("taskmesh_outbox"))
                        .reduce("", (a, b) -> a + b + "\n")
        );

        // Assert: other outbox instruments are available.
        assertTrue(
                response.contains("taskmesh_outbox_failed_total"),
                "Failed counter must be exported"
        );

        assertTrue(
                response.contains("taskmesh_outbox_lease_lost_total"),
                "Lease lost counter must be exported"
        );
    }
}