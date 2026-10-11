package com.tinyme.tools.handlers;

import com.tinyme.domain.cards.service.CardManagementService;
import com.tinyme.tools.model.ToolContext;
import com.tinyme.tools.model.ToolHandler;
import com.tinyme.tools.model.ToolResult;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.Map;
import java.util.UUID;

@Component
public class CardsDeleteTool implements ToolHandler {
    private final CardManagementService cards;

    public CardsDeleteTool(CardManagementService cards) {
        this.cards = cards;
    }

    @Override
    public String name() {
        return "cards_delete";
    }

    @Override
    public ToolResult handle(JsonNode input, ToolContext context) {
        if (input == null || !input.isObject()) return invalid("Input must be an object");
        try {
            UUID id = ToolInputs.requiredUuid(input, "id");
            return switch (cards.delete(id, context.now())) {
                case DELETED -> new ToolResult.Ok(Map.of("id", id.toString(), "deleted", true), "Deleted card");
                case NOT_FOUND -> new ToolResult.Err("not_found", "No card found with that id");
                case SYSTEM_CARD -> new ToolResult.Err("system_card", "The Journal card can't be removed");
            };
        } catch (IllegalArgumentException error) {
            return invalid(error.getMessage());
        }
    }

    private static ToolResult.Err invalid(String message) {
        return new ToolResult.Err("validation_error", message == null ? "Invalid card id" : message);
    }
}
