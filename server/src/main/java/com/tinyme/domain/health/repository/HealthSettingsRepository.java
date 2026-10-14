package com.tinyme.domain.health.repository;

import com.tinyme.domain.health.model.HealthGroup;
import com.tinyme.domain.health.model.HealthNudgeTopic;
import com.tinyme.domain.health.model.HealthSettings;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** The single health_settings row; no row means nothing is connected. */
@Repository
public class HealthSettingsRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public HealthSettingsRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public HealthSettings load() {
        List<HealthSettings> rows = jdbc.query("SELECT groups, write_enabled, nudges_off FROM health_settings WHERE id = 1",
                (rs, row) -> new HealthSettings(
                        keys(rs.getArray("groups")).stream().map(key -> HealthGroup.fromKey(key).orElseThrow())
                                .collect(Collectors.toSet()),
                        rs.getBoolean("write_enabled"),
                        keys(rs.getArray("nudges_off")).stream().map(key -> HealthNudgeTopic.fromKey(key).orElseThrow())
                                .collect(Collectors.toSet())));
        return rows.isEmpty() ? HealthSettings.none() : rows.getFirst();
    }

    public void save(HealthSettings settings, Instant now) {
        jdbc.update("""
                INSERT INTO health_settings (id, groups, write_enabled, nudges_off, updated_at)
                VALUES (1, CAST(:groups AS text[]), :write, CAST(:nudges AS text[]), :now)
                ON CONFLICT (id) DO UPDATE SET groups = EXCLUDED.groups, write_enabled = EXCLUDED.write_enabled,
                    nudges_off = EXCLUDED.nudges_off, updated_at = EXCLUDED.updated_at
                """, new MapSqlParameterSource()
                .addValue("groups", literal(settings.groups().stream().map(HealthGroup::key).toList()))
                .addValue("write", settings.writeEnabled())
                .addValue("nudges", literal(settings.nudgesOff().stream().map(HealthNudgeTopic::key).toList()))
                .addValue("now", Timestamp.from(now)));
    }

    private static Set<String> keys(Array array) throws SQLException {
        if (array == null) return Set.of();
        return Set.copyOf(Arrays.asList((String[]) array.getArray()));
    }

    /** Keys are fixed enum words, so a Postgres array literal needs no escaping. */
    private static String literal(List<String> keys) {
        return "{" + String.join(",", keys) + "}";
    }
}
