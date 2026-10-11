package com.tinyme.api.kinds;

import com.tinyme.domain.entries.model.RecentKind;
import com.tinyme.domain.entries.service.EntryService;
import com.tinyme.settings.UserZone;
import org.junit.jupiter.api.Test;

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

class KindControllerTests {
    @Test
    void returnsRecentKindsUsingTheOwnersLocalNinetyDayWindowAndFriendlyLabels() {
        EntryService entries = mock(EntryService.class);
        UserZone userZone = mock(UserZone.class);
        when(userZone.current()).thenReturn(ZoneId.of("Asia/Tokyo"));
        Clock clock = Clock.fixed(Instant.parse("2026-10-10T16:30:00Z"), ZoneOffset.UTC);
        LocalDate today = LocalDate.of(2026, 10, 11);
        when(entries.recentCanonicalKinds(today.minusDays(89), today, 20)).thenReturn(List.of(
                new RecentKind("drink", 12, today.minusDays(1)),
                new RecentKind("piano_practice", 3, today)));

        var response = new KindController(entries, userZone, clock).list();

        assertThat(response).containsExactly(
                new KindController.KindResponse("drink", "Drinks", 12, today.minusDays(1)),
                new KindController.KindResponse("piano_practice", "Piano practice", 3, today));
        verify(entries).recentCanonicalKinds(today.minusDays(89), today, 20);
    }
}
