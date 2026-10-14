package com.tinyme.api.health;

import com.tinyme.auth.service.DeviceTokenService;
import com.tinyme.maintenance.MaintenanceGate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "tinyme.agent.setup-enabled=false",
        "TINYME_OWNER_EMAIL=owner@test.tinyme.local",
        "TINYME_OWNER_PASSWORD=test-only-owner-password"
})
@AutoConfigureMockMvc
@Import(HealthSyncApiTests.DatabaseConfiguration.class)
class HealthSyncApiTests {
    private static final Instant NOW = Instant.parse("2026-10-14T09:00:00Z");

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired DeviceTokenService tokens;
    @Autowired MaintenanceGate gate;
    @Autowired com.tinyme.domain.health.repository.HealthSyncEpochRepository syncEpoch;

    private static final String STEPS = """
            {"external_id":"steps:2026-10-14","kind":"steps","local_day":"2026-10-14",
             "ts":"2026-10-14T03:00:00Z","quantity":6420,"data":{"steps":6420}}""";
    private static final String WORKOUT = """
            {"external_id":"3F2A-WORKOUT","kind":"workout","local_day":"2026-10-14",
             "ts":"2026-10-14T01:05:00.123456789Z","quantity":1,
             "data":{"type":"traditional strength training","minutes":52,"kcal":310,"avg_hr":128,"source_app":"Workout"}}""";

    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM entries WHERE source = 'health'");
        jdbc.update("DELETE FROM health_sync_log");
        jdbc.update("UPDATE entry_kinds SET use_count = 0 WHERE kind IN ('steps', 'workout', 'sleep')");
        jdbc.update("UPDATE entry_kinds SET merged_into = NULL WHERE kind = 'gym'");
        jdbc.update("DELETE FROM entry_kinds WHERE kind = 'gym'");
    }

    @Test
    void syncNeedsAToken() throws Exception {
        mvc.perform(post("/health/sync").contentType(MediaType.APPLICATION_JSON).content("{\"items\":[]}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void sendingTheSameBatchTwiceChangesNothingAndANewValueReplacesTheOld() throws Exception {
        sync(batch(STEPS + "," + WORKOUT, ""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.upserted").value(2))
                .andExpect(jsonPath("$.unchanged").value(0))
                .andExpect(jsonPath("$.deleted").value(0))
                .andExpect(jsonPath("$.merged").value(0));
        sync(batch(STEPS + "," + WORKOUT, ""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.upserted").value(0))
                .andExpect(jsonPath("$.unchanged").value(2));

        sync(batch(STEPS.replace("6420", "7012"), ""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.upserted").value(1));
        assertThat(jdbc.queryForObject("SELECT quantity::int FROM entries WHERE external_id = 'steps:2026-10-14'",
                Integer.class)).isEqualTo(7012);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM entries WHERE source = 'health'", Long.class)).isEqualTo(2);
        // A new entry is a use of its kind; a replaced one isn't.
        assertThat(jdbc.queryForObject("SELECT use_count FROM entry_kinds WHERE kind = 'steps'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT data ->> 'type' FROM entries WHERE external_id = '3F2A-WORKOUT'",
                String.class)).isEqualTo("traditional strength training");
    }

    @Test
    void aNoteAddedInTinyMeSurvivesANewValueFromHealth() throws Exception {
        sync(batch(STEPS, "")).andExpect(status().isOk());
        jdbc.update("UPDATE entries SET data = data || '{\"note\":\"hiked\"}' WHERE external_id = 'steps:2026-10-14'");

        sync(batch(STEPS, "")).andExpect(jsonPath("$.unchanged").value(1));
        sync(batch(STEPS.replace("6420", "9000"), "")).andExpect(jsonPath("$.upserted").value(1));

        assertThat(jdbc.queryForObject("SELECT data ->> 'note' FROM entries WHERE external_id = 'steps:2026-10-14'",
                String.class)).isEqualTo("hiked");
    }

    @Test
    void deletingSoftDeletesAndTheSameIdCanComeBack() throws Exception {
        sync(batch(WORKOUT, "")).andExpect(status().isOk());

        sync(batch("", "\"3F2A-WORKOUT\", \"never-sent\""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted").value(1));
        assertThat(jdbc.queryForObject("SELECT deleted_at IS NOT NULL FROM entries WHERE external_id = '3F2A-WORKOUT'",
                Boolean.class)).isTrue();
        sync(batch("", "\"3F2A-WORKOUT\"")).andExpect(jsonPath("$.deleted").value(0));

        sync(batch(WORKOUT, "")).andExpect(jsonPath("$.upserted").value(1));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM entries WHERE external_id = '3F2A-WORKOUT' AND deleted_at IS NULL",
                Long.class)).isEqualTo(1);
    }

    @Test
    void aBadItemIsNamedByIndexAndNothingInTheBatchIsStored() throws Exception {
        String zeroSteps = """
                {"external_id":"steps:2026-10-13","kind":"steps","local_day":"2026-10-13",
                 "ts":"2026-10-13T03:00:00Z","quantity":0,"data":{"steps":0}}""";

        sync(batch(STEPS + "," + zeroSteps, ""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.index").value(1))
                .andExpect(jsonPath("$.detail").value("items[1]: quantity must be greater than zero; send a 0 day as a delete"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM entries WHERE source = 'health'", Long.class)).isZero();

        String badDate = STEPS.replace("\"local_day\":\"2026-10-14\"", "\"local_day\":\"14/10/2026\"");
        sync(batch(badDate, ""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.index").value(0));
    }

    @Test
    void wholeBatchMistakesHaveNoIndex() throws Exception {
        sync(batch(STEPS, "\"other\", \"steps:2026-10-14\""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.index").doesNotExist())
                .andExpect(jsonPath("$.deleted_index").value(1))
                .andExpect(jsonPath("$.detail").value("deleted[1]: external_id steps:2026-10-14 is both sent and deleted"));
        sync(batch("", "\"ok\", 42"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.deleted_index").value(1))
                .andExpect(jsonPath("$.detail").value("deleted[1]: external_id is required"));

        String many = IntStream.range(0, 501).mapToObj(i -> STEPS.replace("steps:2026-10-14", "steps:" + i))
                .collect(Collectors.joining(","));
        sync(batch(many, ""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("send at most 500 items at a time"));
    }

    @Test
    void theSyncLogCountsWhatIsStoredPerKind() throws Exception {
        String yesterday = STEPS.replace("2026-10-14", "2026-10-13").replace("2026-10-13T03", "2026-10-13T03");
        sync(batch(STEPS + "," + yesterday, "")).andExpect(status().isOk());

        assertThat(jdbc.queryForObject("SELECT items_total FROM health_sync_log WHERE kind = 'steps'", Long.class))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT last_day::text FROM health_sync_log WHERE kind = 'steps'", String.class))
                .isEqualTo("2026-10-14");
        assertThat(jdbc.queryForObject("SELECT last_sync_at FROM health_sync_log WHERE kind = 'steps'", Instant.class))
                .isEqualTo(NOW);

        sync(batch("", "\"steps:2026-10-14\"")).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT items_total FROM health_sync_log WHERE kind = 'steps'", Long.class))
                .isEqualTo(1);
    }

    @Test
    void aHealthKindMergedIntoAChatKindIsStoredUnderTheMergedIntoKind() throws Exception {
        jdbc.update("INSERT INTO entry_kinds (kind) VALUES ('walk') ON CONFLICT (kind) DO NOTHING");
        jdbc.update("UPDATE entry_kinds SET merged_into = 'walk' WHERE kind = 'steps'");
        try {
            sync(batch(STEPS, "")).andExpect(status().isOk()).andExpect(jsonPath("$.upserted").value(1));
            sync(batch(STEPS, "")).andExpect(jsonPath("$.unchanged").value(1));

            assertThat(jdbc.queryForObject("SELECT kind FROM entries WHERE external_id = 'steps:2026-10-14'",
                    String.class)).isEqualTo("walk");
            assertThat(jdbc.queryForObject("SELECT items_total FROM health_sync_log WHERE kind = 'walk'", Long.class))
                    .isEqualTo(1);
        } finally {
            jdbc.update("DELETE FROM entries WHERE source = 'health'");
            jdbc.update("DELETE FROM health_sync_log");
            jdbc.update("UPDATE entry_kinds SET merged_into = NULL WHERE kind = 'steps'");
            jdbc.update("DELETE FROM entry_kinds WHERE kind = 'walk'");
        }
    }

    @Test
    void aWorkoutsOwnNameSurvivesHealthsType() throws Exception {
        sync(batch(WORKOUT, "")).andExpect(status().isOk());
        jdbc.update("UPDATE entries SET data = data || '{\"label\":\"push day\"}' WHERE external_id = '3F2A-WORKOUT'");

        sync(batch(WORKOUT.replace("\"minutes\":52", "\"minutes\":55"), "")).andExpect(jsonPath("$.upserted").value(1));

        assertThat(jdbc.queryForObject("SELECT data ->> 'label' FROM entries WHERE external_id = '3F2A-WORKOUT'",
                String.class)).isEqualTo("push day");
        assertThat(jdbc.queryForObject("SELECT data ->> 'type' FROM entries WHERE external_id = '3F2A-WORKOUT'",
                String.class)).isEqualTo("traditional strength training");
    }

    @Test
    void aSyncDuringDeleteAllIsTurnedAway() throws Exception {
        assertThat(gate.beginMaintenance(false)).isTrue();
        try {
            sync(batch(STEPS, ""))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.detail").value("TinyMe is resetting, try again in a minute"));
        } finally {
            gate.endMaintenance();
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM entries WHERE source = 'health'", Long.class)).isZero();
    }

    @Test
    void aChatWorkoutAndTheWatchsBecomeOneEntryWithYourWords() throws Exception {
        try {
            chatWorkout("2026-10-14T01:50:00Z", "2026-10-14", "push day", "push day, felt strong");
            chatWorkout("2026-10-13T10:00:00Z", "2026-10-13", "run", "evening run");

            sync(batch(WORKOUT, "")).andExpect(status().isOk()).andExpect(jsonPath("$.merged").value(1));

            assertThat(jdbc.queryForObject("SELECT text FROM entries WHERE external_id = '3F2A-WORKOUT'", String.class))
                    .isEqualTo("push day, felt strong");
            assertThat(jdbc.queryForObject("SELECT data ->> 'label' FROM entries WHERE external_id = '3F2A-WORKOUT'",
                    String.class)).isEqualTo("push day");
            assertThat(jdbc.queryForObject("SELECT data ->> 'type' FROM entries WHERE external_id = '3F2A-WORKOUT'",
                    String.class)).isEqualTo("traditional strength training");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM entries WHERE source = 'chat' AND kind = 'workout' "
                    + "AND deleted_at IS NULL", Long.class)).isEqualTo(1);   // the run on another day stays
            String chatId = jdbc.queryForObject("SELECT id::text FROM entries WHERE source = 'chat' AND text = "
                    + "'push day, felt strong'", String.class);
            assertThat(jdbc.queryForObject("SELECT data ->> 'merged_from' FROM entries WHERE external_id = '3F2A-WORKOUT'",
                    String.class)).isEqualTo(chatId);

            // Merged once; a later sync of new numbers keeps your words.
            sync(batch(WORKOUT.replace("\"minutes\":52", "\"minutes\":53"), ""))
                    .andExpect(jsonPath("$.merged").value(0)).andExpect(jsonPath("$.upserted").value(1));
            assertThat(jdbc.queryForObject("SELECT data ->> 'label' FROM entries WHERE external_id = '3F2A-WORKOUT'",
                    String.class)).isEqualTo("push day");
        } finally {
            jdbc.update("DELETE FROM entries WHERE source = 'chat' AND kind = 'workout'");
        }
    }

    @Test
    void aWorkoutToldInChatAfterTheWatchsMergesOnTheNextSync() throws Exception {
        try {
            sync(batch(WORKOUT, "")).andExpect(jsonPath("$.merged").value(0));
            chatWorkout("2026-10-14T02:30:00Z", "2026-10-14", "leg day", null);

            sync(batch(STEPS, "")).andExpect(jsonPath("$.merged").value(1));
            assertThat(jdbc.queryForObject("SELECT data ->> 'label' FROM entries WHERE external_id = '3F2A-WORKOUT'",
                    String.class)).isEqualTo("leg day");
        } finally {
            jdbc.update("DELETE FROM entries WHERE source = 'chat' AND kind = 'workout'");
        }
    }

    @Test
    void aChatWorkoutHoursAwayIsADifferentSession() throws Exception {
        try {
            chatWorkout("2026-10-14T08:30:00Z", "2026-10-14", "evening run", null);   // 6 h after the Watch one ended
            sync(batch(WORKOUT, "")).andExpect(jsonPath("$.merged").value(0));
        } finally {
            jdbc.update("DELETE FROM entries WHERE source = 'chat' AND kind = 'workout'");
        }
    }

    @Test
    void deletingAHealthWorkoutRestoresTheChatWorkoutItHadMerged() throws Exception {
        try {
            jdbc.update("INSERT INTO entry_kinds (kind, merged_into) VALUES ('gym', 'workout')");
            chatWorkout("2026-10-14T01:50:00Z", "2026-10-14", "push day", "push day, felt strong", "gym");
            sync(batch(WORKOUT, "")).andExpect(jsonPath("$.merged").value(1));
            assertThat(jdbc.queryForObject("SELECT count(*) FROM entries WHERE source = 'chat' AND kind = 'gym' "
                    + "AND deleted_at IS NULL", Long.class)).isZero();

            sync(batch("", "\"3F2A-WORKOUT\"")).andExpect(status().isOk()).andExpect(jsonPath("$.deleted").value(1));

            assertThat(jdbc.queryForObject("SELECT count(*) FROM entries WHERE source = 'chat' AND kind = 'gym' "
                    + "AND deleted_at IS NULL AND text = 'push day, felt strong'", Long.class)).isEqualTo(1);
        } finally {
            jdbc.update("DELETE FROM entries WHERE source = 'chat' AND kind = 'gym'");
        }
    }

    @Test
    void healthStatusExposesTheDurableSyncEpoch() throws Exception {
        long before = syncEpoch.current();
        long bumped = syncEpoch.bump();
        assertThat(bumped).isEqualTo(before + 1);
        mvc.perform(get("/health/status").header("Authorization", "Bearer " + tokens.issue("health-sync-tests").token()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.sync_epoch").value(bumped));
    }

    @Test
    void statusSaysWhatIsStoredPerKind() throws Exception {
        jdbc.update("INSERT INTO health_settings (groups) VALUES ('{activity,workouts}')");
        try {
            sync(batch(STEPS + "," + WORKOUT, "")).andExpect(status().isOk());
            mvc.perform(get("/health/status").header("Authorization", "Bearer " + tokens.issue("health-sync-tests").token()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.groups[0]").value("activity"))
                    .andExpect(jsonPath("$.write_enabled").value(false))
                    .andExpect(jsonPath("$.sync_epoch").isNumber())
                    .andExpect(jsonPath("$.kinds[?(@.kind == 'steps')].items_total").value(1))
                    .andExpect(jsonPath("$.kinds[?(@.kind == 'steps')].last_day").value("2026-10-14"))
                    .andExpect(jsonPath("$.kinds[?(@.kind == 'steps')].last_sync_at").value(NOW.toString()));
        } finally {
            jdbc.update("DELETE FROM health_settings");
        }
    }

    @Test
    void disconnectRemovesWhatHealthMadeAndGivesBackYourWorkout() throws Exception {
        jdbc.update("INSERT INTO health_settings (groups, nudges_off) VALUES ('{activity,workouts}', '{sleep}')");
        long epochBefore = syncEpoch.current();
        try {
            chatWorkout("2026-10-14T01:50:00Z", "2026-10-14", "push day", "push day, felt strong");
            jdbc.update("""
                    INSERT INTO entries (ts, local_day, kind, quantity, data, source)
                    VALUES ('2026-10-14T03:00:00Z', '2026-10-14', 'steps', 2000, '{"steps":2000,"adds_to_health":true}', 'chat')
                    """);
            sync(batch(STEPS + "," + WORKOUT, "")).andExpect(jsonPath("$.merged").value(1));

            disconnect("{\"confirm\":\"remove it\"}").andExpect(status().isBadRequest());
            disconnect("{\"confirm\":\"DELETE HEALTH DATA\"}")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.removed").value(2))
                    .andExpect(jsonPath("$.restored_chat_workouts").value(1));
            assertThat(syncEpoch.current()).isEqualTo(epochBefore + 1);

            assertThat(jdbc.queryForObject("SELECT count(*) FROM entries WHERE source = 'health' AND deleted_at IS NULL",
                    Long.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM health_sync_log", Long.class)).isZero();
            // Your words and your additions stay.
            assertThat(jdbc.queryForObject("SELECT count(*) FROM entries WHERE source = 'chat' AND deleted_at IS NULL "
                    + "AND (text = 'push day, felt strong' OR kind = 'steps')", Long.class)).isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT array_to_string(groups, ',') FROM health_settings", String.class)).isEmpty();
            assertThat(jdbc.queryForObject("SELECT array_to_string(nudges_off, ',') FROM health_settings", String.class))
                    .isEqualTo("sleep");
        } finally {
            jdbc.update("DELETE FROM entries WHERE source = 'chat' AND kind IN ('workout', 'steps')");
            jdbc.update("DELETE FROM health_settings");
        }
    }

    private ResultActions disconnect(String body) throws Exception {
        return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/health/data")
                .header("Authorization", "Bearer " + tokens.issue("health-sync-tests").token())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private void chatWorkout(String ts, String day, String type, String text) {
        chatWorkout(ts, day, type, text, "workout");
    }

    private void chatWorkout(String ts, String day, String type, String text, String kind) {
        jdbc.update("""
                INSERT INTO entries (ts, local_day, kind, quantity, text, data, source)
                VALUES (CAST(? AS timestamptz), CAST(? AS date), ?, 1, ?, CAST(? AS jsonb), 'chat')
                """, ts, day, kind, text, "{\"type\":\"" + type + "\"}");
    }

    private ResultActions sync(String body) throws Exception {
        return mvc.perform(post("/health/sync")
                .header("Authorization", "Bearer " + tokens.issue("health-sync-tests").token())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private static String batch(String items, String deleted) {
        return "{\"items\":[" + items + "],\"deleted\":[" + deleted + "]}";
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class DatabaseConfiguration {
        @Bean
        @ServiceConnection
        PostgreSQLContainer postgres() {
            return new PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:pg18"));
        }

        @Bean
        @org.springframework.context.annotation.Primary
        Clock fixedClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }
}
