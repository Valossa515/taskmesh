
package io.github.valossa515.jobmanager.application.job.create;

import io.github.valossa515.jobmanager.application.port.IOutboxRepository;
import io.github.valossa515.jobmanager.domain.model.OutboxEvent;
import io.github.valossa515.jobmanager.support.IntegrationTestConfig;

import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SpringBootTest
@Import(IntegrationTestConfig.class)
class CreateJobTransactionIntegrationTest {

    @Autowired
    private CreateJobHandler handler;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean(enforceOverride = true)
    private IOutboxRepository outboxRepository;

    @Test
    void shouldRollbackJobWhenOutboxPersistenceFails() {
        String marker = UUID.randomUUID().toString();

        String payload = """
                {"rollbackMarker":"%s"}
                """.formatted(marker);

        when(outboxRepository.save(any(OutboxEvent.class)))
                .thenThrow(new IllegalStateException(
                        "Simulated outbox failure"
                ));

        assertTrue(
                AopUtils.isAopProxy(handler),
                "Handler must be managed by a Spring AOP proxy"
        );

        assertThrows(
                IllegalStateException.class,
                () -> handler.handle(
                        new CreateJobCommand(
                                "NO_OP",
                                payload
                        )
                )
        );

        Integer persistedJobs = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM jobs
                WHERE type = 'NO_OP'
                  AND payload ->> 'rollbackMarker' = ?
                """,
                Integer.class,
                marker
        );

        assertEquals(0, persistedJobs);

        verify(outboxRepository)
                .save(any(OutboxEvent.class));
    }
}