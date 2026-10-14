package com.tinyme.domain.entries.service;

import com.tinyme.agent.support.AgentDatabaseConfiguration;
import com.tinyme.domain.entries.model.add.AddCommand;
import com.tinyme.domain.entries.model.update.EntryPatch;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "tinyme.agent.setup-enabled=false",
        "TINYME_OWNER_EMAIL=owner@test.tinyme.local",
        "TINYME_OWNER_PASSWORD=test-only-owner-password"
})
@Import(AgentDatabaseConfiguration.class)
class HealthEntryRulesTests {
    private static final ZoneId TOKYO = ZoneId.of("Asia/Tokyo");
    private static final Instant NOON = Instant.parse("2026-10-14T03:00:00Z");

    @Autowired EntryService entries;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM entries WHERE local_day = '2026-10-14' AND kind IN ('steps', 'workout', 'sleep')");
        jdbc.update("DELETE FROM health_settings");
    }

    @Test
    void aHealthEntrysNumbersCantBeChangedButANoteCanBeAdded() {
        UUID steps = healthSteps(6420);

        assertThatThrownBy(() -> entries.update(new EntryPatch(steps, null, BigDecimal.valueOf(9000), null, null, null), TOKYO))
                .isInstanceOf(IllegalArgumentException.class).hasMessageStartingWith("That came from Apple Health");
        assertThatThrownBy(() -> entries.update(new EntryPatch(steps, null, null, null, Map.of("steps", 9000), null), TOKYO))
                .isInstanceOf(IllegalArgumentException.class);

        // The agent may send just the note; the measured numbers stay.
        entries.update(new EntryPatch(steps, null, null, null, Map.of("note", "hiked Takao"), null), TOKYO);
        assertThat(jdbc.queryForObject("SELECT data ->> 'note' FROM entries WHERE id = ?", String.class, steps)).isEqualTo("hiked Takao");
        assertThat(jdbc.queryForObject("SELECT (data ->> 'steps')::int FROM entries WHERE id = ?", Integer.class, steps)).isEqualTo(6420);
        // Sending the same number back with the note is fine too.
        entries.update(new EntryPatch(steps, null, null, "a long walk", Map.of("steps", 6420, "note", "Takao"), null), TOKYO);
        assertThat(jdbc.queryForObject("SELECT text FROM entries WHERE id = ?", String.class, steps)).isEqualTo("a long walk");
    }

    @Test
    void aHealthEntryCantBeDeletedHere() {
        UUID steps = healthSteps(6420);
        assertThatThrownBy(() -> entries.delete(steps, NOON))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Delete it in the Health app");
        assertThat(jdbc.queryForObject("SELECT deleted_at IS NULL FROM entries WHERE id = ?", Boolean.class, steps)).isTrue();
    }

    @Test
    void aHealthKindIsntLoggedFromChatWhileItsGroupIsConnected() {
        jdbc.update("INSERT INTO health_settings (groups) VALUES ('{activity,workouts}')");

        assertThatThrownBy(() -> entries.add(chat("steps", Map.of("steps", 12000))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageStartingWith("Apple Health fills in steps");
        // A workout told in chat is allowed: it's merged with the Watch's on the next sync.
        entries.add(chat("workout", Map.of("type", "push day")));
        // Sleep isn't connected, so chat logs it as usual.
        entries.add(chat("sleep", Map.of("hours", 7)));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM entries WHERE source = 'chat' AND local_day = '2026-10-14' "
                + "AND kind IN ('steps', 'workout', 'sleep')", Long.class)).isEqualTo(2);
    }

    @Test
    void whatHealthMissedCanBeAddedBesideIt() {
        jdbc.update("INSERT INTO health_settings (groups) VALUES ('{sleep_heart}')");
        jdbc.update("""
                INSERT INTO entries (ts, local_day, kind, quantity, data, source, external_id)
                VALUES (?, '2026-10-14', 'sleep', 1.52, '{"hours":1.52}', 'health', 'sleep:2026-10-14')
                """, java.sql.Timestamp.from(NOON));

        // "My watch died, I slept another 7 hours": an addition, not a change to Health's 1.52.
        entries.add(new AddCommand("sleep", BigDecimal.valueOf(7), "watch died", Map.of("hours", 7, "adds_to_health", true),
                List.of(), NOON, TOKYO, "chat"));

        assertThat(jdbc.queryForObject("SELECT sum(quantity) FROM entries WHERE kind = 'sleep' AND local_day = '2026-10-14' "
                + "AND deleted_at IS NULL", BigDecimal.class)).isEqualByComparingTo("8.52");
        assertThat(jdbc.queryForObject("SELECT quantity FROM entries WHERE source = 'health' AND kind = 'sleep' "
                + "AND local_day = '2026-10-14'", BigDecimal.class)).isEqualByComparingTo("1.52");
    }

    @Test
    void withoutHealthConnectedChatLogsStepsAsBefore() {
        entries.add(chat("steps", Map.of("steps", 12000)));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM entries WHERE source = 'chat' AND kind = 'steps' "
                + "AND local_day = '2026-10-14'", Long.class)).isEqualTo(1);
    }

    private UUID healthSteps(int steps) {
        return jdbc.queryForObject("""
                INSERT INTO entries (ts, local_day, kind, quantity, data, source, external_id)
                VALUES (?, '2026-10-14', 'steps', ?, CAST(? AS jsonb), 'health', 'steps:2026-10-14') RETURNING id
                """, UUID.class, java.sql.Timestamp.from(NOON), steps, "{\"steps\":" + steps + "}");
    }

    private static AddCommand chat(String kind, Map<String, Object> data) {
        return new AddCommand(kind, null, null, data, List.of(), NOON, TOKYO, "chat");
    }
}
