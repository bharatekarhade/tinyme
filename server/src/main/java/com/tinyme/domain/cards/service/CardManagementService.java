package com.tinyme.domain.cards.service;

import com.tinyme.domain.cards.entity.CardEntity;
import com.tinyme.domain.cards.model.Block;
import com.tinyme.domain.cards.model.CardSpec;
import com.tinyme.domain.cards.model.CardSpecParser;
import com.tinyme.domain.cards.model.CardSpecWriter;
import com.tinyme.domain.cards.model.CardStatus;
import com.tinyme.domain.cards.model.BreakdownBlock;
import com.tinyme.domain.cards.model.GoalsBlock;
import com.tinyme.domain.cards.model.LatestBlock;
import com.tinyme.domain.cards.model.MetricBlock;
import com.tinyme.domain.cards.model.MetricSpec;
import com.tinyme.domain.cards.model.view.BlockView;
import com.tinyme.domain.cards.model.view.CardView;
import com.tinyme.domain.cards.repository.CardRepository;
import com.tinyme.domain.entries.repository.EntryKindRepository;
import com.tinyme.domain.entries.service.EntryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
public class CardManagementService {
    private static final Logger log = LoggerFactory.getLogger(CardManagementService.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final CardRepository cards;
    private final CardSpecParser parser;
    private final CardSpecWriter writer;
    private final EntryKindRepository entryKinds;
    private final EntryService entries;
    private final CardEvaluator evaluator;

    public CardManagementService(CardRepository cards, CardSpecParser parser, CardSpecWriter writer,
                                 EntryKindRepository entryKinds, EntryService entries, CardEvaluator evaluator) {
        this.cards = Objects.requireNonNull(cards, "cards");
        this.parser = Objects.requireNonNull(parser, "parser");
        this.writer = Objects.requireNonNull(writer, "writer");
        this.entryKinds = Objects.requireNonNull(entryKinds, "entryKinds");
        this.entries = Objects.requireNonNull(entries, "entries");
        this.evaluator = Objects.requireNonNull(evaluator, "evaluator");
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (CardEntity card : cards.liveUserCards()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", card.getId().toString());
            item.put("slug", card.getSlug());
            item.put("title", card.getTitle());
            item.put("status", CardView.wire(card.getStatus()));
            try {
                CardSpec spec = parser.parse(JSON.valueToTree(card.getSpec()));
                item.put("blocks", spec.blocks().stream().map(this::blockSummary).toList());
            } catch (RuntimeException error) {
                log.warn("Card list spec unavailable: card={}, id={}", card.getSlug(), card.getId(), error);
                item.put("blocks", List.of());
            }
            result.add(item);
        }
        return List.copyOf(result);
    }

    @Transactional
    public SaveOutcome save(UUID id, tools.jackson.databind.JsonNode specNode, boolean activate, Instant now) {
        Objects.requireNonNull(now, "now");
        CardEntity card;
        CardSpec spec = null;
        boolean created = id == null;
        boolean activatedNow = false;
        boolean alreadyActive = false;
        boolean wasActive = false;
        boolean specChanged = false;
        List<String> warnings = List.of();

        if (created) {
            if (specNode == null || specNode.isNull()) {
                throw new IllegalArgumentException("spec is required when creating a card");
            }
            spec = parser.parse(specNode);
            String slug = CardSlugger.base(spec.title());
            if (slug.equals("journal")) return new SaveOutcome.SystemCard();
            var duplicate = cards.findBySlug(slug);
            if (duplicate.isPresent()) return new SaveOutcome.Duplicate(duplicate.get().getId());
            card = new CardEntity(slug, spec.title(), stored(spec), CardStatus.DRAFT, false, 0, null);
            card = cards.save(card);
        } else {
            card = cards.findById(id).orElse(null);
            if (card == null) return new SaveOutcome.NotFound();
            if (card.isSystem() || card.getSlug().equals("journal")) return new SaveOutcome.SystemCard();
            wasActive = card.getStatus() == CardStatus.ACTIVE;
            if (specNode != null && !specNode.isNull()) {
                spec = parser.parse(specNode);
                if (CardSlugger.base(spec.title()).equals("journal")) return new SaveOutcome.SystemCard();
                card.replaceSpec(spec.title(), stored(spec));
                specChanged = true;
            } else if (!activate) {
                throw new IllegalArgumentException("provide a spec or status: active");
            }
            if (activate) {
                ActivationResult activation = activate(id, now)
                        .orElseThrow(() -> new IllegalStateException("Card disappeared during activation"));
                card = activation.card();
                activatedNow = activation.activatedNow();
                warnings = activation.warnings();
                alreadyActive = wasActive && !specChanged;
            } else {
                card = cards.save(card);
                if (spec == null) spec = parse(card);
                warnings = inspectAndPrepareKinds(spec, wasActive && specChanged);
            }
        }

        if (created) warnings = inspectAndPrepareKinds(spec, false);
        CardView preview = evaluator.evaluate(card, com.tinyme.domain.entries.model.aggregate.Range.WEEK);
        return new SaveOutcome.Saved(preview, warnings, created, activatedNow, alreadyActive);
    }

    @Transactional
    public java.util.Optional<ActivationResult> activate(UUID id, Instant now) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(now, "now");
        CardEntity card = cards.findById(id).orElse(null);
        if (card == null) return java.util.Optional.empty();

        boolean activatedNow = card.getStatus() != CardStatus.ACTIVE;
        if (activatedNow) {
            card.activate(cards.nextActivePosition(), now);
            cards.save(card);
        }
        CardSpec spec = parse(card);
        List<String> warnings = inspectAndPrepareKinds(spec, true);
        return java.util.Optional.of(new ActivationResult(card, warnings, activatedNow));
    }

    @Transactional
    public DeleteOutcome delete(UUID id, Instant now) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(now, "now");
        CardEntity card = cards.findByIdIncludingDeleted(id).orElse(null);
        if (card == null) return DeleteOutcome.NOT_FOUND;
        if (card.isSystem() || card.getSlug().equals("journal")) return DeleteOutcome.SYSTEM_CARD;
        if (card.getDeletedAt() == null) {
            card.softDelete(now);
            cards.save(card);
        }
        return DeleteOutcome.DELETED;
    }

    private Map<String, Object> blockSummary(Block block) {
        return switch (block) {
            case MetricBlock metric -> Map.of("type", blockType(block), "label", metric.label());
            case GoalsBlock goals -> Map.of("type", "goals",
                    "label", goals.label().isBlank() ? "goals" : goals.label());
            case LatestBlock latest -> Map.of("type", "latest", "label", latest.label());
        };
    }

    private List<String> inspectAndPrepareKinds(CardSpec spec, boolean prepareUnknownKinds) {
        Map<String, Map<String, String>> hints = new LinkedHashMap<>();
        for (Block block : spec.blocks()) {
            switch (block) {
                case MetricBlock metric -> addMetricHints(metric.metric(), hints);
                case GoalsBlock goals -> goals.items().forEach(item -> addMetricHints(item.metric(), hints));
                case LatestBlock latest -> latest.kinds().forEach(kind -> latest.fields().forEach(field ->
                        addHint(hints, kind, field, "any")));
            }
            if (block instanceof BreakdownBlock breakdown) {
                breakdown.metric().kinds().forEach(kind -> addHint(hints, kind, breakdown.by(), "string"));
            }
        }

        List<String> warnings = new ArrayList<>();
        for (Map.Entry<String, Map<String, String>> use : hints.entrySet()) {
            String kind = use.getKey();
            var existing = entryKinds.resolveExisting(kind);
            if (existing.isEmpty()) {
                if (prepareUnknownKinds) {
                    entryKinds.ensureCardKind(kind, "Tracked by the " + spec.title() + " card", use.getValue());
                }
                warnings.add("no " + kind + " entries yet; they will count once logged");
                continue;
            }
            String resolvedKind = existing.get();
            for (String field : use.getValue().keySet()) {
                if (!entries.hasDataField(resolvedKind, field)) {
                    warnings.add("no " + resolvedKind + " entries have data." + field + " yet");
                }
            }
        }
        return List.copyOf(new LinkedHashSet<>(warnings));
    }

    private static void addMetricHints(MetricSpec metric, Map<String, Map<String, String>> hints) {
        metric.kinds().forEach(kind -> {
            if (!"*".equals(kind)) hints.computeIfAbsent(kind, ignored -> new LinkedHashMap<>());
            if (metric.field() != null) addHint(hints, kind, metric.field(), "number");
            metric.where().forEach((field, value) -> addHint(hints, kind, field, typeOf(value)));
        });
    }

    private static void addHint(Map<String, Map<String, String>> hints, String kind, String field, String type) {
        if ("*".equals(kind)) return;
        hints.computeIfAbsent(kind, ignored -> new LinkedHashMap<>()).putIfAbsent(field, type);
    }

    private static String typeOf(Object value) {
        if (value instanceof Number) return "number";
        if (value instanceof Boolean) return "boolean";
        if (value instanceof Map<?, ?> || value instanceof List<?>) return "object";
        return "string";
    }

    private static String blockType(Block block) {
        return switch (block) {
            case com.tinyme.domain.cards.model.StatBlock ignored -> "stat";
            case com.tinyme.domain.cards.model.ProgressBlock ignored -> "progress";
            case GoalsBlock ignored -> "goals";
            case com.tinyme.domain.cards.model.ChartBlock ignored -> "chart";
            case com.tinyme.domain.cards.model.CheckinsBlock ignored -> "checkins";
            case BreakdownBlock ignored -> "breakdown";
            case LatestBlock ignored -> "latest";
        };
    }

    private Map<String, Object> stored(CardSpec spec) {
        return JSON.convertValue(writer.write(spec), MAP_TYPE);
    }

    private CardSpec parse(CardEntity card) {
        var node = JSON.valueToTree(card.getSpec());
        return card.isSystem() ? parser.parseTrusted(node) : parser.parse(node);
    }

    public record ActivationResult(CardEntity card, List<String> warnings, boolean activatedNow) {
        public ActivationResult { warnings = List.copyOf(warnings); }
    }

    public sealed interface SaveOutcome permits SaveOutcome.Saved, SaveOutcome.Duplicate,
            SaveOutcome.NotFound, SaveOutcome.SystemCard {
        record Saved(CardView card, List<String> warnings, boolean created, boolean activatedNow,
                     boolean alreadyActive)
                implements SaveOutcome {
            public Saved { warnings = List.copyOf(warnings); }
        }
        record Duplicate(UUID existingId) implements SaveOutcome {}
        record NotFound() implements SaveOutcome {}
        record SystemCard() implements SaveOutcome {}
    }

    public enum DeleteOutcome { DELETED, NOT_FOUND, SYSTEM_CARD }
}
