package io.github.valossa515.jobmanager.infrastructure.persistence;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;


public interface SpringDataJobRepository extends JpaRepository<JobJpaEntity, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT j FROM JobJpaEntity j WHERE j.id = :id")
    Optional<JobJpaEntity> findByIdForUpdate(
            @Param("id") UUID id
    );
}
