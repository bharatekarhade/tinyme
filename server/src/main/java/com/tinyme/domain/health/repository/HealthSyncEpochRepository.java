package com.tinyme.domain.health.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Durable signal for phones to discard HealthKit anchors after server-side data deletion. */
@Repository
public class HealthSyncEpochRepository {
    private final JdbcTemplate jdbc;

    public HealthSyncEpochRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long current() {
        Long value = jdbc.queryForObject("SELECT (value #>> '{}')::bigint FROM settings WHERE key = 'health.sync_epoch'",
                Long.class);
        return value == null ? 0 : value;
    }

    /** Atomic and safe if two administrative resets happen close together. */
    public long bump() {
        return jdbc.queryForObject("""
                INSERT INTO settings (key, value) VALUES ('health.sync_epoch', '1'::jsonb)
                ON CONFLICT (key) DO UPDATE
                  SET value = to_jsonb((settings.value #>> '{}')::bigint + 1), updated_at = now()
                RETURNING (value #>> '{}')::bigint
                """, Long.class);
    }
}
