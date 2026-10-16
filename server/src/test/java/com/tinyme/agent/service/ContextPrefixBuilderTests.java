package com.tinyme.agent.service;

import com.tinyme.domain.entries.model.aggregate.TodayTotal;
import com.tinyme.domain.entries.repository.EntryKindRepository;
import com.tinyme.domain.entries.repository.EntryRepository;
import com.tinyme.domain.plans.service.PlanService;
import com.tinyme.agent.model.TurnLocation;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ContextPrefixBuilderTests {
    private final EntryKindRepository kinds = mock(EntryKindRepository.class);
    private final EntryRepository entries = mock(EntryRepository.class);
    private final PlanService plans = mock(PlanService.class);
    private final com.tinyme.domain.health.service.HealthContext health =
            mock(com.tinyme.domain.health.service.HealthContext.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-05T15:30:00Z"), ZoneOffset.UTC);
    private final com.tinyme.domain.health.service.HealthNudges nudges =
            mock(com.tinyme.domain.health.service.HealthNudges.class);
    private final ContextPrefixBuilder builder = new ContextPrefixBuilder(kinds, entries, plans, health, nudges, clock);

    @Test
    void tellsTheAgentWhichCurrencySpendingIsKeptIn() {
        var currency = mock(com.tinyme.settings.UserCurrency.class);
        when(currency.current()).thenReturn("USD");
        var withCurrency = new ContextPrefixBuilder(kinds, entries, plans, health, nudges, clock, null, currency);

        assertThat(withCurrency.build(ZoneId.of("Asia/Tokyo")))
                .contains("tz: Asia/Tokyo\ncurrency: USD\n");
    }

    @Test
    void theHealthLineComesLastAndAFailureOnlyLeavesItOut() {
        when(health.line(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn("health: connected sleep_heart · slept 8.2 h last night");
        assertThat(builder.build(ZoneId.of("Asia/Tokyo")))
                .endsWith("health: connected sleep_heart · slept 8.2 h last night\n[/context]");

        when(health.line(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenThrow(new IllegalStateException("database down"));
        assertThat(builder.build(ZoneId.of("Asia/Tokyo"))).doesNotContain("health:").endsWith("[/context]");
    }

    @Test
    void usesLocalDateAndWeekdayAndPreservesKindRanking() {
        when(kinds.topKinds(30)).thenReturn(List.of("drink", "meal", "journal"));
        when(entries.todayTotals(LocalDate.of(2026, 10, 6))).thenReturn(List.of(
                new TodayTotal("drink", "coffee", new BigDecimal("2")),
                new TodayTotal("drink", "tea", new BigDecimal("1.50")),
                new TodayTotal("sleep", null, new BigDecimal("7"))));

        assertThat(builder.build(ZoneId.of("Asia/Tokyo"))).isEqualTo("""
                [context]
                now: 2026-10-06 Tuesday 00:30:00 +09:00
                tz: Asia/Tokyo
                known_kinds: drink, meal, journal
                today: drink (coffee)=2, drink (tea)=1.5, sleep=7
                [/context]""");
        verify(kinds).topKinds(30);
        verify(entries).todayTotals(LocalDate.of(2026, 10, 6));
    }

    @Test
    void omitsKnownKindsWhenEmptyWithoutLeavingBlankLines() {
        when(kinds.topKinds(30)).thenReturn(List.of());

        assertThat(builder.build(ZoneId.of("UTC"))).isEqualTo("""
                [context]
                now: 2026-10-05 Monday 15:30:00 Z
                tz: UTC
                [/context]""");
    }

    @Test
    void usesTheZonesDaylightSavingOffset() {
        when(kinds.topKinds(30)).thenReturn(List.of());

        assertThat(builder.build(ZoneId.of("America/New_York")))
                .contains("now: 2026-10-05 Monday 11:30:00 -04:00\ntz: America/New_York");
    }

    @Test
    void buildsContextAndTodayTotalsAsOfTheOriginalQueuedTime() {
        var queuedAt = Instant.parse("2026-10-04T15:30:00Z"); // Monday, Oct 5 at 00:30 in Tokyo.
        when(kinds.topKinds(30)).thenReturn(List.of("drink"));
        when(entries.todayTotals(LocalDate.of(2026, 10, 5))).thenReturn(List.of(
                new TodayTotal("drink", "coffee", new BigDecimal("1"))));

        assertThat(builder.build(ZoneId.of("Asia/Tokyo"), queuedAt)).isEqualTo("""
                [context]
                now: 2026-10-05 Monday 00:30:00 +09:00
                tz: Asia/Tokyo
                known_kinds: drink
                today: drink (coffee)=1
                [/context]""");
        verify(entries).todayTotals(LocalDate.of(2026, 10, 5));
    }

    @Test
    void includesOnlyTheAreaNameAndAgeForLocation() {
        when(kinds.topKinds(30)).thenReturn(List.of());
        when(entries.todayTotals(LocalDate.of(2026, 10, 6))).thenReturn(List.of());
        var location = new TurnLocation(35.6595, 139.7005, 35,
                clock.instant().minus(java.time.Duration.ofMinutes(2)), "  Shibuya,\nTokyo  ");

        String context = builder.build(ZoneId.of("Asia/Tokyo"), clock.instant(), location);

        assertThat(context).contains("location: Shibuya, Tokyo (2 min ago)")
                .doesNotContain("35.6595", "139.7005", "accuracy");
    }

    @Test
    void includesUpcomingPlanSummaryWhenAvailable() {
        when(kinds.topKinds(30)).thenReturn(List.of());
        when(plans.contextLine(ZoneId.of("Asia/Tokyo"), clock.instant()))
                .thenReturn("upcoming: 13:00 Lunch with Aiko · tomorrow 09:00 Gym");

        assertThat(builder.build(ZoneId.of("Asia/Tokyo"))).contains(
                "tz: Asia/Tokyo\nupcoming: 13:00 Lunch with Aiko · tomorrow 09:00 Gym");
    }

    @Test
    void brokenPlanContextDoesNotPreventChatContextFromBeingBuilt() {
        ZoneId zone = ZoneId.of("Asia/Tokyo");
        when(kinds.topKinds(30)).thenReturn(List.of());
        when(entries.todayTotals(LocalDate.of(2026, 10, 6))).thenReturn(List.of());
        when(plans.contextLine(zone, clock.instant())).thenThrow(new IllegalStateException("broken kind merge"));

        String context = builder.build(zone);

        assertThat(context).contains("[context]").contains("tz: Asia/Tokyo").doesNotContain("upcoming:");
        verify(plans).contextLine(zone, clock.instant());
    }
}
