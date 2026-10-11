package com.tinyme.api.cards;

import com.tinyme.domain.cards.entity.CardEntity;
import com.tinyme.domain.cards.model.CardSpecParser;
import com.tinyme.domain.cards.model.CardStatus;
import com.tinyme.domain.cards.repository.CardRepository;
import com.tinyme.domain.cards.service.CardEvaluator;
import com.tinyme.domain.cards.service.CardManagementService;
import com.tinyme.domain.cards.model.view.CardView;
import com.tinyme.domain.entries.model.aggregate.Range;
import com.tinyme.domain.entries.service.EntryService;
import com.tinyme.settings.UserZone;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CardApiServiceTests {
    private final CardRepository cards = mock(CardRepository.class);
    private final CardEvaluator evaluator = mock(CardEvaluator.class);
    private final CardManagementService management = mock(CardManagementService.class);
    private final EntryService entries = mock(EntryService.class);
    private final UserZone userZone = mock(UserZone.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-07T03:00:00Z"), ZoneId.of("UTC"));

    private CardApiService service() {
        when(userZone.current()).thenReturn(ZoneId.of("Asia/Tokyo"));
        return new CardApiService(cards, evaluator, management, new CardSpecParser(), entries, userZone, clock);
    }

    @Test
    void activationAssignsLastPositionAndDoesNotChangeAnAlreadyActiveCard() {
        var draft = new CardEntity("sleep", "Sleep", Map.of("v", 1), CardStatus.DRAFT,
                false, 0, null);
        UUID id = UUID.randomUUID();
        when(management.activate(id, clock.instant())).thenReturn(java.util.Optional.of(
                new CardManagementService.ActivationResult(draft, List.of(), true)));
        when(evaluator.evaluate(draft, Range.WEEK)).thenReturn(new CardView(id, "sleep", "Sleep",
                "bed", "mint", "surface", "active", false, null, List.of(), null));

        assertThat(service().activate(id, Range.WEEK)).isPresent();

        verify(management).activate(id, clock.instant());
        verify(evaluator).evaluate(draft, Range.WEEK);
    }

    @Test
    void selectedDateAnchorsListPeriodAndCardEvaluation() {
        LocalDate selected = LocalDate.parse("2026-10-07");
        var card = new CardEntity("sleep", "Sleep", Map.of("v", 1), CardStatus.ACTIVE,
                false, 0, clock.instant());
        when(cards.activeCards()).thenReturn(List.of(card));
        when(evaluator.evaluate(card, Range.WEEK, selected)).thenReturn(new CardView(null, "sleep", "Sleep",
                "bed", "mint", "surface", "active", false, null, List.of(), null));

        var result = service().list(Range.WEEK, selected);

        assertThat(result.from()).isEqualTo(LocalDate.parse("2026-10-05"));
        assertThat(result.to()).isEqualTo(LocalDate.parse("2026-10-11"));
        verify(evaluator).evaluate(card, Range.WEEK, selected);
    }

    @Test
    void orderingRequiresTheExactActiveNonSystemSetAndSetsSequentialPositions() {
        UUID firstId = UUID.randomUUID();
        UUID secondId = UUID.randomUUID();
        CardEntity first = mock(CardEntity.class);
        CardEntity second = mock(CardEntity.class);
        CardEntity journal = mock(CardEntity.class);
        when(first.getId()).thenReturn(firstId);
        when(second.getId()).thenReturn(secondId);
        when(first.isSystem()).thenReturn(false);
        when(second.isSystem()).thenReturn(false);
        when(journal.isSystem()).thenReturn(true);
        when(cards.activeCards()).thenReturn(List.of(journal, first, second));

        service().order(List.of(secondId, firstId));

        verify(second).moveTo(1);
        verify(first).moveTo(2);
        verify(cards).save(second);
        verify(cards).save(first);
        assertThatThrownBy(() -> service().order(List.of(firstId)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("match the active non-system cards exactly");
    }

    @Test
    void suggestionsUseRecentUntrackedKindsThenPadWithFixedExamples() {
        CardEntity tracker = new CardEntity("drinks", "Drinks", Map.of(
                "v", 1,
                "title", "Drinks",
                "icon", "cup",
                "accent", "mint",
                "tone", "surface",
                "blocks", List.of(Map.of(
                        "type", "stat", "label", "count",
                        "metric", Map.of("kinds", List.of("drink"), "op", "count"),
                        "period", "range"))),
                CardStatus.ACTIVE, false, 1, clock.instant());
        when(cards.activeCards()).thenReturn(List.of(tracker));
        when(entries.recentKinds(any(), any(), anyLong())).thenReturn(List.of("drink", "sleep"));

        var result = service().suggestions();

        assertThat(result).containsExactly(
                new CardApiService.CardSuggestion("Track my sleep", "sleep"),
                new CardApiService.CardSuggestion("Track my water intake", "water"),
                new CardApiService.CardSuggestion("Count my steps", "steps"));
    }
}
