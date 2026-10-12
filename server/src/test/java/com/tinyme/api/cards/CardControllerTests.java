package com.tinyme.api.cards;

import com.tinyme.domain.entries.model.aggregate.Range;
import com.tinyme.domain.cards.service.CardManagementService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CardControllerTests {
    @Test
    void defaultsListToWeekAndUsesLowercaseRange() {
        CardApiService service = mock(CardApiService.class);
        when(service.list(Range.WEEK, null)).thenReturn(new CardApiService.CardList(Range.WEEK,
                LocalDate.parse("2026-10-05"), LocalDate.parse("2026-10-11"), List.of(), null));

        var result = new CardController(service).list("week", null);

        assertThat(result.range()).isEqualTo("week");
        assertThat(result.from()).isEqualTo(LocalDate.parse("2026-10-05"));
        assertThat(result.to()).isEqualTo(LocalDate.parse("2026-10-11"));
    }

    @Test
    void acceptsSelectedDateAndUsesItToAnchorTheRequestedRange() {
        CardApiService service = mock(CardApiService.class);
        LocalDate selected = LocalDate.parse("2026-10-07");
        when(service.list(Range.WEEK, selected)).thenReturn(new CardApiService.CardList(Range.WEEK,
                LocalDate.parse("2026-10-05"), LocalDate.parse("2026-10-11"), List.of(), null));

        var result = new CardController(service).list("week", selected);

        assertThat(result.from()).isEqualTo(LocalDate.parse("2026-10-05"));
        assertThat(result.to()).isEqualTo(LocalDate.parse("2026-10-11"));
    }

    @Test
    void invalidRangeReturnsBadRequest() {
        assertThatThrownBy(() -> new CardController(mock(CardApiService.class)).list("quarter", null))
                .isInstanceOf(ResponseStatusException.class)
                .hasFieldOrPropertyWithValue("statusCode", HttpStatus.BAD_REQUEST)
                .hasMessageContaining("range must be day, week, month, or year");
    }

    @Test
    void deletedOrUnknownCardReturnsNotFound() {
        CardApiService service = mock(CardApiService.class);
        when(service.get(UUID.fromString("00000000-0000-0000-0000-000000000001"), Range.WEEK, null))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> new CardController(service)
                .get(UUID.fromString("00000000-0000-0000-0000-000000000001"), "week", null))
                .isInstanceOf(ResponseStatusException.class)
                .hasFieldOrPropertyWithValue("statusCode", HttpStatus.NOT_FOUND);
    }

    @Test
    void systemCardCannotBeDeleted() {
        CardApiService service = mock(CardApiService.class);
        UUID id = UUID.randomUUID();
        when(service.delete(id))
                .thenReturn(CardManagementService.DeleteOutcome.SYSTEM_CARD);

        assertThatThrownBy(() -> new CardController(service).delete(id))
                .isInstanceOf(ResponseStatusException.class)
                .hasFieldOrPropertyWithValue("statusCode", HttpStatus.CONFLICT)
                .hasMessageContaining("Journal card can't be removed");
    }
}
