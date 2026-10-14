package com.tinyme.domain.health;

import com.tinyme.agent.support.AgentDatabaseConfiguration;
import com.tinyme.domain.health.service.HealthNudges;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "tinyme.agent.setup-enabled=false",
        "TINYME_OWNER_EMAIL=owner@test.tinyme.local",
        "TINYME_OWNER_PASSWORD=test-only-owner-password"
})
@Import(AgentDatabaseConfiguration.class)
class HealthNudgesTests {
    private static final ZoneId TOKYO = ZoneId.of("Asia/Tokyo");
    /** Wednesday 14 Oct 2026, 18:00 in Tokyo. */
    private static final Instant NOW = Instant.parse("2026-10-14T09:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 14);

    @Autowired HealthNudges nudges;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM entries WHERE kind IN ('steps', 'sleep', 'workout', 'gym', 'weight')");
        jdbc.update("UPDATE entry_kinds SET merged_into = NULL WHERE kind = 'gym'");
        jdbc.update("DELETE FROM entry_kinds WHERE kind = 'gym'");
        jdbc.update("DELETE FROM plans WHERE title LIKE 'nudge-test%'");
        jdbc.update("DELETE FROM cards WHERE slug LIKE 'nudge-test%'");
        jdbc.update("DELETE FROM nudge_log");
        jdbc.update("DELETE FROM health_settings");
    }

    @Test
    void nothingWhenHealthIsntConnected() {
        shortNights();
        assertThat(nudges.pick(TOKYO, NOW)).isEmpty();
    }

    @Test
    void shortNightsAskOnceADayAndNotAgainForThreeDays() {
        connect("{sleep_heart}", "{}");
        shortNights();

        var nudge = nudges.pick(TOKYO, NOW).orElseThrow();
        assertThat(nudge.key()).isEqualTo("short_sleep");
        assertThat(nudge.text()).startsWith("short nights lately: under 6 h on 3 of the last 4 nights (5.3 h average)");

        // One a day: later turns today get nothing.
        assertThat(nudges.pick(TOKYO, NOW.plusSeconds(3600))).isEmpty();
        // Two days later the same nights would still qualify, but the key is cooling down.
        assertThat(nudges.pick(TOKYO, NOW.plusSeconds(2 * 86_400))).isEmpty();
        assertThat(nudges.pick(TOKYO, NOW.plusSeconds(3 * 86_400))).isEmpty();   // nights moved out of the window
        assertThat(jdbc.queryForObject("SELECT count(*) FROM nudge_log", Long.class)).isEqualTo(1);
    }

    @Test
    void aTopicSwitchedOffIsNeverAskedAbout() {
        connect("{sleep_heart}", "{sleep}");
        shortNights();
        assertThat(nudges.pick(TOKYO, NOW)).isEmpty();
    }

    @Test
    void noWorkoutForDaysWithAGymPlanAsksIfItsStillOn() {
        connect("{workouts}", "{}");
        entry("workout", TODAY.minusDays(5), 1);
        habitPlan("nudge-test gym", TODAY, "19:30");

        var nudge = nudges.pick(TOKYO, NOW).orElseThrow();
        assertThat(nudge.key()).isEqualTo("no_workout_streak");
        assertThat(nudge.text()).isEqualTo("no workout since Friday (5 days); nudge-test gym is planned today at 19:30;"
                + " ask if it's still on or a rest day");
    }

    @Test
    void workoutNudgeSeesEntriesStoredUnderAMergedKind() {
        connect("{workouts}", "{}");
        jdbc.update("INSERT INTO entry_kinds (kind, merged_into) VALUES ('gym', 'workout')");
        entry("gym", TODAY.minusDays(5), 1);
        habitPlan("nudge-test gym", TODAY, "19:30");

        assertThat(nudges.pick(TOKYO, NOW).orElseThrow().text())
                .contains("no workout since Friday (5 days)");
    }

    @Test
    void aHabitTwoHoursPastItsTimeAndNotDoneComesFirst() {
        connect("{workouts}", "{}");
        entry("workout", TODAY.minusDays(5), 1);
        habitPlan("nudge-test morning run", TODAY, "07:00");

        var nudge = nudges.pick(TOKYO, NOW).orElseThrow();
        assertThat(nudge.key()).isEqualTo("missed_habit_today");
        assertThat(nudge.text()).startsWith("nudge-test morning run was planned today at 07:00 and isn't done");
    }

    @Test
    void noWorkoutNudgeWithoutAWorkoutPlan() {
        connect("{workouts}", "{}");
        entry("workout", TODAY.minusDays(9), 1);
        assertThat(nudges.pick(TOKYO, NOW)).isEmpty();
    }

    @Test
    void aWeightGoalWithoutRecentWeighInsOffersAReminder() {
        connect("{body}", "{}");
        goalCard("nudge-test-weight", "weight", 70);
        entry("weight", TODAY.minusDays(12), 72.4);

        assertThat(nudges.pick(TOKYO, NOW).orElseThrow().text())
                .isEqualTo("no weigh-in for 12 days; offer a reminder task for a weekly weigh-in");
    }

    @Test
    void fiveDaysOverTheStepsGoalIsPraised() {
        connect("{activity}", "{}");
        goalCard("nudge-test-steps", "steps", 8000);
        for (int back = 1; back <= 5; back++) entry("steps", TODAY.minusDays(back), 9000);

        var nudge = nudges.pick(TOKYO, NOW).orElseThrow();
        assertThat(nudge.key()).isEqualTo("steps_goal_streak");
        assertThat(nudge.text()).startsWith("5 days in a row over the steps goal of 8000");
    }

    private void connect(String groups, String nudgesOff) {
        jdbc.update("INSERT INTO health_settings (groups, nudges_off) VALUES (CAST(? AS text[]), CAST(? AS text[]))",
                groups, nudgesOff);
    }

    /** 5.5, 7.2, 4.8 and 5.6 h: three of the last four nights under 6 h. */
    private void shortNights() {
        double[] hours = {5.5, 7.2, 4.8, 3.6};
        for (int back = 0; back < 4; back++) entry("sleep", TODAY.minusDays(back), hours[back]);
        entry("sleep", TODAY, 0.1);   // an addition on last night counts with it: 5.6 h
    }

    private void entry(String kind, LocalDate day, double quantity) {
        jdbc.update("""
                INSERT INTO entries (ts, local_day, kind, quantity, data)
                VALUES (CAST(? AS date) + time '03:00', ?, ?, ?, '{}')
                """, day.toString(), day, kind, quantity);
    }

    private void habitPlan(String title, LocalDate day, String time) {
        jdbc.update("""
                INSERT INTO plans (kind, title, zone, start_day, start_time, repeat_freq, habit_kind)
                VALUES ('event', ?, 'Asia/Tokyo', ?, CAST(? AS time), 'daily', 'workout')
                """, title, day, time);
    }

    private void goalCard(String slug, String kind, int goal) {
        jdbc.update("""
                INSERT INTO cards (slug, title, spec, status, activated_at)
                VALUES (?, 'test', CAST(? AS jsonb), 'active', now())
                """, slug, "{\"v\":1,\"blocks\":[{\"type\":\"stat\",\"metric\":{\"kinds\":[\"" + kind
                + "\"],\"op\":\"sum\"},\"goal\":" + goal + "}]}");
    }
}
