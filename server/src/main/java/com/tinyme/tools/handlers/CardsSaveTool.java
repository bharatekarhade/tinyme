package com.tinyme.tools.handlers;

import com.tinyme.domain.cards.service.CardManagementService;
import com.tinyme.domain.cards.model.view.BlockView;
import com.tinyme.domain.cards.model.view.BreakdownView;
import com.tinyme.domain.cards.model.view.CardView;
import com.tinyme.domain.cards.model.view.ChartView;
import com.tinyme.domain.cards.model.view.CheckinsView;
import com.tinyme.domain.cards.model.view.GoalsView;
import com.tinyme.domain.cards.model.view.LatestView;
import com.tinyme.domain.cards.model.view.ProgressView;
import com.tinyme.domain.cards.model.view.StatView;
import com.tinyme.domain.cards.model.view.UnavailableView;
import com.tinyme.tools.model.ToolContext;
import com.tinyme.tools.model.ToolHandler;
import com.tinyme.tools.model.ToolResult;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Component
public class CardsSaveTool implements ToolHandler {
    private final CardManagementService cards;

    public CardsSaveTool(CardManagementService cards) {
        this.cards = cards;
    }

    @Override
    public String name() {
        return "cards_save";
    }

    @Override
    public ToolResult handle(JsonNode input, ToolContext context) {
        if (input == null || !input.isObject()) return invalid("Input must be an object");
        try {
            String rawId = ToolInputs.optionalString(input, "id");
            UUID id = rawId == null ? null : parseId(rawId);
            String status = ToolInputs.optionalString(input, "status");
            if (status != null && !status.equals("active")) {
                throw new IllegalArgumentException("status must be 'active'");
            }
            boolean activate = "active".equals(status);
            JsonNode spec = input.get("spec");
            return switch (cards.save(id, spec, activate, context.now())) {
                case CardManagementService.SaveOutcome.Saved saved -> saved(saved);
                case CardManagementService.SaveOutcome.Duplicate duplicate -> new ToolResult.Err("duplicate",
                        "A card with this title already exists (id " + duplicate.existingId()
                                + "). Pass that id to update it.");
                case CardManagementService.SaveOutcome.NotFound ignored -> new ToolResult.Err("not_found",
                        "No live card found with that id");
                case CardManagementService.SaveOutcome.SystemCard ignored -> new ToolResult.Err("system_card",
                        "The Journal card is managed by TinyMe. Pick another title.");
            };
        } catch (IllegalArgumentException error) {
            return invalid(error.getMessage());
        }
    }

    private static ToolResult.Ok saved(CardManagementService.SaveOutcome.Saved saved) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("id", saved.card().id().toString());
        output.put("status", saved.card().status());
        output.put("title", saved.card().title());
        output.put("headline", headlines(saved.card()));
        output.put("warnings", saved.warnings());
        output.put("ref", Map.of("card_id", saved.card().id().toString()));
        String summary;
        if (saved.created()) {
            summary = "Saved draft card · " + saved.card().title();
        } else if (saved.activatedNow()) {
            summary = "Added card to Home · " + saved.card().title();
        } else if (saved.alreadyActive()) {
            summary = "Card already on Home · " + saved.card().title();
        } else {
            summary = "Updated card · " + saved.card().title();
        }
        return new ToolResult.Ok(output, summary);
    }

    private static java.util.List<Map<String, Object>> headlines(CardView card) {
        java.util.List<Map<String, Object>> result = new java.util.ArrayList<>();
        if (card.blocks() == null) return result;
        for (BlockView block : card.blocks()) {
            switch (block) {
                case StatView stat -> result.add(headline("stat", stat.label(), stat.value(), stat.goal()));
                case ProgressView progress -> result.add(headline("progress", progress.label(), progress.value(), progress.goal()));
                case GoalsView goals -> goals.items().forEach(item ->
                        result.add(headline("goals", item.label(), item.value(), item.goal())));
                case ChartView chart -> chart.points().stream().filter(point -> !"future".equals(point.state()))
                        .reduce((first, last) -> last).ifPresent(point -> {
                            Map<String, Object> item = headline("chart", chart.label(), point.value(), null);
                            item.put("period", point.period());
                            result.add(item);
                        });
                case CheckinsView checkins -> result.add(headline("checkins", checkins.label(),
                        java.math.BigDecimal.valueOf(checkins.doneCount()),
                        checkins.goal() == null ? null : java.math.BigDecimal.valueOf(checkins.goal())));
                case BreakdownView breakdown -> result.add(headline("breakdown", breakdown.label(), breakdown.total(), null));
                case LatestView latest -> result.add(headline("latest", latest.label(), null, null));
                case UnavailableView ignored -> { }
            }
        }
        return result;
    }

    private static Map<String, Object> headline(String block, String label,
                                                 java.math.BigDecimal value, java.math.BigDecimal goal) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("block", block);
        item.put("label", label);
        item.put("value", value);
        if (goal != null) item.put("goal", goal);
        return item;
    }

    private static UUID parseId(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ignored) {
            throw new IllegalArgumentException("id must be a UUID");
        }
    }

    private static ToolResult.Err invalid(String message) {
        return new ToolResult.Err("validation_error", message == null ? "Invalid card" : message);
    }
}
