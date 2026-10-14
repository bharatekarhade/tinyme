package com.tinyme.domain.health.service;

import com.tinyme.domain.entries.repository.EntryKindRepository;
import com.tinyme.domain.health.model.HealthGroup;
import com.tinyme.domain.health.repository.HealthSettingsRepository;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The {@code health:} line in the agent's [context] (guide §6.2): what's connected and the latest numbers, so
 * "how did I sleep" starts from facts. Only connected groups, only values that exist; anything older than its
 * limit is left out, never shown as 0. Additions told in chat count with Health's numbers.
 */
@Service
public class HealthContext {
    private static final DateTimeFormatter SHORT_DAY = DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH);
    private static final DateTimeFormatter WEEKDAY = DateTimeFormatter.ofPattern("EEE", Locale.ENGLISH);

    private final HealthSettingsRepository settings;
    private final EntryKindRepository kinds;
    private final NamedParameterJdbcTemplate jdbc;

    public HealthContext(HealthSettingsRepository settings, EntryKindRepository kinds, NamedParameterJdbcTemplate jdbc) {
        this.settings = settings;
        this.kinds = kinds;
        this.jdbc = jdbc;
    }

    /** Null when Health isn't connected. */
    @Transactional(readOnly = true)
    public String line(ZoneId zone, Instant now) {
        Set<HealthGroup> groups = settings.load().groups();
        if (groups.isEmpty()) return null;
        LocalDate today = LocalDate.ofInstant(now, zone);
        List<String> parts = new ArrayList<>();
        parts.add("connected " + String.join(", ", groups.stream().map(HealthGroup::key).toList()));
        if (groups.contains(HealthGroup.ACTIVITY)) {
            dayTotal("steps", "quantity", today, 1).ifPresent(total ->
                    parts.add("steps " + dayWord(total.day(), today) + " " + whole(total.value())));
        }
        if (groups.contains(HealthGroup.SLEEP_HEART)) {
            // A night is filed on the day you woke up, so today's sleep is last night.
            dayTotal("sleep", "quantity", today, 1).ifPresent(total -> parts.add("slept "
                    + hours(total.value()) + " h " + (total.day().equals(today) ? "last night" : "the night before")));
        }
        if (groups.contains(HealthGroup.WORKOUTS)) {
            latestWorkout(today).ifPresent(parts::add);
        }
        if (groups.contains(HealthGroup.BODY)) {
            latest("weight", today, 30).ifPresent(weight ->
                    parts.add("weight " + weight.value().stripTrailingZeros().toPlainString() + " kg (" + weight.day().format(SHORT_DAY) + ")"));
        }
        if (groups.contains(HealthGroup.SLEEP_HEART)) {
            latest("resting_hr", today, 2).ifPresent(hr ->
                    parts.add("resting HR " + whole(hr.value()) + " (" + dayWord(hr.day(), today) + ")"));
        }
        return "health: " + String.join(" · ", parts);
    }

    private record DayValue(LocalDate day, BigDecimal value) { }

    /** The total for today, or for the newest of the days before it within {@code daysBack}. */
    private java.util.Optional<DayValue> dayTotal(String kind, String column, LocalDate today, int daysBack) {
        return jdbc.query("""
                SELECT local_day, SUM(%s) AS value FROM entries
                WHERE kind = :kind AND deleted_at IS NULL AND local_day BETWEEN :from AND :today
                GROUP BY local_day ORDER BY local_day DESC LIMIT 1
                """.formatted(column), params(kind, today, daysBack),
                (rs, row) -> new DayValue(rs.getObject("local_day", LocalDate.class), rs.getBigDecimal("value")))
                .stream().findFirst();
    }

    private java.util.Optional<DayValue> latest(String kind, LocalDate today, int daysBack) {
        return jdbc.query("""
                SELECT local_day, quantity AS value FROM entries
                WHERE kind = :kind AND deleted_at IS NULL AND local_day BETWEEN :from AND :today
                ORDER BY ts DESC LIMIT 1
                """, params(kind, today, daysBack),
                (rs, row) -> new DayValue(rs.getObject("local_day", LocalDate.class), rs.getBigDecimal("value")))
                .stream().findFirst();
    }

    private java.util.Optional<String> latestWorkout(LocalDate today) {
        return jdbc.query("""
                SELECT local_day, data ->> 'minutes' AS minutes,
                       COALESCE(NULLIF(data ->> 'label', ''), data ->> 'type') AS name
                FROM entries
                WHERE kind = :kind AND deleted_at IS NULL AND local_day BETWEEN :from AND :today
                ORDER BY ts DESC LIMIT 1
                """, params("workout", today, 6), (rs, row) -> {
            StringBuilder text = new StringBuilder("last workout ")
                    .append(dayWord(rs.getObject("local_day", LocalDate.class), today));
            if (rs.getString("minutes") != null) text.append(" ").append(whole(new BigDecimal(rs.getString("minutes")))).append(" min");
            if (rs.getString("name") != null) text.append(" (").append(rs.getString("name")).append(")");
            return text.toString();
        }).stream().findFirst();
    }

    private MapSqlParameterSource params(String kind, LocalDate today, int daysBack) {
        return new MapSqlParameterSource(Map.of("kind", kinds.resolveExisting(kind).orElse(kind),
                "from", today.minusDays(daysBack), "today", today));
    }

    private static String dayWord(LocalDate day, LocalDate today) {
        if (day.equals(today)) return "today";
        if (day.equals(today.minusDays(1))) return "yesterday";
        return day.format(WEEKDAY);
    }

    private static String whole(BigDecimal value) {
        return NumberFormat.getIntegerInstance(Locale.ENGLISH).format(value.setScale(0, RoundingMode.HALF_UP));
    }

    private static String hours(BigDecimal value) {
        return value.setScale(1, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }
}
