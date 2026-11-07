package io.github.valossa515.jobmanager.infrastructure.persistence;

import io.github.valossa515.jobmanager.application.port.IOutboxRepository;
import io.github.valossa515.jobmanager.domain.model.OutboxEvent;
import io.github.valossa515.jobmanager.domain.model.OutboxStatus;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class OutboxRepositoryAdapter implements IOutboxRepository {

    private final SpringDataOutboxRepository repository;
    private final NamedParameterJdbcTemplate jdbcTemplate;

    public OutboxRepositoryAdapter(
            SpringDataOutboxRepository repository,
            NamedParameterJdbcTemplate jdbcTemplate) {
        this.repository = repository;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public OutboxEvent save(OutboxEvent event) {
        OutboxJpaEntity entity = OutboxMapper.toEntity(event);

        OutboxJpaEntity saved = repository.save(entity);

        return OutboxMapper.toDomain(saved);
    }

    @Override
    public Optional<OutboxEvent> findById(UUID id) {
        return repository.findById(id)
                .map(OutboxMapper::toDomain);
    }

    @Override
    public List<OutboxEvent> findPending(int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException(
                    "Limit must be greater than zero"
            );
        }

        return repository
                .findByStatusOrderByCreatedAtAscIdAsc(
                        OutboxStatus.PENDING,
                        PageRequest.of(0, limit)
                )
                .stream()
                .map(OutboxMapper::toDomain)
                .toList();
    }

    @Override
    @Transactional
    public List<OutboxEvent> reservePending(
            String publisherId,
            int limit,
            Duration leaseDuration) {

        if (publisherId == null
                || publisherId.isBlank()
                || publisherId.length() > 100) {
            throw new IllegalArgumentException(
                    "Invalid publisher ID"
            );
        }

        if (limit <= 0) {
            throw new IllegalArgumentException(
                    "Limit must be greater than zero"
            );
        }

        if (leaseDuration == null
                || leaseDuration.isNegative()
                || leaseDuration.isZero()
                || leaseDuration.toMillis() == 0) {
            throw new IllegalArgumentException(
                    "Lease duration must be positive"
            );
        }

        String sql = """
        WITH candidates AS MATERIALIZED (
            SELECT id
            FROM outbox_events
            WHERE status = 'PENDING'
              AND (
                  locked_until IS NULL
                  OR locked_until <= statement_timestamp()
              )
            ORDER BY created_at, id
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
        ),
        reserved AS (
            UPDATE outbox_events e
            SET
                locked_by = :publisherId,
                locked_until = statement_timestamp()
                    + (CAST(:leaseMillis AS double precision)
                       * INTERVAL '1 millisecond'),
                lease_token = gen_random_uuid()
            FROM candidates c
            WHERE e.id = c.id
            RETURNING
                e.id,
                e.aggregate_id,
                e.event_type,
                e.routing_key,
                CAST(e.payload AS text) AS payload,
                e.status,
                e.attempts,
                e.created_at,
                e.published_at,
                e.locked_by,
                e.locked_until,
                e.lease_token
        )
        SELECT *
        FROM reserved
        ORDER BY created_at, id
        """;

        var parameters = new MapSqlParameterSource()
                .addValue("publisherId", publisherId)
                .addValue("limit", limit)
                .addValue("leaseMillis", leaseDuration.toMillis());


        return jdbcTemplate.query(sql, parameters, (rs, rowNum) ->
                OutboxEvent.restore(
                        rs.getObject("id", UUID.class),
                        rs.getObject("aggregate_id", UUID.class),
                        rs.getString("event_type"),
                        rs.getString("routing_key"),
                        rs.getString("payload"),
                        OutboxStatus.valueOf(
                                rs.getString("status")
                        ),
                        rs.getInt("attempts"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("published_at") == null
                                ? null
                                : rs.getTimestamp("published_at")
                                .toInstant(),
                        rs.getString("locked_by"),
                        rs.getTimestamp("locked_until") == null
                                ? null
                                : rs.getTimestamp("locked_until")
                                .toInstant(),
                        rs.getObject("lease_token", UUID.class)
                )
        );
    }


    @Override
    @Transactional
    public boolean markPublished(
            UUID eventId,
            UUID leaseToken
    ) {
        if (eventId == null) {
            throw new IllegalArgumentException(
                    "Event ID must not be null"
            );
        }

        if (leaseToken == null) {
            throw new IllegalArgumentException(
                    "Lease token must not be null"
            );
        }

        String sql = """
        UPDATE outbox_events
        SET
            status = 'PUBLISHED',
            published_at = statement_timestamp(),
            locked_by = NULL,
            locked_until = NULL,
            lease_token = NULL
        WHERE id = :eventId
          AND status = 'PENDING'
          AND lease_token = :leaseToken
          AND locked_until > statement_timestamp()
        """;

        var parameters = new MapSqlParameterSource()
                .addValue("eventId", eventId)
                .addValue("leaseToken", leaseToken);

        int updatedRows = jdbcTemplate.update(
                sql,
                parameters
        );

        return updatedRows == 1;
    }
}