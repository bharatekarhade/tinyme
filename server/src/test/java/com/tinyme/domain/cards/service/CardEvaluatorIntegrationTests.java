package com.tinyme.domain.cards.service;

import com.tinyme.api.cards.CardEntriesService;
import com.tinyme.domain.cards.entity.CardEntity;
import com.tinyme.domain.cards.model.CardSpecParser;
import com.tinyme.domain.cards.model.CardSpecWriter;
import com.tinyme.domain.cards.model.CardStatus;
import com.tinyme.domain.cards.model.view.BreakdownView;
import com.tinyme.domain.cards.model.view.ChartView;
import com.tinyme.domain.cards.model.view.CheckinsView;
import com.tinyme.domain.cards.model.view.GoalsView;
import com.tinyme.domain.cards.model.view.LatestView;
import com.tinyme.domain.cards.model.view.ProgressView;
import com.tinyme.domain.cards.model.view.StatView;
import com.tinyme.domain.cards.repository.CardRepository;
import com.tinyme.domain.entries.model.add.AddCommand;
import com.tinyme.domain.entries.model.aggregate.Range;
import com.tinyme.domain.entries.service.EntryService;
import com.tinyme.domain.plans.model.PlanAddCommand;
import com.tinyme.domain.plans.model.PlanKind;
import com.tinyme.domain.plans.service.PlanService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
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
@Import(CardEvaluatorIntegrationTests.DatabaseConfiguration.class)
class CardEvaluatorIntegrationTests {
    private static final ZoneId TOKYO = ZoneId.of("Asia/Tokyo");
    private static final LocalDate WEDNESDAY = LocalDate.of(2026, 10, 7);
    private static final String CARD_PREFIX = "evaluator-it-" + UUID.randomUUID() + "-";
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired CardEvaluator evaluator;
    @Autowired CardEntriesService cardEntries;
    @Autowired EntryService entries;
    @Autowired PlanService plans;
    @Autowired CardRepository cards;
    @Autowired CardSpecParser parser;
    @Autowired CardSpecWriter writer;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM entries WHERE kind IN ('meal', 'drink', 'expense', 'workout', 'quantity_test')");
        jdbc.update("DELETE FROM entries WHERE source = 'health'");
        jdbc.update("DELETE FROM entries WHERE kind IN ('sleep', 'weight')");
        jdbc.update("DELETE FROM plans WHERE title LIKE ?", CARD_PREFIX + "%");
        jdbc.update("UPDATE entry_kinds SET merged_into = NULL WHERE kind IN ('meal', 'drink', 'expense', 'workout', 'quantity_test')");
        jdbc.update("DELETE FROM entry_kinds WHERE kind IN ('meal', 'drink', 'expense', 'workout', 'quantity_test')");
        jdbc.update("DELETE FROM cards WHERE slug LIKE ?", CARD_PREFIX + "%");
    }

    @Test
    void evaluatesTheFourExampleCardsAgainstPostgresForAWeekRange() {
        add("meal", WEDNESDAY, LocalTime.of(9, 0), Map.of("kcal", 100, "protein_g", 40, "carbs_g", 20,
                "fat_g", 10, "type", "breakfast"));
        add("drink", WEDNESDAY, LocalTime.of(0, 30), Map.of("kcal", 55, "protein_g", 5, "carbs_g", 10,
                "fat_g", 2, "type", "coffee"));
        add("drink", LocalDate.of(2026, 10, 4), LocalTime.of(12, 0), Map.of("kcal", 100));

        add("expense", LocalDate.of(2026, 10, 5), LocalTime.NOON, Map.of("amount", 8470, "category", "food"));
        add("expense", LocalDate.of(2026, 10, 6), LocalTime.NOON, Map.of("amount", 4050, "category", "transport"));
        add("expense", WEDNESDAY, LocalTime.of(13, 0), Map.of("amount", 3320, "category", "shopping"));
        add("expense", WEDNESDAY, LocalTime.of(14, 0), Map.of("amount", 2580));
        add("expense", LocalDate.of(2026, 9, 28), LocalTime.NOON, Map.of("amount", 20930, "category", "food"));
        // Last week's Sunday: counted, because the comparison is with the whole of last week.
        add("expense", LocalDate.of(2026, 10, 4), LocalTime.NOON, Map.of("amount", 7000, "category", "food"));

        add("workout", LocalDate.of(2026, 10, 5), LocalTime.of(8, 0), Map.of("type", "Leg day", "minutes", 50));
        add("workout", LocalDate.of(2026, 10, 6), LocalTime.of(8, 0), Map.of("type", "Run", "minutes", 30));
        add("workout", WEDNESDAY, LocalTime.of(8, 0), Map.of("type", "Push day", "minutes", 70));
        // Apple Health measured these; they aren't journaling, so the Journal count and streak ignore them.
        healthSteps(LocalDate.of(2026, 10, 2), 6420);
        healthSteps(WEDNESDAY, 3100);

        var journal = evaluator.evaluate(cards.findBySlug("journal").orElseThrow(), Range.WEEK);
        var journalStat = (StatView) journal.blocks().getFirst();
        var journalChart = (ChartView) journal.blocks().get(1);
        assertThat(journalStat.value()).isEqualByComparingTo("9"); // ["*"] leaves out the kind predicate.
        assertThat(journalChart.points()).hasSize(7);
        assertThat(journalChart.points().getFirst().period()).isEqualTo(LocalDate.of(2026, 10, 5)); // Monday start.
        assertThat(journalChart.points().getFirst().value()).isEqualByComparingTo("2");
        assertThat(journalChart.points().getFirst().state()).isEqualTo("past");
        assertThat(journalChart.points().get(2).period()).isEqualTo(WEDNESDAY);
        assertThat(journalChart.points().get(2).state()).isEqualTo("current");
        assertThat(journalChart.points().get(3).state()).isEqualTo("future");
        assertThat(journalChart.points().getLast().value()).isEqualByComparingTo("0");

        // The journaling streak is as of today, whatever range is shown: Sunday to Wednesday.
        var journalStreak = evaluator.journalStreak();
        assertThat(journalStreak.current()).isEqualTo(4);
        assertThat(journalStreak.days()).containsExactly(false, false, false, true, true, true, true);

        var calories = evaluator.evaluate(saveExample("calories", CALORIES), Range.WEEK);
        var calorieProgress = (ProgressView) calories.blocks().getFirst();
        assertThat(calorieProgress.value()).isEqualByComparingTo("155");
        // 100 kcal over the 3 days since the first entry, not over a flat 30.
        assertThat(calorieProgress.averageDay().baseline()).isEqualByComparingTo("33.33");
        assertThat(calorieProgress.averageDay().currentPerDay()).isEqualByComparingTo("155.00");
        assertThat(((GoalsView) calories.blocks().get(1)).items()).hasSize(3);
        assertThat(((GoalsView) calories.blocks().get(1)).items().getFirst().value()).isEqualByComparingTo("45");

        var spending = evaluator.evaluate(saveExample("spending", SPENDING), Range.WEEK);
        assertThat(((StatView) spending.blocks().getFirst()).value()).isEqualByComparingTo("18420");
        // The whole of last week, Sunday's 7,000 included, not just its Monday to Wednesday.
        assertThat(((StatView) spending.blocks().getFirst()).previous()).isEqualByComparingTo("27930");
        var breakdown = (BreakdownView) spending.blocks().get(1);
        assertThat(breakdown.total()).isEqualByComparingTo("18420");
        assertThat(breakdown.slices()).hasSize(3);
        assertThat(breakdown.other().value()).isEqualByComparingTo("2580");

        var gymCard = saveExample("gym", GYM);
        // One day of check-ins would be a single dot: the Day range shows the week, weekly goal included.
        var gymToday = (CheckinsView) evaluator.evaluate(gymCard, Range.DAY).blocks().getFirst();
        assertThat(gymToday.slots()).hasSize(7);
        assertThat(gymToday.slots().get(2).state()).isEqualTo("current");
        assertThat(gymToday.goal()).isEqualTo(5);

        var gym = evaluator.evaluate(gymCard, Range.WEEK);
        var checkins = (CheckinsView) gym.blocks().getFirst();
        assertThat(checkins.slots()).hasSize(7);
        assertThat(checkins.slots().getFirst().period()).isEqualTo(LocalDate.of(2026, 10, 5));
        assertThat(checkins.doneCount()).isEqualTo(3);
        assertThat(checkins.slots().getFirst().activity()).isEqualTo("Leg day");
        assertThat(checkins.slots().get(1).activity()).isEqualTo("Run");
        assertThat(checkins.slots().get(2).period()).isEqualTo(WEDNESDAY);
        assertThat(checkins.slots().get(2).activity()).isEqualTo("Push day");
        assertThat(checkins.slots().get(2).state()).isEqualTo("current");
        assertThat(checkins.slots().get(3).state()).isEqualTo("future");
        assertThat(checkins.slots().getLast().done()).isFalse();
        var latest = (LatestView) gym.blocks().get(1);
        assertThat(latest.entry().data()).containsEntry("type", "Push day").containsEntry("minutes", 70);

        assertThat(jdbc.queryForObject("SELECT local_day FROM entries WHERE kind = 'drink' AND local_day = ?",
                LocalDate.class, WEDNESDAY))
                .isEqualTo(WEDNESDAY);
    }

    @Test
    void historicalWeekMonthAndYearChartsKeepTheirRecordedBarsVisible() {
        LocalDate previousWeek = LocalDate.of(2026, 9, 28);
        LocalDate previousMonth = LocalDate.of(2026, 9, 1);
        LocalDate previousYear = LocalDate.of(2025, 1, 1);
        add("expense", previousWeek.plusDays(2), LocalTime.NOON, Map.of("amount", 12));
        add("expense", LocalDate.of(2026, 9, 15), LocalTime.NOON, Map.of("amount", 34));
        add("expense", LocalDate.of(2025, 1, 4), LocalTime.NOON, Map.of("amount", 56));
        CardEntity journal = cards.findBySlug("journal").orElseThrow();

        ChartView week = (ChartView) evaluator.evaluate(journal, Range.WEEK, previousWeek).blocks().get(1);
        ChartView month = (ChartView) evaluator.evaluate(journal, Range.MONTH, previousMonth).blocks().get(1);
        ChartView year = (ChartView) evaluator.evaluate(journal, Range.YEAR, previousYear).blocks().get(1);

        assertThat(week.points()).anySatisfy(point -> {
            assertThat(point.value()).isEqualByComparingTo("1");
            assertThat(point.state()).isEqualTo("past");
        });
        assertThat(week.points()).noneMatch(point -> point.state().equals("future"));
        assertThat(month.points()).hasSize(30).noneMatch(point -> point.state().equals("future"));
        assertThat(month.points().get(14).value()).isEqualByComparingTo("1");
        assertThat(year.points()).hasSize(12).noneMatch(point -> point.state().equals("future"));
        assertThat(year.points().getFirst().value()).isEqualByComparingTo("1");
    }

    @Test
    void historicalStreakBadgeIsMeasuredAtTheEndOfTheSelectedPeriod() {
        add("expense", LocalDate.of(2026, 10, 2), LocalTime.NOON, Map.of("amount", 12));
        add("expense", LocalDate.of(2026, 10, 3), LocalTime.NOON, Map.of("amount", 13));
        add("expense", LocalDate.of(2026, 10, 4), LocalTime.NOON, Map.of("amount", 14));
        CardEntity journal = cards.findBySlug("journal").orElseThrow();

        var historicalWeek = evaluator.evaluate(journal, Range.WEEK, LocalDate.of(2026, 9, 30));

        assertThat(historicalWeek.badge().days()).isEqualTo(3);
    }

    @Test
    void checkinSlotHasNoActivityLabelWhenWorkoutTypeWasNotLogged() {
        add("workout", LocalDate.of(2026, 10, 5), LocalTime.of(8, 0), Map.of("minutes", 25));
        var gym = evaluator.evaluate(saveExample("gym-no-type", GYM), Range.MONTH, LocalDate.of(2026, 10, 1));
        var checkins = (CheckinsView) gym.blocks().getFirst();

        assertThat(checkins.slots().get(4).done()).isTrue();
        assertThat(checkins.slots().get(4).activity()).isNull();
    }

    @Test
    void yearGymHeadlineCountsVisitsWhileTilesShowActiveMonths() {
        add("workout", LocalDate.of(2026, 1, 5), LocalTime.of(8, 0), Map.of("type", "Leg day"));
        add("workout", LocalDate.of(2026, 1, 9), LocalTime.of(8, 0), Map.of("type", "Run"));
        add("workout", LocalDate.of(2026, 2, 2), LocalTime.of(8, 0), Map.of("type", "Push day"));

        var gym = evaluator.evaluate(saveExample("gym-year", GYM), Range.YEAR, LocalDate.of(2026, 10, 7));
        var checkins = (CheckinsView) gym.blocks().getFirst();

        assertThat(checkins.doneCount()).isEqualTo(3);
        assertThat(checkins.slots()).hasSize(12);
        assertThat(checkins.slots().get(0).done()).isTrue();
        assertThat(checkins.slots().get(1).done()).isTrue();
        assertThat(checkins.slots().subList(2, 12)).noneMatch(slot -> slot.done());
    }

    @Test
    void upcomingHabitPlansMarkFutureGymSlotsPlannedUntilAnEntryIsLogged() {
        add("workout", LocalDate.of(2026, 10, 5), LocalTime.of(8, 0), Map.of("type", "Run"));
        var gym = saveExample("gym-planned", GYM);
        LocalDate thursday = LocalDate.of(2026, 10, 8);
        plans.add(new PlanAddCommand(PlanKind.EVENT, CARD_PREFIX + "planned gym", thursday,
                LocalTime.of(19, 0), null, null, null, List.of(), null, null, "workout", null), TOKYO,
                Instant.parse("2026-10-07T03:00:00Z"));

        CheckinsView planned = (CheckinsView) evaluator.evaluate(gym, Range.WEEK).blocks().getFirst();
        assertThat(planned.slots().get(3).period()).isEqualTo(thursday);
        assertThat(planned.slots().get(3).state()).isEqualTo("planned");
        assertThat(planned.slots().get(3).done()).isFalse();
        assertThat(planned.plannedCount()).isEqualTo(1);

        add("workout", thursday, LocalTime.of(19, 0), Map.of("type", "Strength training"));
        CheckinsView logged = (CheckinsView) evaluator.evaluate(gym, Range.WEEK).blocks().getFirst();
        assertThat(logged.slots().get(3).state()).isEqualTo("future");
        assertThat(logged.slots().get(3).done()).isTrue();
        assertThat(logged.slots().get(3).activity()).isEqualTo("Strength training");
        assertThat(logged.plannedCount()).isZero();
    }

    @Test
    void cardDrilldownUsesTheSameMetricScopeAsTheCard() {
        add("expense", LocalDate.of(2026, 10, 5), LocalTime.NOON, Map.of("amount", 8470, "category", "food"));
        add("expense", LocalDate.of(2026, 10, 6), LocalTime.NOON, Map.of("amount", 4050, "category", "transport"));
        add("expense", LocalDate.of(2026, 10, 7), LocalTime.NOON, Map.of("amount", 3320, "category", "food"));
        add("expense", LocalDate.of(2026, 10, 7), LocalTime.of(13, 0), Map.of("category", "food"));
        CardEntity spending = saveExample("spending", SPENDING);

        var firstPage = cardEntries.get(spending.getId(), Range.WEEK, 0, null, null, null, null, 2).orElseThrow();
        assertThat(firstPage.nextCursor()).isNotNull();
        var secondPage = cardEntries.get(spending.getId(), Range.WEEK, 0, null, null, null,
                firstPage.nextCursor(), 2).orElseThrow();
        List<CardEntriesService.EntryRow> allListed = new java.util.ArrayList<>(firstPage.entries());
        allListed.addAll(secondPage.entries());
        BigDecimal listedAmount = allListed.stream().map(row -> (Number) row.data().get("amount"))
                .map(value -> new BigDecimal(value.toString())).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(firstPage.total()).isEqualByComparingTo("15840");
        assertThat(listedAmount).isEqualByComparingTo(firstPage.total());
        assertThat(allListed).hasSize(3);
        assertThat(firstPage.total()).isEqualByComparingTo(((StatView) evaluator.evaluate(spending, Range.WEEK)
                .blocks().getFirst()).value());

        var food = cardEntries.get(spending.getId(), Range.WEEK, 1, null, null, "food", null, 50).orElseThrow();
        BigDecimal foodSlice = ((BreakdownView) evaluator.evaluate(spending, Range.WEEK).blocks().get(1))
                .slices().stream().filter(slice -> "food".equals(slice.key())).findFirst().orElseThrow().value();
        assertThat(food.total()).isEqualByComparingTo(foodSlice);
        assertThat(food.entries()).hasSize(2);

        var journal = cards.findBySlug("journal").orElseThrow();
        var monday = cardEntries.get(journal.getId(), Range.WEEK, 1, null, LocalDate.of(2026, 10, 5),
                null, null, 50).orElseThrow();
        assertThat(monday.total()).isEqualByComparingTo("1");
        assertThat(monday.dayTotals()).singleElement().extracting(CardEntriesService.DayTotal::value)
                .isEqualTo(new BigDecimal("1"));
    }

    @Test
    void cardDrilldownUsesTheSelectedHistoricalPeriod() {
        add("expense", LocalDate.of(2026, 10, 7), LocalTime.NOON, Map.of("amount", 12, "category", "food"));
        add("expense", LocalDate.of(2026, 10, 12), LocalTime.NOON, Map.of("amount", 34, "category", "food"));
        CardEntity spending = saveExample("spending-history", SPENDING);

        var selected = cardEntries.get(spending.getId(), Range.WEEK, WEDNESDAY,
                0, null, null, null, null, 50).orElseThrow();

        assertThat(selected.from()).isEqualTo(LocalDate.of(2026, 10, 5));
        assertThat(selected.to()).isEqualTo(LocalDate.of(2026, 10, 11));
        assertThat(selected.total()).isEqualByComparingTo("12");
        assertThat(selected.entries()).hasSize(1);
    }

    @Test
    void countDrilldownUsesQuantityRatherThanRowCount() {
        entries.add(new AddCommand("quantity_test", new BigDecimal("2"), "two checks", Map.of(), List.of(),
                WEDNESDAY.atTime(10, 0).atZone(TOKYO).toInstant(), TOKYO, "chat"));
        CardEntity card = saveExample("quantity-count", """
                {"v":1,"title":"Quantity","icon":"chart","accent":"mint","tone":"surface",
                 "blocks":[{"type":"stat","label":"count","period":"range",
                            "metric":{"kinds":["quantity_test"],"op":"count"}}]}
                """);

        var result = cardEntries.get(card.getId(), Range.WEEK, 0, null, null, null, null, 50).orElseThrow();

        assertThat(result.total()).isEqualByComparingTo("2");
        assertThat(result.entries()).hasSize(1);
        assertThat(result.entries().getFirst().quantity()).isEqualByComparingTo("2");
    }

    @Test
    void cardDrilldownRejectsInvalidBlockItemKeyDayCursorAndLimit() {
        CardEntity spending = saveExample("spending", SPENDING);

        assertThatThrownBy(() -> cardEntries.get(spending.getId(), Range.WEEK, 8, null,
                null, null, null, 50)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("block");
        assertThatThrownBy(() -> cardEntries.get(spending.getId(), Range.WEEK, 0, 0,
                null, null, null, 50)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("item");
        assertThatThrownBy(() -> cardEntries.get(spending.getId(), Range.WEEK, 0, null,
                null, "food", null, 50)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("key");
        assertThatThrownBy(() -> cardEntries.get(spending.getId(), Range.WEEK, 1, null,
                LocalDate.of(2026, 10, 4), null, null, 50)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("day");
        assertThatThrownBy(() -> cardEntries.get(spending.getId(), Range.WEEK, 0, null,
                null, null, "bad-cursor", 50)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cursor");
        assertThatThrownBy(() -> cardEntries.get(spending.getId(), Range.WEEK, 0, null,
                null, null, null, 0)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("limit");
        assertThat(cardEntries.get(UUID.randomUUID(), Range.WEEK, null, null,
                null, null, null, 50)).isEmpty();
    }

    @Test
    void lastNightIsTheNightsTotalAcrossHealthAndAnAddition() {
        // The Watch died: Health has 1.52 h, the user added 7.58 h in chat later that day.
        jdbc.update("""
                INSERT INTO entries (ts, local_day, kind, quantity, data, source, external_id) VALUES
                  ('2026-10-06T14:10:00Z', '2026-10-07', 'sleep', 1.52, '{"hours":1.52}', 'health', 'sleep:2026-10-07'),
                  ('2026-10-07T08:23:00Z', '2026-10-07', 'sleep', 7.58, '{"hours":7.58,"adds_to_health":true}', 'chat', NULL),
                  ('2026-10-06T21:00:00Z', '2026-10-07', 'weight', 72.4, '{"kg":72.4}', 'health', 'w1'),
                  ('2026-10-07T01:00:00Z', '2026-10-07', 'weight', 72.1, '{"kg":72.1}', 'health', 'w2')
                """);
        var card = saveExample("sleep", """
                {"v":1,"title":"Sleep","icon":"bed","accent":"mint","tone":"surface","blocks":[
                  {"type":"latest","label":"Last night","kinds":["sleep"],"fields":["hours"]},
                  {"type":"latest","label":"Weight","kinds":["weight"],"fields":["kg"]}]}
                """);

        var blocks = evaluator.evaluate(card, Range.WEEK).blocks();
        var night = (com.tinyme.domain.cards.model.view.LatestView) blocks.getFirst();
        assertThat(new java.math.BigDecimal(night.entry().data().get("hours").toString())).isEqualByComparingTo("9.10");
        // Weight is a reading, not a day total: the newest one, not 144.5 kg.
        var weight = (com.tinyme.domain.cards.model.view.LatestView) blocks.get(1);
        assertThat(new java.math.BigDecimal(weight.entry().data().get("kg").toString())).isEqualByComparingTo("72.1");
    }

    private CardEntity saveExample(String slug, String source) {
        var spec = parser.parse(JSON.readTree(source));
        Map<String, Object> stored = JSON.convertValue(writer.write(spec), MAP_TYPE);
        return cards.save(new CardEntity(CARD_PREFIX + slug, spec.title(), stored, CardStatus.DRAFT, false, 0, null));
    }

    private void healthSteps(LocalDate day, int steps) {
        jdbc.update("""
                INSERT INTO entries (ts, local_day, kind, quantity, data, source, external_id)
                VALUES (?, ?, 'steps', ?, CAST(? AS jsonb), 'health', ?)
                """, java.sql.Timestamp.from(day.atTime(12, 0).atZone(TOKYO).toInstant()), day, steps,
                "{\"steps\":" + steps + "}", "steps:" + day);
    }

    private void add(String kind, LocalDate day, LocalTime time, Map<String, Object> data) {
        Instant instant = day.atTime(time).atZone(TOKYO).toInstant();
        entries.add(new AddCommand(kind, BigDecimal.ONE, null, data, List.of(), instant, TOKYO, "chat"));
    }

    private static final String CALORIES = """
            {"v":1,"title":"Calories","icon":"flame","accent":"coral","tone":"surface","blocks":[
              {"type":"progress","label":"kcal","style":"gauge","metric":{"kinds":["meal","drink"],"op":"sum","field":"kcal"},"period":"today","goal":2200,"unit":"kcal"},
              {"type":"goals","period":"today","items":[
                {"label":"Protein","metric":{"kinds":["meal","drink"],"op":"sum","field":"protein_g"},"goal":120,"unit":"g"},
                {"label":"Carbs","metric":{"kinds":["meal","drink"],"op":"sum","field":"carbs_g"},"goal":250,"unit":"g"},
                {"label":"Fat","metric":{"kinds":["meal","drink"],"op":"sum","field":"fat_g"},"goal":70,"unit":"g"}]}
            ]}
            """;

    private static final String SPENDING = """
            {"v":1,"title":"Spending","icon":"wallet","accent":"sunny","tone":"surface","badge":{"kind":"delta","block":0},"blocks":[
              {"type":"stat","label":"spent","metric":{"kinds":["expense"],"op":"sum","field":"amount"},"period":"range","goal":30000,"goal_period":"week","compare":"previous_period","better":"lower","unit":"JPY"},
              {"type":"breakdown","label":"by category","metric":{"kinds":["expense"],"op":"sum","field":"amount"},"period":"range","by":"category","top":3,"style":"segmented","unit":"JPY"}
            ]}
            """;

    private static final String GYM = """
            {"v":1,"title":"Gym","icon":"dumbbell","accent":"ink","tone":"ink","blocks":[
              {"type":"checkins","label":"visits","metric":{"kinds":["workout"],"op":"count"},"period":"range","goal":5,"goal_period":"week"},
              {"type":"latest","label":"Last session","kinds":["workout"],"fields":["type","minutes"]}
            ]}
            """;

    @TestConfiguration(proxyBeanMethods = false)
    static class DatabaseConfiguration {
        @Bean
        @ServiceConnection
        PostgreSQLContainer postgres() {
            return new PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:pg18")
                    .asCompatibleSubstituteFor("postgres"));
        }

        @Bean
        @Primary
        Clock fixedCardClock() {
            return Clock.fixed(Instant.parse("2026-10-07T03:00:00Z"), ZoneId.of("UTC"));
        }
    }
}
