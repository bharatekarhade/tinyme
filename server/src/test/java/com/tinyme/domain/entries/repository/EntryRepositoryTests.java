package com.tinyme.domain.entries.repository;

import com.tinyme.domain.entries.model.add.NewEntry;
import com.tinyme.domain.entries.model.aggregate.TodayTotal;
import com.tinyme.domain.entries.model.aggregate.AggregateMetric;
import com.tinyme.domain.entries.model.aggregate.AggregateQuery;
import com.tinyme.domain.entries.model.aggregate.AggregateResult;
import com.tinyme.domain.entries.model.aggregate.Bucket;
import com.tinyme.domain.entries.model.aggregate.GroupBy;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "tinyme.agent.setup-enabled=false",
        "tinyme.tools.allow-missing-handlers=true"
})
@Import(EntryRepositoryTests.DatabaseConfiguration.class)
class EntryRepositoryTests {
    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    EntryRepository entries;

    @Test
    void insertsChatEntryAndSumsLiveQuantitiesByOptionalType() {
        String kind = "repo_drink_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        LocalDate day = LocalDate.of(2026, 10, 6);
        UUID coffeeId = null;
        UUID teaId = null;
        UUID untypedId = null;
        try {
            jdbc.update("INSERT INTO entry_kinds (kind) VALUES (?)", kind);
            coffeeId = entries.insert(new NewEntry(kind, new BigDecimal("2"), "two coffees",
                    Map.of("type", "coffee"), List.of("morning"), Instant.parse("2026-10-06T00:30:00Z"), day, "chat"));
            teaId = entries.insert(new NewEntry(kind, new BigDecimal("3"), "three teas",
                    Map.of("type", "tea"), List.of(), Instant.parse("2026-10-06T02:30:00Z"), day, "chat"));
            untypedId = entries.insert(new NewEntry(kind, new BigDecimal("4"), "four servings",
                    Map.of(), List.of(), Instant.parse("2026-10-06T04:30:00Z"), day, "chat"));

            assertThat(coffeeId).isNotNull();
            assertThat(coffeeId.version()).isEqualTo(7);
            assertThat(jdbc.queryForObject(
                    "SELECT source FROM entries WHERE id = ?", String.class, coffeeId))
                    .isEqualTo("chat");
            assertThat(jdbc.queryForObject(
                    "SELECT tags[1] FROM entries WHERE id = ?", String.class, coffeeId))
                    .isEqualTo("morning");

            assertThat(entries.totalForDay(kind, day, null)).isEqualByComparingTo("9");
            assertThat(entries.totalForDay(kind, day, "coffee")).isEqualByComparingTo("2");
            assertThat(entries.totalForDay(kind, day, "tea")).isEqualByComparingTo("3");
            assertThat(entries.todayTotals(day)).containsExactly(
                    new TodayTotal(kind, null, new BigDecimal("4")),
                    new TodayTotal(kind, "coffee", new BigDecimal("2")),
                    new TodayTotal(kind, "tea", new BigDecimal("3")));

            jdbc.update("UPDATE entries SET deleted_at = now() WHERE id = ?", coffeeId);
            assertThat(entries.totalForDay(kind, day, "coffee")).isEqualByComparingTo("0");
            assertThat(entries.totalForDay(kind, day, null)).isEqualByComparingTo("7");
            assertThat(entries.todayTotals(day)).containsExactly(
                    new TodayTotal(kind, null, new BigDecimal("4")),
                    new TodayTotal(kind, "tea", new BigDecimal("3")));
        } finally {
            if (coffeeId != null) jdbc.update("DELETE FROM entries WHERE id = ?", coffeeId);
            if (teaId != null) jdbc.update("DELETE FROM entries WHERE id = ?", teaId);
            if (untypedId != null) jdbc.update("DELETE FROM entries WHERE id = ?", untypedId);
            jdbc.update("DELETE FROM entry_kinds WHERE kind = ?", kind);
        }
    }

    @Test
    void aggregateCountUsesQuantityWhereAndExcludesSoftDeletedRows() {
        String kind = createKind();
        LocalDate day = LocalDate.of(2026, 10, 8);
        List<UUID> ids = new ArrayList<>();
        try {
            ids.add(insert(kind, day, "2", Map.of("type", "coffee")));
            ids.add(insert(kind, day, "1", Map.of("type", "tea")));

            AggregateResult coffee = entries.aggregate(new AggregateQuery(kind, AggregateMetric.COUNT,
                    null, Map.of("type", "coffee"), day, day, GroupBy.NONE));
            assertThat(coffee.value()).isEqualByComparingTo("2");
            assertThat(coffee.entries()).isEqualTo(1);

            AggregateResult tea = entries.aggregate(new AggregateQuery(kind, AggregateMetric.COUNT,
                    null, Map.of("type", "tea"), day, day, GroupBy.NONE));
            assertThat(tea.value()).isEqualByComparingTo("1");
            AggregateResult noSuchType = entries.aggregate(new AggregateQuery(kind, AggregateMetric.COUNT,
                    null, Map.of("type", "matcha"), day, day, GroupBy.NONE));
            assertThat(noSuchType.value()).isEqualByComparingTo("0");

            jdbc.update("UPDATE entries SET deleted_at = now() WHERE id = ?", ids.getFirst());
            AggregateResult deleted = entries.aggregate(new AggregateQuery(kind, AggregateMetric.COUNT,
                    null, Map.of("type", "coffee"), day, day, GroupBy.NONE));
            assertThat(deleted.value()).isEqualByComparingTo("0");
            assertThat(deleted.entries()).isZero();
        } finally {
            cleanup(kind, ids);
        }
    }

    @Test
    void numericMetricsSkipMissingAndStringFields() {
        String kind = createKind();
        LocalDate day = LocalDate.of(2026, 10, 8);
        List<UUID> ids = new ArrayList<>();
        try {
            ids.add(insert(kind, day, "1", Map.of("hours", new BigDecimal("6.5"))));
            ids.add(insert(kind, day, "1", Map.of("hours", new BigDecimal("7.5"))));
            ids.add(insert(kind, day, "1", Map.of("hours", "7")));
            ids.add(insert(kind, day, "1", Map.of("hours", "seven")));
            ids.add(insert(kind, day, "1", Map.of("type", "sleep")));

            AggregateResult avg = aggregate(kind, AggregateMetric.AVG, "hours", day, day);
            assertThat(avg.value()).isEqualByComparingTo("7.00");
            assertThat(avg.entries()).isEqualTo(2);
            assertThat(aggregate(kind, AggregateMetric.MIN, "hours", day, day).value())
                    .isEqualByComparingTo("6.5");
            assertThat(aggregate(kind, AggregateMetric.MAX, "hours", day, day).value())
                    .isEqualByComparingTo("7.5");
            assertThat(aggregate(kind, AggregateMetric.SUM, "hours", day, day).value())
                    .isEqualByComparingTo("14.0");

            AggregateResult noMatches = aggregate(kind, AggregateMetric.AVG, "km", day, day);
            assertThat(noMatches.value()).isNull();
            assertThat(noMatches.entries()).isZero();
            assertThat(aggregate(kind, AggregateMetric.SUM, "km", day, day).value())
                    .isEqualByComparingTo("0");
            assertThat(aggregate(kind, AggregateMetric.COUNT, null, day, day).value())
                    .isEqualByComparingTo("5");
        } finally {
            cleanup(kind, ids);
        }
    }

    @Test
    void groupedDaysAreZeroFilledAndWeeksAreLabeledByMonday() {
        String kind = createKind();
        LocalDate monday = LocalDate.of(2026, 10, 5);
        List<UUID> ids = new ArrayList<>();
        try {
            ids.add(insert(kind, monday, "1", Map.of("type", "coffee")));
            ids.add(insert(kind, monday.plusDays(2), "2", Map.of("type", "coffee")));

            AggregateResult days = entries.aggregate(new AggregateQuery(kind, AggregateMetric.COUNT,
                    null, Map.of(), monday, monday.plusDays(2), GroupBy.DAY));
            assertThat(days.buckets()).hasSize(3);
            assertBucket(days.buckets().get(0), monday, "1", 1);
            assertBucket(days.buckets().get(1), monday.plusDays(1), "0", 0);
            assertBucket(days.buckets().get(2), monday.plusDays(2), "2", 1);

            LocalDate nextMonday = monday.plusWeeks(1);
            ids.add(insert(kind, nextMonday.plusDays(1), "1", Map.of("type", "coffee")));
            AggregateResult weeks = entries.aggregate(new AggregateQuery(kind, AggregateMetric.COUNT,
                    null, Map.of(), monday.plusDays(1), nextMonday.plusDays(1), GroupBy.WEEK));
            assertThat(weeks.buckets()).extracting(Bucket::period)
                    .containsExactly(monday, nextMonday);
        } finally {
            cleanup(kind, ids);
        }
    }

    private AggregateResult aggregate(String kind, AggregateMetric metric, String field,
                                      LocalDate from, LocalDate to) {
        return entries.aggregate(new AggregateQuery(kind, metric, field, Map.of(), from, to, GroupBy.NONE));
    }

    private UUID insert(String kind, LocalDate day, String quantity, Map<String, Object> data) {
        return entries.insert(new NewEntry(kind, new BigDecimal(quantity), null, data, List.of(),
                day.atStartOfDay().toInstant(ZoneOffset.UTC).plus(1, ChronoUnit.HOURS), day, "chat"));
    }

    private String createKind() {
        String kind = "agg_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        jdbc.update("INSERT INTO entry_kinds (kind) VALUES (?)", kind);
        return kind;
    }

    private void cleanup(String kind, List<UUID> ids) {
        for (UUID id : ids) jdbc.update("DELETE FROM entries WHERE id = ?", id);
        jdbc.update("DELETE FROM entry_kinds WHERE kind = ?", kind);
    }

    private static void assertBucket(Bucket bucket, LocalDate date, String value, long entryCount) {
        assertThat(bucket.period()).isEqualTo(date);
        assertThat(bucket.value()).isEqualByComparingTo(value);
        assertThat(bucket.entries()).isEqualTo(entryCount);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class DatabaseConfiguration {
        @Bean
        @ServiceConnection
        PostgreSQLContainer postgres() {
            return new PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:pg18")
                    .asCompatibleSubstituteFor("postgres"));
        }
    }
}
