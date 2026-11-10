package io.github.valossa515.jobmanager.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface SpringDataJobExecutionRepository extends JpaRepository<JobExecutionJpaEntity, UUID> {
    @Query(value = """
        SELECT job_id
        FROM job_executions
        WHERE status = 'ACTIVE'
          AND expires_at <= statement_timestamp()
        ORDER BY expires_at, job_id
        LIMIT :limit
        """, nativeQuery = true)
    List<UUID> findExpiredJobIds(@Param("limit") int limit);
}