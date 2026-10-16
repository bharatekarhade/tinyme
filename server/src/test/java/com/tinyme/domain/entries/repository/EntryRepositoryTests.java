package com.tinyme.domain.entries.repository;

import com.tinyme.domain.entries.model.add.NewEntry;
import com.tinyme.domain.entries.model.EntrySnapshot;
import com.tinyme.domain.entries.model.aggregate.TodayTotal;
import com.tinyme.domain.entries.model.aggregate.AggregateMetric;
import com.tinyme.domain.entries.model.aggregate.AggregateQuery;
import com.tinyme.domain.entries.model.aggregate.AggregateResult;
import com.tinyme.domain.entries.model.aggregate.Bucket;
import com.tinyme.domain.entries.model.aggregate.GroupBy;
import com.tinyme.domain.entries.model.aggregate.BreakdownQuery;
import com.tinyme.domain.entries.model.query.EntryQuery;
import com.tinyme.domain.entries.model.query.EntryFilters;
import com.tinyme.domain.entries.model.query.EntryQueryResult;
import com.tinyme.domain.entries.service.EntryCursorCodec;
import com.tinyme.domain.entries.service.EntryService;
import com.tinyme.api.entries.EntryController;
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
        "TINYME_OWNER_EMAIL=owner@test.tinyme.local",
        "TINYME_OWNER_PASSWORD=test-only-owner-password"
})
@Import(EntryRepositoryTests.DatabaseConfiguration.class)
class EntryRepositoryTests {
    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    EntryRepository entries;

    @Autowired
    EntryService entryService;

    @Autowired
    EntryController entryController;

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
    void updatesEntryAndReturnsPersistedSnapshot() {
        String kind = createKind();
        LocalDate originalDay = LocalDate.of(2026, 10, 8);
        UUID id = null;
        try {
            id = entries.insert(new NewEntry(kind, new BigDecimal("2"), "two coffees",
                    Map.of("type", "coffee"), List.of(), Instant.parse("2026-10-08T10:00:00Z"),
                    originalDay, "chat"));
            LocalDate updatedDay = originalDay.minusDays(1);
            Instant updatedTs = Instant.parse("2026-10-07T14:30:00Z");

            EntrySnapshot updated = entries.update(id, kind, new BigDecimal("3"), "three teas",
                    Map.of("type", "tea"), updatedTs, updatedDay);

            assertThat(updated.id()).isEqualTo(id);
            assertThat(updated.quantity()).isEqualByComparingTo("3");
            assertThat(updated.text()).isEqualTo("three teas");
            assertThat(updated.data()).containsEntry("type", "tea");
            assertThat(updated.ts()).isEqualTo(updatedTs);
            assertThat(updated.localDay()).isEqualTo(updatedDay);
            assertThat(jdbc.queryForObject("SELECT updated_at > created_at FROM entries WHERE id = ?",
                    Boolean.class, id)).isTrue();
        } finally {
            cleanup(kind, id == null ? List.of() : List.of(id));
        }
    }

    @Test
    void softDeleteIsIdempotentAndReturnsEmptyForUnknownId() {
        String kind = createKind();
        LocalDate day = LocalDate.of(2026, 10, 8);
        UUID id = null;
        try {
            id = entries.insert(new NewEntry(kind, BigDecimal.ONE, "coffee", Map.of("type", "coffee"),
                    List.of(), Instant.parse("2026-10-08T08:15:00Z"), day, "chat"));
            Instant firstDeletedAt = Instant.parse("2026-10-09T00:00:00Z");

            EntrySnapshot first = entries.softDelete(id, firstDeletedAt).orElseThrow();
            BigDecimal persistedDeletedAt = jdbc.queryForObject(
                    "SELECT EXTRACT(EPOCH FROM deleted_at)::numeric FROM entries WHERE id = ?",
                    BigDecimal.class, id);
            EntrySnapshot second = entries.softDelete(id, firstDeletedAt.plusSeconds(60)).orElseThrow();
            BigDecimal persistedAfterSecond = jdbc.queryForObject(
                    "SELECT EXTRACT(EPOCH FROM deleted_at)::numeric FROM entries WHERE id = ?",
                    BigDecimal.class, id);

            assertThat(second).isEqualTo(first);
            assertThat(persistedAfterSecond).isEqualByComparingTo(persistedDeletedAt);
            assertThat(entries.softDelete(UUID.randomUUID(), firstDeletedAt)).isEmpty();
        } finally {
            cleanup(kind, id == null ? List.of() : List.of(id));
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

            AggregateResult coffee = entries.aggregate(new AggregateQuery(List.of(kind), AggregateMetric.COUNT,
                    null, Map.of("type", "coffee"), day, day, GroupBy.NONE));
            assertThat(coffee.value()).isEqualByComparingTo("2");
            assertThat(coffee.entries()).isEqualTo(1);

            AggregateResult tea = entries.aggregate(new AggregateQuery(List.of(kind), AggregateMetric.COUNT,
                    null, Map.of("type", "tea"), day, day, GroupBy.NONE));
            assertThat(tea.value()).isEqualByComparingTo("1");
            AggregateResult noSuchType = entries.aggregate(new AggregateQuery(List.of(kind), AggregateMetric.COUNT,
                    null, Map.of("type", "matcha"), day, day, GroupBy.NONE));
            assertThat(noSuchType.value()).isEqualByComparingTo("0");

            jdbc.update("UPDATE entries SET deleted_at = now() WHERE id = ?", ids.getFirst());
            AggregateResult deleted = entries.aggregate(new AggregateQuery(List.of(kind), AggregateMetric.COUNT,
                    null, Map.of("type", "coffee"), day, day, GroupBy.NONE));
            assertThat(deleted.value()).isEqualByComparingTo("0");
            assertThat(deleted.entries()).isZero();
        } finally {
            cleanup(kind, ids);
        }
    }

    @Test
    void countingEveryKindCountsEntriesNotQuantities() {
        String kind = createKind();
        LocalDate day = LocalDate.of(2031, 3, 3);   // a day no other test logs on: "*" sees every kind
        List<UUID> ids = new ArrayList<>();
        try {
            ids.add(insert("sleep", day, "8.6", Map.of("hours", new BigDecimal("8.6"))));
            ids.add(insert(kind, day, "2", Map.of("type", "coffee")));

            AggregateResult all = entries.aggregate(new AggregateQuery(List.of("*"), AggregateMetric.COUNT,
                    null, Map.of(), day, day, GroupBy.NONE));
            assertThat(all.value()).as("a night of sleep and one coffee entry").isEqualByComparingTo("2");
            AggregateResult perDay = entries.aggregate(new AggregateQuery(List.of("*"), AggregateMetric.COUNT,
                    null, Map.of(), day, day, GroupBy.DAY));
            assertThat(perDay.buckets()).singleElement()
                    .satisfies(bucket -> assertThat(bucket.value()).isEqualByComparingTo("2"));

            AggregateResult coffee = entries.aggregate(new AggregateQuery(List.of(kind), AggregateMetric.COUNT,
                    null, Map.of(), day, day, GroupBy.NONE));
            assertThat(coffee.value()).as("one kind still adds up quantities").isEqualByComparingTo("2");
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
    void sleepAverageCombinesRowsFromTheSameNightBeforeAveraging() {
        LocalDate firstNight = LocalDate.of(2026, 10, 6);
        LocalDate secondNight = firstNight.plusDays(1);
        List<UUID> ids = new ArrayList<>();
        try {
            ids.add(insert("sleep", firstNight, "1", Map.of("hours", new BigDecimal("1.5"))));
            ids.add(insert("sleep", firstNight, "1", Map.of("hours", new BigDecimal("7.5"))));
            ids.add(insert("sleep", secondNight, "1", Map.of("hours", new BigDecimal("6"))));

            AggregateResult result = entries.aggregate(new AggregateQuery(List.of("sleep"), AggregateMetric.AVG,
                    "hours", Map.of(), firstNight, secondNight, GroupBy.NONE));

            assertThat(result.value()).isEqualByComparingTo("7.50");
            assertThat(result.entries()).isEqualTo(2);
        } finally {
            for (UUID id : ids) jdbc.update("DELETE FROM entries WHERE id = ?", id);
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

            AggregateResult days = entries.aggregate(new AggregateQuery(List.of(kind), AggregateMetric.COUNT,
                    null, Map.of(), monday, monday.plusDays(2), GroupBy.DAY));
            assertThat(days.buckets()).hasSize(3);
            assertBucket(days.buckets().get(0), monday, "1", 1);
            assertBucket(days.buckets().get(1), monday.plusDays(1), "0", 0);
            assertBucket(days.buckets().get(2), monday.plusDays(2), "2", 1);

            LocalDate nextMonday = monday.plusWeeks(1);
            ids.add(insert(kind, nextMonday.plusDays(1), "1", Map.of("type", "coffee")));
            AggregateResult weeks = entries.aggregate(new AggregateQuery(List.of(kind), AggregateMetric.COUNT,
                    null, Map.of(), monday.plusDays(1), nextMonday.plusDays(1), GroupBy.WEEK));
            assertThat(weeks.buckets()).extracting(Bucket::period)
                    .containsExactly(monday, nextMonday);
        } finally {
            cleanup(kind, ids);
        }
    }

    @Test
    void aggregatesAcrossMultipleKinds() {
        String meal = createKind();
        String drink = createKind();
        LocalDate day = LocalDate.of(2026, 10, 8);
        List<UUID> ids = new ArrayList<>();
        try {
            ids.add(insert(meal, day, "1", Map.of("kcal", 100)));
            ids.add(insert(drink, day, "1", Map.of("kcal", 55)));
            AggregateResult result = entries.aggregate(new AggregateQuery(List.of(meal, drink),
                    AggregateMetric.SUM, "kcal", Map.of(), day, day, GroupBy.NONE));

            assertThat(result.value()).isEqualByComparingTo("155");
            assertThat(result.kinds()).containsExactly(meal, drink);
            assertThat(result.unknownKinds()).isEmpty();
        } finally {
            for (UUID id : ids) jdbc.update("DELETE FROM entries WHERE id = ?", id);
            jdbc.update("DELETE FROM entry_kinds WHERE kind IN (?, ?)", meal, drink);
        }
    }

    @Test
    void breakdownReturnsTopCategoriesAndCombinesTheRest() {
        String kind = createKind();
        LocalDate day = LocalDate.of(2026, 10, 8);
        List<UUID> ids = new ArrayList<>();
        try {
            ids.add(insert(kind, day, "1", Map.of("category", "food", "amount", 100)));
            ids.add(insert(kind, day, "1", Map.of("category", "rent", "amount", 60)));
            ids.add(insert(kind, day, "1", Map.of("category", "travel", "amount", 25)));
            ids.add(insert(kind, day, "1", Map.of("amount", 15)));
            var result = entries.breakdown(new BreakdownQuery(List.of(kind), AggregateMetric.SUM,
                    "amount", Map.of(), day, day, "category", 2));

            assertThat(result.slices()).extracting("key").containsExactly("food", "rent");
            assertThat(result.slices()).extracting("value").containsExactly(new BigDecimal("100"), new BigDecimal("60"));
            assertThat(result.slices()).extracting("pct").containsExactly(new BigDecimal("50.00"), new BigDecimal("30.00"));
            assertThat(result.other().value()).isEqualByComparingTo("40");
            assertThat(result.other().pct()).isEqualByComparingTo("20.00");
            assertThat(result.total()).isEqualByComparingTo("200");
        } finally {
            cleanup(kind, ids);
        }
    }

    @Test
    void emptyBreakdownHasZeroTotalAndEmptyOther() {
        String kind = createKind();
        try {
            var result = entries.breakdown(new BreakdownQuery(List.of(kind), AggregateMetric.SUM,
                    "amount", Map.of(), LocalDate.of(2026, 10, 8), LocalDate.of(2026, 10, 8),
                    "category", 3));

            assertThat(result.slices()).isEmpty();
            assertThat(result.total()).isEqualByComparingTo("0");
            assertThat(result.other().value()).isEqualByComparingTo("0");
            assertThat(result.other().pct()).isEqualByComparingTo("0");
        } finally {
            cleanup(kind, List.of());
        }
    }

    @Test
    void findsLatestLiveEntryAcrossRequestedKinds() {
        String first = createKind();
        String second = createKind();
        LocalDate day = LocalDate.of(2026, 10, 8);
        List<UUID> ids = new ArrayList<>();
        try {
            ids.add(insertQueryEntry(first, day, "2026-10-08T08:00:00Z", "first", Map.of()));
            ids.add(insertQueryEntry(second, day, "2026-10-08T09:00:00Z", "latest", Map.of()));
            assertThat(entries.findLatest(List.of(first, second))).get()
                    .extracting(EntrySnapshot::text).isEqualTo("latest");
            jdbc.update("UPDATE entries SET deleted_at = now() WHERE id = ?", ids.get(1));
            assertThat(entries.findLatest(List.of(first, second))).get()
                    .extracting(EntrySnapshot::text).isEqualTo("first");
        } finally {
            for (UUID id : ids) jdbc.update("DELETE FROM entries WHERE id = ?", id);
            jdbc.update("DELETE FROM entry_kinds WHERE kind IN (?, ?)", first, second);
        }
    }

    @Test
    void findsNewestEntriesAppliesFiltersAndReportsTruncation() {
        String kind = createKind();
        LocalDate day = LocalDate.of(2026, 10, 8);
        List<UUID> ids = new ArrayList<>();
        try {
            ids.add(insertQueryEntry(kind, day, "2026-10-08T08:00:00Z", "Had a coffee", Map.of("type", "coffee")));
            ids.add(insertQueryEntry(kind, day, "2026-10-08T23:30:00Z", "Had a beer", Map.of("type", "beer")));
            ids.add(insertQueryEntry(kind, day.plusDays(1), "2026-10-09T09:00:00Z", "Had another beer", Map.of("type", "beer")));
            jdbc.update("UPDATE entries SET deleted_at = now() WHERE id = ?", ids.get(2));

            EntryQueryResult beer = entries.find(new EntryQuery(kind, "beer", Map.of("type", "beer"),
                    day, day, 1));
            assertThat(beer.kindKnown()).isTrue();
            assertThat(beer.entries()).hasSize(1);
            assertThat(beer.entries().getFirst().id()).isEqualTo(ids.get(1));
            assertThat(beer.entries().getFirst().ts()).isEqualTo(Instant.parse("2026-10-08T23:30:00Z"));
            assertThat(beer.truncated()).isFalse();
            var apiBeer = entryController.list(List.of(kind), day, day, "beer", "{\"type\":\"beer\"}", null, 50);
            assertThat(apiBeer.entries()).extracting(EntryController.EntryResponse::id)
                    .containsExactly(ids.get(1).toString());

            EntryQueryResult all = entries.find(new EntryQuery(kind, null, Map.of(), null, null, 1));
            assertThat(all.entries()).extracting(EntrySnapshot::id).containsExactly(ids.get(1));
            assertThat(all.truncated()).isTrue();
        } finally {
            cleanup(kind, ids);
        }
    }

    @Test
    void pagesThroughOneHundredTwentyEntriesWithoutDuplicates() {
        String kind = createKind();
        LocalDate day = LocalDate.of(2026, 10, 9);
        List<UUID> ids = new ArrayList<>();
        try {
            Instant start = Instant.parse("2026-10-09T00:00:00Z");
            for (int i = 0; i < 120; i++) {
                Map<String, Object> data = i % 10 == 0 ? Map.of("type", "tea") : Map.of("type", "coffee");
                ids.add(insertQueryEntry(kind, day, start.plusSeconds(i).toString(), "page " + i, data));
            }

            var first = entryService.page(new EntryFilters(List.of(kind), null, Map.of(), day, day, null), 50, null);
            var second = entryService.page(new EntryFilters(List.of(kind), null, Map.of(), day, day, null), 50,
                    EntryCursorCodec.decode(first.nextCursor()));
            var third = entryService.page(new EntryFilters(List.of(kind), null, Map.of(), day, day, null), 50,
                    EntryCursorCodec.decode(second.nextCursor()));

            assertThat(first.entries()).hasSize(50);
            assertThat(second.entries()).hasSize(50);
            assertThat(third.entries()).hasSize(20);
            var all = new ArrayList<EntrySnapshot>();
            all.addAll(first.entries());
            all.addAll(second.entries());
            all.addAll(third.entries());
            assertThat(all).extracting(EntrySnapshot::id).doesNotHaveDuplicates().hasSize(120);
            assertThat(first.entries().getFirst().ts()).isAfter(first.entries().getLast().ts());
            assertThat(second.nextCursor()).isNotNull();
            assertThat(third.nextCursor()).isNull();

            EntryQuery filter = new EntryQuery(kind, null, Map.of("type", "tea"), day, day, 20);
            var queryRows = entries.find(filter).entries();
            var pageRows = entryService.page(filter.filters(), 100, null).entries();
            assertThat(pageRows).extracting(EntrySnapshot::id)
                    .containsExactlyElementsOf(queryRows.stream().map(EntrySnapshot::id).toList());
        } finally {
            cleanup(kind, ids);
        }
    }

    @Test
    void pagesAcrossMultipleResolvedKindsAndReportsUnknownKinds() {
        String meal = createKind();
        String drink = createKind();
        String oldDrink = "old_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String missing = "missing_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        LocalDate day = LocalDate.of(2026, 10, 9);
        List<UUID> ids = new ArrayList<>();
        try {
            jdbc.update("INSERT INTO entry_kinds (kind, merged_into) VALUES (?, ?)", oldDrink, drink);
            Instant start = Instant.parse("2026-10-09T00:00:00Z");
            for (int i = 0; i < 120; i++) {
                String kind = i % 2 == 0 ? meal : drink;
                ids.add(insertQueryEntry(kind, day, start.plusSeconds(i).toString(), "entry " + i, Map.of()));
            }

            EntryFilters filters = new EntryFilters(List.of(meal, oldDrink, missing), null, Map.of(), day, day, null);
            var first = entryService.page(filters, 50, null);
            var second = entryService.page(filters, 50, EntryCursorCodec.decode(first.nextCursor()));
            var third = entryService.page(filters, 50, EntryCursorCodec.decode(second.nextCursor()));

            assertThat(first.unknownKinds()).containsExactly(missing);
            assertThat(first.entries()).hasSize(50);
            assertThat(second.entries()).hasSize(50);
            assertThat(third.entries()).hasSize(20);
            var all = new ArrayList<EntrySnapshot>();
            all.addAll(first.entries());
            all.addAll(second.entries());
            all.addAll(third.entries());
            assertThat(all).extracting(EntrySnapshot::id).doesNotHaveDuplicates().hasSize(120);
            assertThat(all).extracting(EntrySnapshot::kind).contains(meal, drink);

            var apiPage = entryController.list(List.of(meal, oldDrink, missing), day, day,
                    null, null, null, 100);
            assertThat(apiPage.unknownKinds()).containsExactly(missing);
            assertThat(apiPage.entries()).hasSize(100);

            var unknownOnly = entryService.page(new EntryFilters(List.of(missing), null, Map.of(), day, day, null),
                    50, null);
            assertThat(unknownOnly.unknownKinds()).containsExactly(missing);
            assertThat(unknownOnly.entries()).isEmpty();
        } finally {
            for (UUID id : ids) jdbc.update("DELETE FROM entries WHERE id = ?", id);
            jdbc.update("DELETE FROM entry_kinds WHERE kind = ?", oldDrink);
            jdbc.update("DELETE FROM entry_kinds WHERE kind IN (?, ?)", meal, drink);
        }
    }

    @Test
    void numericFieldFilterExcludesStringAndMissingValues() {
        String kind = createKind();
        LocalDate day = LocalDate.of(2026, 10, 9);
        List<UUID> ids = new ArrayList<>();
        try {
            ids.add(insertQueryEntry(kind, day, "2026-10-09T08:00:00Z", "numeric", Map.of("amount", 980)));
            ids.add(insertQueryEntry(kind, day, "2026-10-09T09:00:00Z", "string", Map.of("amount", "500")));
            ids.add(insertQueryEntry(kind, day, "2026-10-09T10:00:00Z", "missing", Map.of()));

            var page = entryService.page(new EntryFilters(List.of(kind), null, Map.of(), day, day, "amount"),
                    20, null);

            assertThat(page.entries()).extracting(EntrySnapshot::id).containsExactly(ids.getFirst());
        } finally {
            cleanup(kind, ids);
        }
    }

    @Test
    void recentCanonicalKindsCountsLiveEntriesAndReportsLatestLocalDay() {
        String kind = createKind();
        String staleKind = "stale_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        LocalDate from = LocalDate.of(2026, 7, 14);
        LocalDate to = LocalDate.of(2026, 10, 11);
        List<UUID> ids = new ArrayList<>();
        try {
            jdbc.update("INSERT INTO entry_kinds (kind) VALUES (?)", staleKind);
            jdbc.update("UPDATE entry_kinds SET merged_into = ? WHERE kind = ?", kind, staleKind);
            ids.add(insert(kind, LocalDate.of(2026, 10, 8), "1", Map.of()));
            ids.add(insert(kind, LocalDate.of(2026, 10, 10), "1", Map.of()));
            ids.add(insert(staleKind, LocalDate.of(2026, 10, 10), "1", Map.of()));
            ids.add(insert(kind, from.minusDays(1), "1", Map.of()));
            jdbc.update("UPDATE entries SET deleted_at = now() WHERE id = ?", ids.get(2));

            assertThat(entries.recentCanonicalKinds(from, to, 20))
                    .containsExactly(new com.tinyme.domain.entries.model.RecentKind(kind, 2,
                            LocalDate.of(2026, 10, 10)));
        } finally {
            for (UUID id : ids) jdbc.update("DELETE FROM entries WHERE id = ?", id);
            jdbc.update("UPDATE entry_kinds SET merged_into = NULL WHERE kind = ?", staleKind);
            jdbc.update("DELETE FROM entry_kinds WHERE kind = ?", staleKind);
            jdbc.update("DELETE FROM entry_kinds WHERE kind = ?", kind);
        }
    }

    @Test
    void apiReturnsFullJournalTextWhileToolQueryTruncatesIt() {
        String kind = createKind();
        LocalDate day = LocalDate.of(2026, 10, 9);
        List<UUID> ids = new ArrayList<>();
        try {
            String fullText = "j".repeat(1000);
            ids.add(insertQueryEntry(kind, day, "2026-10-09T12:00:00Z", fullText, Map.of()));
            var apiPage = entryController.list(List.of(kind), day, day, null, null, null, 50);
            var toolPage = entries.find(new EntryQuery(kind, null, Map.of(), day, day, 10));

            assertThat(apiPage.entries().getFirst().text()).hasSize(1000);
            assertThat(toolPage.entries().getFirst().text()).hasSize(280);
        } finally {
            cleanup(kind, ids);
        }
    }

    @Test
    void textSearchFindsFullTextAndTrigramMatchesAndTruncatesLongText() {
        String kind = createKind();
        LocalDate day = LocalDate.of(2026, 10, 8);
        List<UUID> ids = new ArrayList<>();
        try {
            String longText = "climbing ".repeat(50);
            ids.add(insertQueryEntry(kind, day, "2026-10-08T10:00:00Z", longText, Map.of()));

            EntryQueryResult fullText = entries.find(new EntryQuery(kind, "climbing", Map.of(), day, day, 10));
            assertThat(fullText.entries()).hasSize(1);
            assertThat(fullText.entries().getFirst().text()).hasSize(280);

            EntryQueryResult typo = entries.find(new EntryQuery(kind, "climbng", Map.of(), day, day, 10));
            assertThat(typo.entries()).hasSize(1);

            EntryQueryResult inclusiveDay = entries.find(new EntryQuery(kind, null, Map.of(), day, day, 10));
            assertThat(inclusiveDay.entries()).hasSize(1);
        } finally {
            cleanup(kind, ids);
        }
    }

    private UUID insertQueryEntry(String kind, LocalDate day, String ts, String text,
                                  Map<String, Object> data) {
        return entries.insert(new NewEntry(kind, BigDecimal.ONE, text, data, List.of(),
                Instant.parse(ts), day, "chat"));
    }

    private AggregateResult aggregate(String kind, AggregateMetric metric, String field,
                                      LocalDate from, LocalDate to) {
        return entries.aggregate(new AggregateQuery(List.of(kind), metric, field, Map.of(), from, to, GroupBy.NONE));
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
