package com.tinyme.domain.cards.service;

import com.tinyme.domain.cards.entity.CardEntity;
import com.tinyme.domain.cards.model.CardSpecParser;
import com.tinyme.domain.cards.model.CardSpecWriter;
import com.tinyme.domain.cards.model.CardStatus;
import com.tinyme.domain.cards.model.view.ChartView;
import com.tinyme.domain.cards.model.view.CheckinsView;
import com.tinyme.domain.cards.model.view.StatView;
import com.tinyme.domain.cards.model.view.UnavailableView;
import com.tinyme.domain.entries.model.aggregate.AggregateQuery;
import com.tinyme.domain.entries.model.aggregate.AggregateResult;
import com.tinyme.domain.entries.model.aggregate.Bucket;
import com.tinyme.domain.entries.model.aggregate.GroupBy;
import com.tinyme.domain.entries.model.aggregate.Range;
import com.tinyme.domain.entries.service.EntryService;
import com.tinyme.domain.plans.service.PlanService;
import com.tinyme.settings.UserZone;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CardEvaluatorTests {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final ZoneId TOKYO = ZoneId.of("Asia/Tokyo");
    private static final Instant NOW = Instant.parse("2026-10-10T16:30:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 11);

    @Mock EntryService entries;
    @Mock PlanService plans;
    @Mock UserZone userZone;

    private CardSpecParser parser;
    private CardSpecWriter writer;
    private CardEvaluator evaluator;

    @BeforeEach
    void setUp() {
        parser = new CardSpecParser();
        writer = new CardSpecWriter();
        evaluator = new CardEvaluator(parser, entries, plans, userZone, Clock.fixed(NOW, ZoneId.of("UTC")));
        when(userZone.current()).thenReturn(TOKYO);
    }

    @Test
    void evaluatesStatCompareAndDeltaBadgeUsingLocalToday() {
        CardEntity card = card("""
                {"v":1,"title":"Spending","icon":"wallet","accent":"sunny","tone":"surface",
                 "badge":{"kind":"delta","block":0},"blocks":[
                  {"type":"stat","label":"spent","metric":{"kinds":["expense"],"op":"sum","field":"amount"},
                   "period":"last_7_days","goal":30000,"goal_period":"week","compare":"previous_period","better":"lower"}
                 ]}
                """);
        when(entries.aggregate(any())).thenAnswer(invocation -> {
            AggregateQuery query = invocation.getArgument(0);
            if (query.groupBy() == GroupBy.DAY) {
                List<Bucket> buckets = new ArrayList<>();
                for (LocalDate date = query.from(); !date.isAfter(query.to()); date = date.plusDays(1)) {
                    buckets.add(new Bucket(date, BigDecimal.ONE, 1));
                }
                return aggregate(query, null, null, buckets);
            }
            assertThat(query.from()).isIn(TODAY.minusDays(6), TODAY.minusDays(13));
            assertThat(query.to()).isIn(TODAY, TODAY.minusDays(7));
            return aggregate(query, query.from().equals(TODAY.minusDays(6)) ? "18420" : "20930", 23, List.of());
        });

        var view = evaluator.evaluate(card, Range.DAY);

        var stat = (StatView) view.blocks().getFirst();
        assertThat(stat.value()).isEqualByComparingTo("18420");
        assertThat(stat.goal()).isNull(); // weekly goal is hidden on the day range
        assertThat(stat.previous()).isEqualByComparingTo("20930");
        assertThat(stat.deltaPct()).isEqualByComparingTo("-12");
        assertThat(view.badge().kind()).isEqualTo("delta");
        assertThat(view.badge().better()).isEqualTo("lower");
        assertThat(view.badge().compareLabel()).isEqualTo("previous_7_days");
        assertThat(view.status()).isEqualTo("draft");
    }

    @Test
    void skipsPreviousPeriodComparisonForTheCurrentDay() {
        CardEntity card = card("""
                {"v":1,"title":"Journal","icon":"journal","accent":"mint","tone":"surface",
                 "badge":{"kind":"delta","block":0},"blocks":[
                  {"type":"stat","label":"entries","metric":{"kinds":["drink"],"op":"count"},
                   "period":"range","compare":"previous_period"}
                 ]}
                """);
        when(entries.aggregate(any())).thenAnswer(invocation -> {
            AggregateQuery query = invocation.getArgument(0);
            if (query.groupBy() == GroupBy.DAY) {
                return aggregate(query, null, null, List.of());
            }
            assertThat(query.from()).isEqualTo(TODAY);
            assertThat(query.to()).isEqualTo(TODAY);
            return aggregate(query, "2", 2, List.of());
        });

        var view = evaluator.evaluate(card, Range.DAY);

        var stat = (StatView) view.blocks().getFirst();
        assertThat(stat.previous()).isNull();
        assertThat(stat.deltaPct()).isNull();
        assertThat(view.badge()).isNull();
        verify(entries, times(2)).aggregate(any());
    }

    @Test
    void selectedDateAnchorsEvaluationAndComparesWithThePreviousThirtyDayAverage() {
        LocalDate selected = LocalDate.of(2026, 10, 8);
        CardEntity card = card("""
                {"v":1,"title":"Journal","icon":"journal","accent":"mint","tone":"surface","blocks":[
                  {"type":"stat","label":"entries","metric":{"kinds":["drink"],"op":"count"},
                   "period":"range","compare":"previous_period"}
                ]}
                """);
        when(entries.aggregate(any())).thenAnswer(invocation -> {
            AggregateQuery query = invocation.getArgument(0);
            if (query.groupBy() == GroupBy.DAY) {
                assertThat(query.from()).isEqualTo(selected.minusDays(30));
                assertThat(query.to()).isEqualTo(selected.minusDays(1));
                List<Bucket> buckets = new ArrayList<>();
                for (LocalDate date = query.from(); !date.isAfter(query.to()); date = date.plusDays(1)) {
                    buckets.add(new Bucket(date, BigDecimal.valueOf(2), 1));
                }
                return aggregate(query, null, null, buckets);
            }
            if (query.from().equals(selected)) {
                assertThat(query.to()).isEqualTo(selected);
                return aggregate(query, "4", 4, List.of());
            }
            assertThat(query.from()).isEqualTo(selected.minusDays(1));
            assertThat(query.to()).isEqualTo(selected.minusDays(1));
            return aggregate(query, "2", 2, List.of());
        });

        var view = evaluator.evaluate(card, Range.DAY, selected);

        var stat = (StatView) view.blocks().getFirst();
        assertThat(stat.value()).isEqualByComparingTo("4");
        assertThat(stat.previous()).isEqualByComparingTo("2");
        assertThat(stat.averageDay().baseline()).isEqualByComparingTo("2.00");
        assertThat(stat.averageDay().currentPerDay()).isEqualByComparingTo("4.00");
        assertThat(stat.averageDay().deltaPct()).isEqualByComparingTo("100.00");
    }

    @Test
    void invalidStoredSpecReturnsMinimalUnavailableCard() {
        var card = new CardEntity("legacy", "Old card", Map.of("v", 1), CardStatus.ACTIVE, false, 0, NOW);

        var view = evaluator.evaluate(card, Range.WEEK);
        String json = JSON.writeValueAsString(view);

        assertThat(view.slug()).isEqualTo("legacy");
        assertThat(view.title()).isEqualTo("Old card");
        assertThat(view.status()).isEqualTo("active");
        assertThat(view.error()).isEqualTo("unavailable");
        assertThat(view.blocks()).isNull();
        assertThat(json).contains("\"slug\":\"legacy\"", "\"title\":\"Old card\"",
                "\"status\":\"active\"", "\"error\":\"unavailable\"");
        assertThat(json).doesNotContain("\"icon\"", "\"system\"", "\"blocks\"");
    }

    @Test
    void dayRangeChartIsWidenedToSevenDaysAndMarksTodayCurrent() {
        CardEntity card = card("""
                {"v":1,"title":"Activity","icon":"chart","accent":"mint","tone":"surface","blocks":[
                  {"type":"chart","label":"per day","metric":{"kinds":["drink"],"op":"count"},"period":"range","highlight":"peak"}
                ]}
                """);
        when(entries.aggregate(any())).thenAnswer(invocation -> {
            AggregateQuery query = invocation.getArgument(0);
            if (query.groupBy() == GroupBy.DAY && !query.from().equals(TODAY.minusDays(6))) {
                return aggregate(query, null, null, List.of());
            }
            assertThat(query.from()).isEqualTo(TODAY.minusDays(6));
            assertThat(query.to()).isEqualTo(TODAY);
            assertThat(query.groupBy()).isEqualTo(GroupBy.DAY);
            List<Bucket> buckets = new ArrayList<>();
            for (LocalDate date = query.from(); !date.isAfter(query.to()); date = date.plusDays(1)) {
                buckets.add(new Bucket(date, BigDecimal.valueOf(date.getDayOfMonth() == 10 ? 2 : 1),
                        date.getDayOfMonth() == 10 ? 2 : 1));
            }
            return aggregate(query, null, null, buckets);
        });

        var chart = (ChartView) evaluator.evaluate(card, Range.DAY).blocks().getFirst();

        assertThat(chart.points()).hasSize(7);
        assertThat(chart.points().getLast().period()).isEqualTo(TODAY);
        assertThat(chart.points().getLast().state()).isEqualTo("current");
        assertThat(chart.points().getFirst().state()).isEqualTo("past");
        assertThat(chart.peakIndex()).isEqualTo(5);
    }

    @Test
    void oneFailedBlockDoesNotPreventTheFollowingBlockFromEvaluating() {
        CardEntity card = card("""
                {"v":1,"title":"Health","icon":"heart","accent":"coral","tone":"surface","blocks":[
                  {"type":"stat","label":"sleep","metric":{"kinds":["sleep"],"op":"sum","field":"hours"},"period":"today"},
                  {"type":"stat","label":"drinks","metric":{"kinds":["drink"],"op":"count"},"period":"today"}
                ]}
                """);
        when(entries.aggregate(any())).thenAnswer(invocation -> {
            AggregateQuery query = invocation.getArgument(0);
            if (query.kinds().contains("sleep")) throw new IllegalStateException("test database error");
            return aggregate(query, "1", 1, List.of());
        });

        var view = evaluator.evaluate(card, Range.DAY);

        assertThat(view.blocks().get(0)).isEqualTo(new UnavailableView("stat", "unavailable"));
        assertThat(view.blocks().get(1)).isInstanceOf(StatView.class);
        assertThat(((StatView) view.blocks().get(1)).value()).isEqualByComparingTo("1");
        assertThat(JSON.writeValueAsString(view)).contains("\"type\":\"unavailable\"", "\"block_type\":\"stat\"");
    }

    @Test
    void systemCardUsesTrustedParsingForBrandToneAndAllKinds() {
        JsonNode source = JSON.readTree("""
                {"v":1,"title":"Journal","icon":"journal","accent":"mint","tone":"brand","blocks":[
                  {"type":"stat","label":"entries","metric":{"kinds":["*"],"op":"count"},"period":"range"}
                ]}
                """);
        var spec = parser.parseTrusted(source);
        Map<String, Object> stored = JSON.convertValue(writer.write(spec), MAP_TYPE);
        var card = new CardEntity("journal", "Journal", stored, CardStatus.ACTIVE, true, 0, NOW);
        when(entries.aggregate(any())).thenAnswer(invocation -> {
            AggregateQuery query = invocation.getArgument(0);
            assertThat(query.allKinds()).isTrue();
            return aggregate(query, "4", 4, List.of());
        });

        var view = evaluator.evaluate(card, Range.WEEK);

        assertThat(view.tone()).isEqualTo("brand");
        assertThat(((StatView) view.blocks().getFirst()).value()).isEqualByComparingTo("4");
    }

    @Test
    void streakLooksBack400DaysAndEndsYesterdayWhenTodayHasNoEntries() {
        CardEntity card = card("""
                {"v":1,"title":"Journal","icon":"journal","accent":"mint","tone":"surface",
                 "badge":{"kind":"streak","block":0},"blocks":[
                  {"type":"stat","label":"entries","metric":{"kinds":["drink"],"op":"count"},"period":"today"}
                 ]}
                """);
        when(entries.aggregate(any())).thenAnswer(invocation -> {
            AggregateQuery query = invocation.getArgument(0);
            if (query.groupBy() == GroupBy.DAY) {
                if (!query.from().equals(TODAY.minusDays(399))) {
                    return aggregate(query, null, null, List.of());
                }
                assertThat(query.to()).isEqualTo(TODAY);
                List<Bucket> buckets = new ArrayList<>(400);
                for (LocalDate date = query.from(); !date.isAfter(query.to()); date = date.plusDays(1)) {
                    long count = date.equals(TODAY) || date.isBefore(TODAY.minusDays(2)) ? 0 : 1;
                    buckets.add(new Bucket(date, BigDecimal.valueOf(count), count));
                }
                return aggregate(query, null, null, buckets);
            }
            return aggregate(query, "0", 0, List.of());
        });

        var view = evaluator.evaluate(card, Range.DAY);

        assertThat(view.badge().days()).isEqualTo(2);
    }

    @Test
    void cardViewUsesSnakeCaseForNestedFields() {
        CardEntity card = card("""
                {"v":1,"title":"A","icon":"star","accent":"mint","tone":"surface","blocks":[
                  {"type":"chart","label":"per day","metric":{"kinds":["drink"],"op":"count"},"period":"range","highlight":"peak"}
                ]}
                """);
        when(entries.aggregate(any())).thenAnswer(invocation -> {
            AggregateQuery query = invocation.getArgument(0);
            return aggregate(query, null, null, List.of(new Bucket(TODAY, BigDecimal.ONE, 1)));
        });

        String json = JSON.writeValueAsString(evaluator.evaluate(card, Range.DAY));

        assertThat(json).contains("\"type\":\"chart\"", "\"peak_index\"", "\"goal_line\"");
        assertThat(json).doesNotContain("\"peakIndex\"", "\"goalLine\"");
    }

    @Test
    void upcomingHabitDayMarksAnUndoneCheckinSlotAsPlanned() {
        CardEntity card = card("""
                {"v":1,"title":"Gym","icon":"dumbbell","accent":"ink","tone":"surface","blocks":[
                  {"type":"checkins","label":"visits","metric":{"kinds":["workout"],"op":"count"},"period":"range"}
                ]}
                """);
        when(entries.aggregate(any())).thenAnswer(invocation -> {
            AggregateQuery query = invocation.getArgument(0);
            var buckets = new ArrayList<Bucket>();
            for (LocalDate day = query.from(); !day.isAfter(query.to()); day = day.plusDays(1)) {
                buckets.add(new Bucket(day, BigDecimal.ZERO, 0));
            }
            return aggregate(query, null, null, buckets);
        });
        when(entries.activityTypesByDay(any(), any(), any(), any())).thenReturn(Map.of());
        when(plans.habitDays(any(), any(), any(), any(), any())).thenReturn(Set.of(TODAY));

        CheckinsView result = (CheckinsView) evaluator.evaluate(card, Range.WEEK).blocks().getFirst();

        assertThat(result.slots()).filteredOn(slot -> slot.period().equals(TODAY)).singleElement()
                .satisfies(slot -> {
                    assertThat(slot.state()).isEqualTo("planned");
                    assertThat(slot.done()).isFalse();
                });
        assertThat(result.plannedCount()).isEqualTo(1);
        assertThat(JSON.writeValueAsString(result)).contains("\"planned_count\":1");
    }

    private CardEntity card(String source) {
        JsonNode json = JSON.readTree(source);
        var spec = parser.parse(json);
        Map<String, Object> stored = JSON.convertValue(writer.write(spec), MAP_TYPE);
        return new CardEntity("test-card", spec.title(), stored, CardStatus.DRAFT, false, 0, null);
    }

    private static AggregateResult aggregate(AggregateQuery query, String value, Integer entries, List<Bucket> buckets) {
        return new AggregateResult(query.kinds(), List.of(), query.metric(), query.field(), query.where(),
                query.from(), query.to(), query.groupBy(), value == null ? null : new BigDecimal(value),
                entries == null ? null : entries.longValue(), buckets);
    }
}
