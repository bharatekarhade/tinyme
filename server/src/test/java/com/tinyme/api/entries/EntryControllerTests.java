package com.tinyme.api.entries;

import com.tinyme.agent.service.UserZone;
import com.tinyme.domain.entries.model.EntrySnapshot;
import com.tinyme.domain.entries.model.query.EntryPage;
import com.tinyme.domain.entries.repository.EntryRepository;
import com.tinyme.domain.entries.service.EntryService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EntryControllerTests {
    @Test
    void formatsEntryTimeWithTheConfiguredUserOffset() {
        EntryService service = mock(EntryService.class);
        UserZone userZone = mock(UserZone.class);
        when(userZone.current()).thenReturn(ZoneId.of("Asia/Tokyo"));
        UUID id = UUID.randomUUID();
        var snapshot = new EntrySnapshot(id, Instant.parse("2026-10-08T14:30:00Z"),
                LocalDate.of(2026, 10, 8), "drink", BigDecimal.ONE, "beer", Map.of("type", "beer"));
        when(service.page(any(), eq(50), eq(null))).thenReturn(new EntryPage(true, List.of(snapshot), "next"));

        var response = new EntryController(service, userZone).list("drink", null, null,
                null, null, null, 50);

        assertThat(response.entries()).hasSize(1);
        assertThat(response.entries().getFirst().ts()).isEqualTo("2026-10-08T14:30:00Z");
        assertThat(response.nextCursor()).isEqualTo("next");
    }

    @Test
    void invalidCursorReturnsBadRequest() {
        EntryService service = mock(EntryService.class);
        UserZone userZone = mock(UserZone.class);

        assertThatThrownBy(() -> new EntryController(service, userZone).list(null, null, null,
                null, null, "not-base64!", 50))
                .isInstanceOf(ResponseStatusException.class)
                .hasFieldOrPropertyWithValue("statusCode", HttpStatus.BAD_REQUEST);
    }
}
