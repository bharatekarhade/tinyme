package com.tinyme.domain.health;

import com.tinyme.agent.support.AgentDatabaseConfiguration;
import com.tinyme.domain.health.service.HealthKindBootstrap;
import com.tinyme.domain.health.service.HealthKinds;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "tinyme.agent.setup-enabled=false",
        "TINYME_OWNER_EMAIL=owner@test.tinyme.local",
        "TINYME_OWNER_PASSWORD=test-only-owner-password"
})
@Import(AgentDatabaseConfiguration.class)
class HealthStorageTests {
    @Autowired JdbcTemplate jdbc;
    @Autowired HealthKinds healthKinds;
    @Autowired HealthKindBootstrap bootstrap;

    @Test
    void everyHealthKindIsSeededWithItsFieldsAndNoUses() {
        assertThat(healthKinds.all()).extracting(kind -> kind.kind()).containsExactly("steps", "active_energy",
                "exercise", "walk_distance", "workout", "weight", "body_fat", "sleep", "resting_hr", "hrv");
        for (var kind : healthKinds.all()) {
            for (var field : kind.dataHints().entrySet()) {
                assertThat(jdbc.queryForObject("SELECT data_hints ->> ? FROM entry_kinds WHERE kind = ?",
                        String.class, field.getKey(), kind.kind())).as(kind.kind() + "." + field.getKey())
                        .isEqualTo(field.getValue());
            }
        }
        assertThat(jdbc.queryForObject("SELECT description FROM entry_kinds WHERE kind = 'sleep'", String.class))
                .isEqualTo("One night of sleep, on the day you woke up (filled in by Apple Health when connected)");
    }

    @Test
    @Transactional
    void seedingAgainKeepsUsesAndFieldsAChatKindAlreadyHad() {
        jdbc.update("UPDATE entry_kinds SET use_count = 7, data_hints = '{\"note\":\"string\"}' WHERE kind = 'workout'");

        bootstrap.seed();
        bootstrap.seed();

        assertThat(jdbc.queryForObject("SELECT use_count FROM entry_kinds WHERE kind = 'workout'", Integer.class))
                .isEqualTo(7);
        assertThat(jdbc.queryForObject("SELECT data_hints ->> 'note' FROM entry_kinds WHERE kind = 'workout'",
                String.class)).isEqualTo("string");
        assertThat(jdbc.queryForObject("SELECT data_hints ->> 'minutes' FROM entry_kinds WHERE kind = 'workout'",
                String.class)).isEqualTo("number");
    }

    // Not @Transactional: an expected constraint error would abort the shared transaction.
    @Test
    void healthEntriesNeedAnExternalIdThatIsUniqueWhileLive() {
        try {
            checkExternalIds();
        } finally {
            jdbc.update("DELETE FROM entries WHERE local_day = '2026-10-14' AND kind = 'steps'");
        }
    }

    private void checkExternalIds() {
        assertThatThrownBy(() -> healthEntry(null)).isInstanceOf(DataIntegrityViolationException.class);

        healthEntry("steps:2026-10-14");
        assertThatThrownBy(() -> healthEntry("steps:2026-10-14")).isInstanceOf(DataIntegrityViolationException.class);

        // A deleted one doesn't block the same id coming back.
        jdbc.update("UPDATE entries SET deleted_at = now() WHERE external_id = 'steps:2026-10-14'");
        healthEntry("steps:2026-10-14");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM entries WHERE external_id = 'steps:2026-10-14'",
                Long.class)).isEqualTo(2);

        // Chat entries carry no external id.
        jdbc.update("INSERT INTO entries (ts, local_day, kind, quantity, data) VALUES (now(), '2026-10-14', 'steps', 1, '{}')");
    }

    @Test
    void healthSettingsHoldOneRowOfKnownGroupsAndTopics() {
        assertThatThrownBy(() -> jdbc.update("INSERT INTO health_settings (id) VALUES (2)"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO health_settings (groups) VALUES ('{activity,heart}')"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO health_settings (nudges_off) VALUES ('{body}')"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void theSyncLogOnlyNamesKindsThatExist() {
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO health_sync_log (kind, last_sync_at) VALUES ('no_such_kind', now())"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private void healthEntry(String externalId) {
        jdbc.update("""
                INSERT INTO entries (ts, local_day, kind, quantity, data, source, external_id)
                VALUES ('2026-10-14T03:00:00Z', '2026-10-14', 'steps', 6420, '{"steps":6420}', 'health', ?)
                """, externalId);
    }
}
