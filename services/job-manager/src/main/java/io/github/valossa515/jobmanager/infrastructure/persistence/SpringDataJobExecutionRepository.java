package io.github.valossa515.jobmanager.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface SpringDataJobExecutionRepository extends JpaRepository<JobExecutionJpaEntity, UUID> {
}
