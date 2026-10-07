package com.tinyme.agent.service;

import com.tinyme.domain.entries.repository.EntryKindRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ContextPrefixBuilderTests {
    private final EntryKindRepository kinds = mock(EntryKindRepository.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-05T15:30:00Z"), ZoneOffset.UTC);
    private final ContextPrefixBuilder builder = new ContextPrefixBuilder(kinds, clock);

    @Test
    void usesLocalDateAndWeekdayAndPreservesKindRanking() {
        when(kinds.topKinds(30)).thenReturn(List.of("drink", "meal", "journal"));

        assertThat(builder.build(ZoneId.of("Asia/Tokyo"))).isEqualTo("""
                [context]
                now: 2026-10-06 Tuesday 00:30:00 +09:00
                tz: Asia/Tokyo
                known_kinds: drink, meal, journal
                [/context]""");
        verify(kinds).topKinds(30);
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
}
