package com.tinyme.domain.health;

import com.tinyme.agent.support.AgentDatabaseConfiguration;
import com.tinyme.domain.health.service.HealthContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "tinyme.agent.setup-enabled=false",
        "TINYME_OWNER_EMAIL=owner@test.tinyme.local",
        "TINYME_OWNER_PASSWORD=test-only-owner-password"
})
@Import(AgentDatabaseConfiguration.class)
class HealthContextTests {
    private static final ZoneId TOKYO = ZoneId.of("Asia/Tokyo");
    /** Wednesday 14 Oct 2026, 18:00 in Tokyo. */
    private static final Instant NOW = Instant.parse("2026-10-14T09:00:00Z");

    @Autowired HealthContext context;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM entries WHERE kind IN ('steps', 'sleep', 'workout', 'weight', 'resting_hr')");
        jdbc.update("DELETE FROM health_settings");
    }

    @Test
    void nothingWhenHealthIsntConnected() {
        entry("steps", "2026-10-14", 6420, "{\"steps\":6420}", "health");
        assertThat(context.line(TOKYO, NOW)).isNull();
    }

    @Test
    void theLatestNumbersOfTheConnectedGroups() {
        jdbc.update("INSERT INTO health_settings (groups) VALUES ('{activity,workouts,body,sleep_heart}')");
        entry("steps", "2026-10-14", 6420, "{\"steps\":6420}", "health");
        // Last night: 1.5 h from the Watch plus 7 h the user added after the Watch died.
        entry("sleep", "2026-10-14", 1.5, "{\"hours\":1.5}", "health");
        entry("sleep", "2026-10-14", 7, "{\"hours\":7,\"adds_to_health\":true}", "chat");
        entry("workout", "2026-10-13", 1,
                "{\"type\":\"traditional strength training\",\"label\":\"push day\",\"minutes\":57}", "health");
        entry("weight", "2026-10-12", 72.4, "{\"kg\":72.4}", "health");
        entry("resting_hr", "2026-10-14", 66, "{\"bpm\":66}", "health");

        assertThat(context.line(TOKYO, NOW)).isEqualTo("health: connected activity, workouts, body, sleep_heart"
                + " · steps today 6,420 · slept 8.5 h last night · last workout yesterday 57 min (push day)"
                + " · weight 72.4 kg (Oct 12) · resting HR 66 (today)");
    }

    @Test
    void onlyConnectedGroupsAndNothingStale() {
        jdbc.update("INSERT INTO health_settings (groups) VALUES ('{sleep_heart}')");
        entry("steps", "2026-10-14", 6420, "{\"steps\":6420}", "health");          // Activity isn't connected
        entry("sleep", "2026-10-13", 7.2, "{\"hours\":7.2}", "health");            // the night before still shows
        entry("resting_hr", "2026-10-09", 60, "{\"bpm\":60}", "health");           // 5 days old: left out

        assertThat(context.line(TOKYO, NOW)).isEqualTo("health: connected sleep_heart · slept 7.2 h the night before");
    }

    private void entry(String kind, String day, double quantity, String data, String source) {
        jdbc.update("""
                INSERT INTO entries (ts, local_day, kind, quantity, data, source, external_id)
                VALUES (CAST(? AS date) + time '12:00', CAST(? AS date), ?, ?, CAST(? AS jsonb), ?, ?)
                """, day, day, kind, quantity, data, source,
                "health".equals(source) ? kind + ":" + day + ":" + java.util.UUID.randomUUID() : null);
    }
}
