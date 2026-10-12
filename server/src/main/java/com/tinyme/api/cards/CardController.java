package com.tinyme.api.cards;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.tinyme.domain.cards.model.view.JournalStreakView;
import com.tinyme.api.RangeParam;
import com.tinyme.domain.cards.model.view.CardView;
import com.tinyme.domain.entries.model.aggregate.Range;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@RestController
@RequestMapping("/cards")
public class CardController {
    private final CardApiService cards;

    public CardController(CardApiService cards) {
        this.cards = cards;
    }

    @GetMapping
    public CardsResponse list(@RequestParam(defaultValue = "week") String range,
                              @RequestParam(required = false) LocalDate date) {
        Range resolved = RangeParam.parse(range);
        var result = cards.list(resolved, date);
        return new CardsResponse(resolved.name().toLowerCase(Locale.ROOT), result.from(), result.to(), result.cards(),
                result.journalStreak());
    }

    @GetMapping("/{id}")
    public CardView get(@PathVariable UUID id,
                        @RequestParam(defaultValue = "week") String range,
                        @RequestParam(required = false) LocalDate date) {
        return cards.get(id, RangeParam.parse(range), date).orElseThrow(() -> notFound("Card not found"));
    }

    @PostMapping("/{id}/activate")
    public CardView activate(@PathVariable UUID id,
                             @RequestParam(defaultValue = "week") String range,
                             @RequestParam(required = false) LocalDate date) {
        return cards.activate(id, RangeParam.parse(range), date).orElseThrow(() -> notFound("Card not found"));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        return switch (cards.delete(id)) {
            case DELETED -> ResponseEntity.noContent().build();
            case SYSTEM_CARD -> throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "The Journal card can't be removed");
            case NOT_FOUND -> throw notFound("Card not found");
        };
    }

    @PutMapping("/order")
    public ResponseEntity<Void> order(@RequestBody OrderRequest request) {
        try {
            cards.order(request == null ? null : request.ids());
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, invalid.getMessage(), invalid);
        }
    }

    @GetMapping("/suggestions")
    public List<CardApiService.CardSuggestion> suggestions() {
        return cards.suggestions();
    }

    private static ResponseStatusException notFound(String message) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
    }

    public record CardsResponse(String range, LocalDate from, LocalDate to, List<CardView> cards,
                                @JsonProperty("journal_streak") JournalStreakView journalStreak) {
        public CardsResponse { cards = List.copyOf(cards); }
    }

    public record OrderRequest(List<UUID> ids) { }
}
