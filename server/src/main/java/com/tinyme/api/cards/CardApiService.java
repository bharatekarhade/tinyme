package com.tinyme.api.cards;

import com.tinyme.domain.cards.entity.CardEntity;
import com.tinyme.domain.cards.model.Block;
import com.tinyme.domain.cards.model.CardSpec;
import com.tinyme.domain.cards.model.CardSpecParser;
import com.tinyme.domain.cards.model.GoalsBlock;
import com.tinyme.domain.cards.model.LatestBlock;
import com.tinyme.domain.cards.model.MetricBlock;
import com.tinyme.domain.cards.model.view.CardView;
import com.tinyme.domain.cards.repository.CardRepository;
import com.tinyme.domain.cards.service.CardEvaluator;
import com.tinyme.domain.cards.service.CardManagementService;
import com.tinyme.domain.entries.model.aggregate.DateRange;
import com.tinyme.domain.entries.model.aggregate.Period;
import com.tinyme.domain.entries.model.aggregate.Range;
import com.tinyme.domain.entries.service.EntryService;
import com.tinyme.settings.UserZone;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
public class CardApiService {
    private static final Logger log = LoggerFactory.getLogger(CardApiService.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final int SUGGESTION_LIMIT = 3;
    private static final long SUGGESTION_MINIMUM_ENTRIES = 3;
    private static final List<CardSuggestion> FIXED_SUGGESTIONS = List.of(
            new CardSuggestion("Track my water intake", "water"),
            new CardSuggestion("Count my steps", "steps"));

    private final CardRepository cards;
    private final CardEvaluator evaluator;
    private final CardManagementService management;
    private final CardSpecParser parser;
    private final EntryService entries;
    private final UserZone userZone;
    private final Clock clock;

    public CardApiService(CardRepository cards, CardEvaluator evaluator, CardManagementService management,
                          CardSpecParser parser,
                          EntryService entries, UserZone userZone, Clock clock) {
        this.cards = Objects.requireNonNull(cards, "cards");
        this.evaluator = Objects.requireNonNull(evaluator, "evaluator");
        this.management = Objects.requireNonNull(management, "management");
        this.parser = Objects.requireNonNull(parser, "parser");
        this.entries = Objects.requireNonNull(entries, "entries");
        this.userZone = Objects.requireNonNull(userZone, "userZone");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Transactional(readOnly = true)
    public CardList list(Range range) {
        return list(range, null);
    }

    @Transactional(readOnly = true)
    public CardList list(Range range, LocalDate selectedDate) {
        LocalDate anchor = selectedDate == null ? today() : selectedDate;
        DateRange dates = Period.RANGE.resolve(range, anchor);
        List<CardView> views = cards.activeCards().stream()
                .map(card -> selectedDate == null ? evaluator.evaluate(card, range)
                        : evaluator.evaluate(card, range, selectedDate)).toList();
        return new CardList(range, dates.from(), dates.to(), views);
    }

    @Transactional(readOnly = true)
    public Optional<CardView> get(UUID id, Range range) {
        return get(id, range, null);
    }

    @Transactional(readOnly = true)
    public Optional<CardView> get(UUID id, Range range, LocalDate selectedDate) {
        return cards.findById(id).map(card -> selectedDate == null ? evaluator.evaluate(card, range)
                : evaluator.evaluate(card, range, selectedDate));
    }

    @Transactional
    public Optional<CardView> activate(UUID id, Range range) {
        return activate(id, range, null);
    }

    @Transactional
    public Optional<CardView> activate(UUID id, Range range, LocalDate selectedDate) {
        return management.activate(id, Instant.now(clock))
                .map(activation -> selectedDate == null ? evaluator.evaluate(activation.card(), range)
                        : evaluator.evaluate(activation.card(), range, selectedDate));
    }

    @Transactional
    public CardManagementService.DeleteOutcome delete(UUID id) {
        return management.delete(id, Instant.now(clock));
    }

    @Transactional
    public void order(List<UUID> ids) {
        if (ids == null) throw new IllegalArgumentException("ids is required");
        if (ids.stream().anyMatch(Objects::isNull)) throw new IllegalArgumentException("ids cannot contain null");
        if (new LinkedHashSet<>(ids).size() != ids.size()) {
            throw new IllegalArgumentException("ids must not contain duplicates");
        }
        List<CardEntity> current = cards.activeCards().stream().filter(card -> !card.isSystem()).toList();
        Set<UUID> expected = current.stream().map(CardEntity::getId)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        if (ids.size() != current.size() || !expected.equals(new LinkedHashSet<>(ids))) {
            throw new IllegalArgumentException("ids must match the active non-system cards exactly");
        }
        var byId = current.stream().collect(java.util.stream.Collectors.toMap(CardEntity::getId, card -> card));
        for (int i = 0; i < ids.size(); i++) {
            CardEntity card = byId.get(ids.get(i));
            card.moveTo(i + 1);
            cards.save(card);
        }
    }

    @Transactional(readOnly = true)
    public List<CardSuggestion> suggestions() {
        LocalDate today = today();
        LocalDate from = today.minusDays(29);
        Set<String> used = new LinkedHashSet<>();
        for (CardEntity card : cards.activeCards()) {
            if (card.isSystem()) continue;
            try {
                CardSpec spec = parser.parse(JSON.valueToTree(card.getSpec()));
                for (Block block : spec.blocks()) collectKinds(block, used);
            } catch (RuntimeException invalidSpec) {
                log.warn("Skipping kind suppression for invalid active card id={}", card.getId(), invalidSpec);
            }
        }

        List<CardSuggestion> result = new ArrayList<>();
        for (String kind : entries.recentKinds(from, today, SUGGESTION_MINIMUM_ENTRIES)) {
            if (used.contains(kind)) continue;
            String words = kind.replace('_', ' ');
            result.add(new CardSuggestion("Track my " + words, kind));
            if (result.size() == SUGGESTION_LIMIT) return List.copyOf(result);
        }
        for (CardSuggestion fixed : FIXED_SUGGESTIONS) {
            if (result.size() == SUGGESTION_LIMIT) break;
            if (!used.contains(fixed.kind()) && result.stream().noneMatch(s -> s.kind().equals(fixed.kind()))) {
                result.add(fixed);
            }
        }
        return List.copyOf(result);
    }

    private static void collectKinds(Block block, Set<String> into) {
        if (block instanceof MetricBlock metric) into.addAll(metric.metric().kinds());
        else if (block instanceof GoalsBlock goals) {
            goals.items().forEach(item -> into.addAll(item.metric().kinds()));
        } else if (block instanceof LatestBlock latest) into.addAll(latest.kinds());
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(userZone.current()));
    }

    public record CardList(Range range, LocalDate from, LocalDate to, List<CardView> cards) {
        public CardList { cards = List.copyOf(cards); }
    }

    public record CardSuggestion(String text, String kind) { }
}
