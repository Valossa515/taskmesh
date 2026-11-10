package io.github.valossa515.jobmanager.infrastructure.persistence;

import io.github.valossa515.jobmanager.application.port.IDatabaseClock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;

@Repository
public class PostgresDatabaseClock implements IDatabaseClock {

    private final JdbcTemplate jdbcTemplate;

    public PostgresDatabaseClock(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Instant now() {
        return jdbcTemplate.queryForObject(
                "SELECT clock_timestamp()",
                (rs, rowNum) ->
                        rs.getTimestamp(1).toInstant()
        );
    }
}
