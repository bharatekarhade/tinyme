package com.tinyme.domain.health.service;

import com.tinyme.domain.entries.repository.EntryKindRepository;
import com.tinyme.domain.health.model.HealthGroup;
import com.tinyme.domain.health.model.HealthNudgeTopic;
import com.tinyme.domain.health.model.HealthSettings;
import com.tinyme.domain.health.repository.HealthSettingsRepository;
import com.tinyme.domain.plans.model.OccurrenceStatus;
import com.tinyme.domain.plans.model.PlanOccurrence;
import com.tinyme.domain.plans.service.PlanService;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Follow-up questions (guide §6.3), decided here from entries and plans so the rules are testable and the model
 * doesn't invent them. At most one a day, the same one never twice in 3 days, only for connected groups, and
 * never for a topic switched off in Settings → Apple Health. Asking is the agent's choice: it may mention the
 * {@code nudge:} line once, briefly. A nudge counts as shown once it is put in a turn's context, which errs on
 * the quiet side.
 */
@Service
public class HealthNudges {
    public record Nudge(String key, HealthNudgeTopic topic, String text) { }

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH);
    private static final DateTimeFormatter WEEKDAY = DateTimeFormatter.ofPattern("EEEE", Locale.ENGLISH);
    static final int COOLDOWN_DAYS = 3;
    static final int WORKOUT_GAP_DAYS = 4;
    static final int WEIGHT_GAP_DAYS = 10;
    static final int STEPS_STREAK_DAYS = 5;
    static final Duration HABIT_GRACE = Duration.ofHours(2);
    static final BigDecimal SHORT_NIGHT_HOURS = BigDecimal.valueOf(6);

    private final HealthSettingsRepository settings;
    private final EntryKindRepository kinds;
    private final PlanService plans;
    private final NamedParameterJdbcTemplate jdbc;

    public HealthNudges(HealthSettingsRepository settings, EntryKindRepository kinds, PlanService plans,
                        NamedParameterJdbcTemplate jdbc) {
        this.settings = settings;
        this.kinds = kinds;
        this.plans = plans;
        this.jdbc = jdbc;
    }

    /** The follow-up for this turn, recorded as shown; empty most of the time. */
    @Transactional
    public Optional<Nudge> pick(ZoneId zone, Instant now) {
        HealthSettings health = settings.load();
        if (health.groups().isEmpty()) return Optional.empty();
        LocalDate today = LocalDate.ofInstant(now, zone);
        if (shownOn(today)) return Optional.empty();
        List<Supplier<Optional<Nudge>>> rules = List.of(
                () -> missedHabitToday(health, today, zone, now),
                () -> noWorkoutStreak(health, today, zone, now),
                () -> shortSleep(health, today),
                () -> weightGap(health, today),
                () -> stepsGoalStreak(health, today));
        for (var rule : rules) {
            Optional<Nudge> nudge = rule.get();
            if (nudge.isEmpty() || health.nudgesOff().contains(nudge.get().topic()) || coolingDown(nudge.get().key(), today)) {
                continue;
            }
            jdbc.update("""
                    INSERT INTO nudge_log (nudge_key, day, shown_at) VALUES (:key, :day, :now)
                    ON CONFLICT DO NOTHING
                    """, new MapSqlParameterSource("key", nudge.get().key()).addValue("day", today)
                    .addValue("now", Timestamp.from(now)));
            return nudge;
        }
        return Optional.empty();
    }

    /** A habit plan was due today, its time + 2 h has passed, and it isn't done or skipped. */
    private Optional<Nudge> missedHabitToday(HealthSettings health, LocalDate today, ZoneId zone, Instant now) {
        if (!health.groups().contains(HealthGroup.WORKOUTS)) return Optional.empty();
        return habitsToday(today, zone, now).stream()
                .filter(plan -> plan.startsAt() != null && now.isAfter(plan.startsAt().plus(HABIT_GRACE)))
                .filter(plan -> plan.status() != OccurrenceStatus.DONE && plan.status() != OccurrenceStatus.SKIPPED
                        && plan.status() != OccurrenceStatus.CANCELLED)
                .findFirst()
                .map(plan -> new Nudge("missed_habit_today", HealthNudgeTopic.WORKOUTS, plan.title() + " was planned today at "
                        + plan.startTime().format(TIME) + " and isn't done; ask lightly whether to skip it or move it "
                        + "(plans_update)"));
    }

    /** No workout for 4+ days while a workout habit plan exists. */
    private Optional<Nudge> noWorkoutStreak(HealthSettings health, LocalDate today, ZoneId zone, Instant now) {
        if (!health.groups().contains(HealthGroup.WORKOUTS)) return Optional.empty();
        String workout = kind("workout");
        Long habits = jdbc.queryForObject("""
                SELECT count(*) FROM plans WHERE habit_kind = :kind AND status = 'active' AND deleted_at IS NULL
                """, Map.of("kind", workout), Long.class);
        if (habits == null || habits == 0) return Optional.empty();
        LocalDate last = lastDay(workout, today);
        if (last == null || ChronoUnit.DAYS.between(last, today) < WORKOUT_GAP_DAYS) return Optional.empty();
        StringBuilder text = new StringBuilder("no workout since ").append(last.format(WEEKDAY))
                .append(" (").append(ChronoUnit.DAYS.between(last, today)).append(" days)");
        habitsToday(today, zone, now).stream().filter(plan -> plan.status() == OccurrenceStatus.UPCOMING).findFirst()
                .ifPresent(plan -> text.append("; ").append(plan.title()).append(" is planned today")
                        .append(plan.startTime() == null ? "" : " at " + plan.startTime().format(TIME)));
        text.append("; ask if it's still on or a rest day");
        return Optional.of(new Nudge("no_workout_streak", HealthNudgeTopic.WORKOUTS, text.toString()));
    }

    /** Under 6 h on 3 of the last 4 nights (a night is the sum of its entries, additions included). */
    private Optional<Nudge> shortSleep(HealthSettings health, LocalDate today) {
        if (!health.groups().contains(HealthGroup.SLEEP_HEART)) return Optional.empty();
        List<BigDecimal> nights = jdbc.queryForList("""
                SELECT SUM(quantity) FROM entries
                WHERE kind = :kind AND deleted_at IS NULL AND local_day BETWEEN :from AND :today
                GROUP BY local_day
                """, new MapSqlParameterSource("kind", kind("sleep")).addValue("from", today.minusDays(3))
                .addValue("today", today), BigDecimal.class);
        long shortNights = nights.stream().filter(hours -> hours.compareTo(SHORT_NIGHT_HOURS) < 0).count();
        if (shortNights < 3) return Optional.empty();
        BigDecimal average = nights.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(nights.size()), 1, RoundingMode.HALF_UP);
        return Optional.of(new Nudge("short_sleep", HealthNudgeTopic.SLEEP, "short nights lately: under 6 h on "
                + shortNights + " of the last 4 nights (" + average.stripTrailingZeros().toPlainString()
                + " h average); ask gently if something is keeping them up"));
    }

    /** Body connected, a weight goal on a card, and no weigh-in for 10 days. */
    private Optional<Nudge> weightGap(HealthSettings health, LocalDate today) {
        if (!health.groups().contains(HealthGroup.BODY) || cardGoal("weight").isEmpty()) return Optional.empty();
        LocalDate last = lastDay(kind("weight"), today);
        if (last != null && ChronoUnit.DAYS.between(last, today) < WEIGHT_GAP_DAYS) return Optional.empty();
        String since = last == null ? "no weigh-in yet" : "no weigh-in for " + ChronoUnit.DAYS.between(last, today) + " days";
        return Optional.of(new Nudge("weight_gap", HealthNudgeTopic.WEIGHT, since
                + "; offer a reminder task for a weekly weigh-in"));
    }

    /** A steps goal on a card was met on each of the last 5 days: praise counts too. */
    private Optional<Nudge> stepsGoalStreak(HealthSettings health, LocalDate today) {
        if (!health.groups().contains(HealthGroup.ACTIVITY)) return Optional.empty();
        Optional<BigDecimal> goal = cardGoal("steps");
        if (goal.isEmpty()) return Optional.empty();
        Long metDays = jdbc.queryForObject("""
                SELECT count(*) FROM (
                  SELECT local_day FROM entries
                  WHERE kind = :kind AND deleted_at IS NULL AND local_day BETWEEN :from AND :to
                  GROUP BY local_day HAVING SUM(quantity) >= :goal) met
                """, new MapSqlParameterSource("kind", kind("steps")).addValue("from", today.minusDays(STEPS_STREAK_DAYS))
                .addValue("to", today.minusDays(1)).addValue("goal", goal.get()), Long.class);
        if (metDays == null || metDays < STEPS_STREAK_DAYS) return Optional.empty();
        return Optional.of(new Nudge("steps_goal_streak", HealthNudgeTopic.STEPS, STEPS_STREAK_DAYS
                + " days in a row over the steps goal of " + goal.get().stripTrailingZeros().toPlainString()
                + "; a short well done"));
    }

    private List<PlanOccurrence> habitsToday(LocalDate today, ZoneId zone, Instant now) {
        String workout = kind("workout");
        return plans.upcomingItems(today, today, zone, now).stream()
                .filter(plan -> workout.equals(plan.habitKind())).toList();
    }

    /** The goal of an active card block that counts this kind, if one was set. */
    private Optional<BigDecimal> cardGoal(String kind) {
        return jdbc.queryForList("""
                SELECT (jsonb_path_query_first(spec,
                          '$.blocks[*] ? (exists(@.goal) && @.goal != null && @.metric.kinds[*] == $kind).goal',
                          jsonb_build_object('kind', CAST(:kind AS text))) #>> '{}')::numeric
                FROM cards WHERE status = 'active' AND deleted_at IS NULL
                """, Map.of("kind", kind(kind)), BigDecimal.class).stream()
                .filter(java.util.Objects::nonNull).findFirst();
    }

    private LocalDate lastDay(String kind, LocalDate today) {
        return jdbc.queryForObject("""
                SELECT max(local_day) FROM entries WHERE kind = :kind AND deleted_at IS NULL AND local_day <= :today
                """, new MapSqlParameterSource("kind", kind).addValue("today", today), LocalDate.class);
    }

    private boolean shownOn(LocalDate today) {
        Long shown = jdbc.queryForObject("SELECT count(*) FROM nudge_log WHERE day = :day", Map.of("day", today), Long.class);
        return shown != null && shown > 0;
    }

    private boolean coolingDown(String key, LocalDate today) {
        Long recent = jdbc.queryForObject("SELECT count(*) FROM nudge_log WHERE nudge_key = :key AND day > :since",
                new MapSqlParameterSource("key", key).addValue("since", today.minusDays(COOLDOWN_DAYS)), Long.class);
        return recent != null && recent > 0;
    }

    private String kind(String kind) {
        return kinds.resolveExisting(kind).orElse(kind);
    }
}
