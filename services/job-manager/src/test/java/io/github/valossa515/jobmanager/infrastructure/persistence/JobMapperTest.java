package io.github.valossa515.jobmanager.infrastructure.persistence;

import io.github.valossa515.jobmanager.domain.model.Job;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class JobMapperTest {
    @Test
    void shouldMapJobToEntityAndBack() {
        Job original = Job.create("REPORT_GENERATION", "{}");

        JobJpaEntity entity = JobMapper.toEntity(original);
        Job restored = JobMapper.toDomain(entity);

        assertAll(
                () -> assertEquals(original.getId(), restored.getId()),
                () -> assertEquals(original.getType(), restored.getType()),
                () -> assertEquals(original.getPayload(), restored.getPayload()),
                () -> assertEquals(original.getStatus(), restored.getStatus()),
                () -> assertEquals(original.getCreatedAt(), restored.getCreatedAt())
        );
    }
}