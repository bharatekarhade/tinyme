package com.tinyme.domain.entries.service;

import com.tinyme.domain.entries.model.add.AddCommand;
import com.tinyme.domain.entries.model.add.AddResult;
import com.tinyme.domain.entries.model.EntryWriteResult;
import com.tinyme.domain.entries.model.aggregate.AggregateMetric;
import com.tinyme.domain.entries.model.aggregate.AggregateQuery;
import com.tinyme.domain.entries.model.aggregate.GroupBy;
import com.tinyme.domain.entries.model.query.EntryQuery;
import com.tinyme.domain.entries.model.update.EntryPatch;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "tinyme.agent.setup-enabled=false"
})
@Import(EntryServiceTests.DatabaseConfiguration.class)
class EntryServiceTests {
    private static final ZoneId TOKYO = ZoneId.of("Asia/Tokyo");
    private static final Instant TS = Instant.parse("2026-10-06T00:30:00Z");

    @Autowired
    EntryService service;

    @Autowired
    JdbcTemplate jdbc;

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM entries WHERE kind IN ('drink', 'beverage', 'piano_practice')");
        jdbc.update("UPDATE entry_kinds SET merged_into = NULL WHERE kind IN ('drink', 'beverage', 'piano_practice')");
        jdbc.update("DELETE FROM entry_kinds WHERE kind IN ('drink', 'beverage', 'piano_practice')");
    }

    @Test
    void firstCoffeeTotalsOneAndSecondTotalsTwo() {
        AddResult first = service.add(drink("coffee", null));
        AddResult second = service.add(drink("coffee", null));

        assertThat(first.id()).isNotNull();
        assertThat(second.id()).isNotEqualTo(first.id());
        assertThat(first.kind()).isEqualTo("drink");
        assertThat(first.localDay()).isEqualTo(LocalDate.of(2026, 10, 6));
        assertThat(first.quantity()).isEqualByComparingTo("1");
        assertThat(first.todayTotal()).isEqualByComparingTo("1");
        assertThat(second.todayTotal()).isEqualByComparingTo("2");
        assertThat(jdbc.queryForObject("SELECT use_count FROM entry_kinds WHERE kind = 'drink'", Integer.class))
                .isEqualTo(2);
    }

    @Test
    void quantityTwoTotalsTwo() {
        AddResult result = service.add(drink("coffee", new BigDecimal("2")));
        assertThat(result.quantity()).isEqualByComparingTo("2");
        assertThat(result.todayTotal()).isEqualByComparingTo("2");
    }

    @Test
    void coffeeAndTeaHaveSeparateTotals() {
        service.add(drink("coffee", new BigDecimal("2")));
        AddResult tea = service.add(drink("tea", null));
        AddResult coffee = service.add(drink("coffee", null));
        assertThat(tea.todayTotal()).isEqualByComparingTo("1");
        assertThat(coffee.todayTotal()).isEqualByComparingTo("3");
    }

    @Test
    void createsNewKind() {
        AddResult result = service.add(new AddCommand("piano_practice", null, null, null, null, TS, TOKYO, "chat"));
        assertThat(result.kind()).isEqualTo("piano_practice");
        assertThat(result.todayTotal()).isEqualByComparingTo("1");
        assertThat(jdbc.queryForObject(
                "SELECT use_count FROM entry_kinds WHERE kind = 'piano_practice'", Integer.class)).isEqualTo(1);
    }

    @Test
    void savesMergedBeverageAsDrink() {
        jdbc.update("INSERT INTO entry_kinds (kind) VALUES ('drink')");
        jdbc.update("INSERT INTO entry_kinds (kind, merged_into) VALUES ('beverage', 'drink')");

        AddResult result = service.add(new AddCommand("beverage", null, "coffee", Map.of("type", "coffee"),
                List.of(), TS, TOKYO, "chat"));

        assertThat(result.kind()).isEqualTo("drink");
        assertThat(result.todayTotal()).isEqualByComparingTo("1");
        assertThat(jdbc.queryForObject("SELECT kind FROM entries WHERE id = ?", String.class, result.id()))
                .isEqualTo("drink");
        assertThat(jdbc.queryForObject("SELECT use_count FROM entry_kinds WHERE kind = 'beverage'", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT use_count FROM entry_kinds WHERE kind = 'drink'", Integer.class))
                .isZero();
    }

    @Test
    void excludesSoftDeletedEntriesFromTotal() {
        AddResult first = service.add(drink("coffee", null));
        jdbc.update("UPDATE entries SET deleted_at = now() WHERE id = ?", first.id());
        AddResult second = service.add(drink("coffee", null));
        assertThat(second.todayTotal()).isEqualByComparingTo("1");
    }

    @Test
    void usesEventDayInTokyoForLateNightAndAcrossUtcMidnight() {
        LocalDate yesterday = LocalDate.now(TOKYO).minusDays(1);
        Instant lateNight = yesterday.atTime(23, 30).atZone(TOKYO).toInstant();
        AddResult result = service.add(new AddCommand("drink", null, null, Map.of("type", "coffee"),
                List.of(), lateNight, TOKYO, "chat"));
        assertThat(result.localDay()).isEqualTo(yesterday);
        assertThat(jdbc.queryForObject("SELECT local_day::text FROM entries WHERE id = ?", String.class, result.id()))
                .isEqualTo(yesterday.toString());

        // Tokyo's early morning belongs to the preceding date in UTC.
        Instant earlyMorning = yesterday.atTime(0, 30).atZone(TOKYO).toInstant();
        AddResult morning = service.add(new AddCommand("drink", null, null, Map.of("type", "coffee"),
                List.of(), earlyMorning, TOKYO, "chat"));
        assertThat(morning.localDay()).isEqualTo(yesterday);
        assertThat(morning.todayTotal()).isEqualByComparingTo("2");
        AddResult today = service.add(new AddCommand("drink", null, null, Map.of("type", "coffee"),
                List.of(), yesterday.plusDays(1).atStartOfDay(TOKYO).toInstant(), TOKYO, "chat"));
        assertThat(today.todayTotal()).isEqualByComparingTo("1");
    }

    @Test
    void persistsSuppliedSourceTextDataAndTags() {
        AddResult result = service.add(new AddCommand("drink", null, "morning coffee", Map.of("type", "coffee"),
                List.of("morning"), TS, TOKYO, "app"));
        var row = jdbc.queryForMap("SELECT source, text, data->>'type' AS type, tags[1] AS tag FROM entries WHERE id = ?",
                result.id());
        assertThat(row).containsEntry("source", "app").containsEntry("text", "morning coffee")
                .containsEntry("type", "coffee").containsEntry("tag", "morning");
    }

    @Test
    void nonStringTypeUsesWholeKindTotal() {
        service.add(drink("coffee", null));
        AddResult result = service.add(new AddCommand("drink", null, null, Map.of("type", 42),
                List.of(), TS, TOKYO, "chat"));
        assertThat(result.todayTotal()).isEqualByComparingTo("2");
    }

    @Test
    void aggregateFollowsMergedKindWithoutIncrementingUsage() {
        jdbc.update("INSERT INTO entry_kinds (kind) VALUES ('drink')");
        jdbc.update("INSERT INTO entry_kinds (kind, merged_into) VALUES ('beverage', 'drink')");
        service.add(drink("coffee", new BigDecimal("2")));
        int drinkUseCount = jdbc.queryForObject(
                "SELECT use_count FROM entry_kinds WHERE kind = 'drink'", Integer.class);
        int beverageUseCount = jdbc.queryForObject(
                "SELECT use_count FROM entry_kinds WHERE kind = 'beverage'", Integer.class);

        var result = service.aggregate(new AggregateQuery("beverage", AggregateMetric.COUNT, null,
                Map.of("type", "coffee"), LocalDate.of(2026, 10, 6), LocalDate.of(2026, 10, 6), GroupBy.NONE));

        assertThat(result.kind()).isEqualTo("drink");
        assertThat(result.kindKnown()).isTrue();
        assertThat(result.value()).isEqualByComparingTo("2");
        assertThat(jdbc.queryForObject(
                "SELECT use_count FROM entry_kinds WHERE kind = 'drink'", Integer.class)).isEqualTo(drinkUseCount);
        assertThat(jdbc.queryForObject(
                "SELECT use_count FROM entry_kinds WHERE kind = 'beverage'", Integer.class)).isEqualTo(beverageUseCount);
    }

    @Test
    void unknownKindReturnsEmptyAggregateInsteadOfCreatingKind() {
        var result = service.aggregate(new AggregateQuery("unknown_kind", AggregateMetric.COUNT,
                null, Map.of(), null, null, GroupBy.NONE));

        assertThat(result.kindKnown()).isFalse();
        assertThat(result.kind()).isEqualTo("unknown_kind");
        assertThat(result.value()).isEqualByComparingTo("0");
        assertThat(result.entries()).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM entry_kinds WHERE kind = 'unknown_kind'", Integer.class)).isZero();
    }

    @Test
    void queryFollowsMergedKindWithoutIncrementingUsage() {
        jdbc.update("INSERT INTO entry_kinds (kind) VALUES ('drink')");
        jdbc.update("INSERT INTO entry_kinds (kind, merged_into) VALUES ('beverage', 'drink')");
        service.add(drink("coffee", null));
        int drinkUseCount = jdbc.queryForObject(
                "SELECT use_count FROM entry_kinds WHERE kind = 'drink'", Integer.class);
        int beverageUseCount = jdbc.queryForObject(
                "SELECT use_count FROM entry_kinds WHERE kind = 'beverage'", Integer.class);

        var result = service.query(new EntryQuery("beverage", null, Map.of("type", "coffee"),
                LocalDate.of(2026, 10, 6), LocalDate.of(2026, 10, 6), null));

        assertThat(result.kindKnown()).isTrue();
        assertThat(result.entries()).hasSize(1);
        assertThat(result.entries().getFirst().kind()).isEqualTo("drink");
        assertThat(result.truncated()).isFalse();
        assertThat(jdbc.queryForObject(
                "SELECT use_count FROM entry_kinds WHERE kind = 'drink'", Integer.class)).isEqualTo(drinkUseCount);
        assertThat(jdbc.queryForObject(
                "SELECT use_count FROM entry_kinds WHERE kind = 'beverage'", Integer.class)).isEqualTo(beverageUseCount);
    }

    @Test
    void unknownQueryKindReturnsNoEntriesWithoutCreatingIt() {
        var result = service.query(new EntryQuery("unknown_kind", null, Map.of(), null, null, null));

        assertThat(result.kindKnown()).isFalse();
        assertThat(result.entries()).isEmpty();
        assertThat(result.truncated()).isFalse();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM entry_kinds WHERE kind = 'unknown_kind'", Integer.class)).isZero();
    }

    @Test
    void failedInsertRollsBackKindCreation() {
        assertThatThrownBy(() -> service.add(new AddCommand("piano_practice", null, null, Map.of(),
                List.of(), TS, TOKYO, "invalid"))).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM entry_kinds WHERE kind = 'piano_practice'", Integer.class)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM entries WHERE kind = 'piano_practice'", Integer.class)).isZero();
    }

    @Test
    void updateQuantityReturnsNewDayTotalAndKeepsOneRow() {
        AddResult added = service.add(drink("coffee", new BigDecimal("2")));

        EntryWriteResult updated = service.update(new EntryPatch(added.id(), null,
                new BigDecimal("3"), null, null, null), TOKYO).orElseThrow();

        assertThat(updated.entry().quantity()).isEqualByComparingTo("3");
        assertThat(updated.dayTotal()).isEqualByComparingTo("3");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM entries WHERE id = ?", Integer.class, added.id()))
                .isEqualTo(1);
    }

    @Test
    void updateDataMovesTotalFromCoffeeToTea() {
        AddResult added = service.add(drink("coffee", null));

        EntryWriteResult updated = service.update(new EntryPatch(added.id(), null,
                null, null, Map.of("type", "tea"), null), TOKYO).orElseThrow();

        assertThat(updated.entry().data()).containsEntry("type", "tea");
        assertThat(updated.dayTotal()).isEqualByComparingTo("1");
        assertThat(jdbc.queryForObject("SELECT COALESCE(SUM(quantity), 0) FROM entries "
                + "WHERE kind = 'drink' AND local_day = ? AND data->>'type' = 'coffee' AND deleted_at IS NULL",
                BigDecimal.class, added.localDay())).isEqualByComparingTo("0");
        assertThat(jdbc.queryForObject("SELECT COALESCE(SUM(quantity), 0) FROM entries "
                + "WHERE kind = 'drink' AND local_day = ? AND data->>'type' = 'tea' AND deleted_at IS NULL",
                BigDecimal.class, added.localDay())).isEqualByComparingTo("1");
    }

    @Test
    void updateTimestampRecalculatesLocalDayInContextZone() {
        AddResult added = service.add(drink("coffee", null));
        LocalDate yesterday = LocalDate.of(2026, 10, 5);
        Instant lastNight = yesterday.atTime(23, 30).atZone(TOKYO).toInstant();

        EntryWriteResult updated = service.update(new EntryPatch(added.id(), null,
                null, null, null, lastNight), TOKYO).orElseThrow();

        assertThat(updated.entry().ts()).isEqualTo(lastNight);
        assertThat(updated.entry().localDay()).isEqualTo(yesterday);
        assertThat(jdbc.queryForObject("SELECT local_day::text FROM entries WHERE id = ?", String.class, added.id()))
                .isEqualTo(yesterday.toString());
    }

    @Test
    void updateBlankTextStoresNull() {
        AddResult added = service.add(new AddCommand("drink", null, "original text", Map.of("type", "coffee"),
                List.of(), TS, TOKYO, "chat"));

        EntryWriteResult updated = service.update(new EntryPatch(added.id(), null,
                null, "", null, null), TOKYO).orElseThrow();

        assertThat(updated.entry().text()).isNull();
        assertThat(jdbc.queryForObject("SELECT text FROM entries WHERE id = ?", String.class, added.id())).isNull();
    }

    @Test
    void updateResolvesMergedKindToCanonicalKind() {
        jdbc.update("INSERT INTO entry_kinds (kind) VALUES ('drink')");
        jdbc.update("INSERT INTO entry_kinds (kind, merged_into) VALUES ('beverage', 'drink')");
        AddResult added = service.add(drink("coffee", null));

        EntryWriteResult updated = service.update(new EntryPatch(added.id(), "beverage",
                null, null, null, null), TOKYO).orElseThrow();

        assertThat(updated.entry().kind()).isEqualTo("drink");
        assertThat(jdbc.queryForObject("SELECT kind FROM entries WHERE id = ?", String.class, added.id()))
                .isEqualTo("drink");
        assertThat(jdbc.queryForObject("SELECT use_count FROM entry_kinds WHERE kind = 'beverage'", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void updateOfSoftDeletedEntryReturnsEmptyWithoutCreatingKindOrIncrementingUseCount() {
        AddResult added = service.add(drink("coffee", null));
        jdbc.update("UPDATE entries SET deleted_at = now() WHERE id = ?", added.id());
        int useCountBefore = jdbc.queryForObject("SELECT use_count FROM entry_kinds WHERE kind = 'drink'", Integer.class);

        var updated = service.update(new EntryPatch(added.id(), "piano_practice",
                null, null, null, null), TOKYO);

        assertThat(updated).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM entry_kinds WHERE kind = 'piano_practice'", Integer.class))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT use_count FROM entry_kinds WHERE kind = 'drink'", Integer.class))
                .isEqualTo(useCountBefore);
    }

    @Test
    void updateAdvancesUpdatedAt() {
        AddResult added = service.add(drink("coffee", null));

        service.update(new EntryPatch(added.id(), null, new BigDecimal("2"),
                null, null, null), TOKYO).orElseThrow();

        assertThat(jdbc.queryForObject("SELECT updated_at > created_at FROM entries WHERE id = ?",
                Boolean.class, added.id())).isTrue();
    }

    @Test
    void deleteHidesEntryFromAggregateAndQueryButKeepsTheRow() {
        AddResult added = service.add(drink("coffee", null));
        Instant deletedAt = Instant.parse("2026-10-09T00:00:00Z");

        EntryWriteResult deleted = service.delete(added.id(), deletedAt).orElseThrow();

        assertThat(deleted.entry().id()).isEqualTo(added.id());
        assertThat(deleted.dayTotal()).isEqualByComparingTo("0");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM entries WHERE id = ?", Integer.class, added.id()))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT deleted_at = CAST(? AS timestamptz) FROM entries WHERE id = ?",
                Boolean.class, deletedAt.toString(), added.id())).isTrue();

        var aggregate = service.aggregate(new AggregateQuery("drink", AggregateMetric.COUNT, null,
                Map.of("type", "coffee"), added.localDay(), added.localDay(), GroupBy.NONE));
        var query = service.query(new EntryQuery("drink", null, Map.of("type", "coffee"),
                added.localDay(), added.localDay(), null));
        assertThat(aggregate.value()).isEqualByComparingTo("0");
        assertThat(query.entries()).isEmpty();
    }

    @Test
    void secondDeleteReturnsSameSnapshotAndKeepsFirstDeletedAt() {
        AddResult added = service.add(drink("coffee", null));
        Instant firstTime = Instant.parse("2026-10-09T00:00:00Z");
        Instant secondTime = firstTime.plusSeconds(60);

        EntryWriteResult first = service.delete(added.id(), firstTime).orElseThrow();
        BigDecimal deletedAtAfterFirst = jdbc.queryForObject(
                "SELECT EXTRACT(EPOCH FROM deleted_at)::numeric FROM entries WHERE id = ?",
                BigDecimal.class, added.id());
        EntryWriteResult second = service.delete(added.id(), secondTime).orElseThrow();
        BigDecimal deletedAtAfterSecond = jdbc.queryForObject(
                "SELECT EXTRACT(EPOCH FROM deleted_at)::numeric FROM entries WHERE id = ?",
                BigDecimal.class, added.id());

        assertThat(second.entry()).isEqualTo(first.entry());
        assertThat(deletedAtAfterSecond).isEqualByComparingTo(deletedAtAfterFirst);
    }

    @Test
    void deleteUnknownIdReturnsEmpty() {
        assertThat(service.delete(java.util.UUID.randomUUID(), Instant.parse("2026-10-09T00:00:00Z")))
                .isEmpty();
    }

    private AddCommand drink(String type, BigDecimal quantity) {
        return new AddCommand("drink", quantity, null, Map.of("type", type), List.of(), TS, TOKYO, "chat");
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
