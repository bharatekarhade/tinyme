package com.tinyme.tools.handlers;

import com.tinyme.domain.cards.service.CardManagementService;
import com.tinyme.tools.model.ToolContext;
import com.tinyme.tools.model.ToolHandler;
import com.tinyme.tools.model.ToolResult;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

@Component
public class CardsListTool implements ToolHandler {
    private final CardManagementService cards;

    public CardsListTool(CardManagementService cards) {
        this.cards = cards;
    }

    @Override
    public String name() {
        return "cards_list";
    }

    @Override
    public ToolResult handle(JsonNode input, ToolContext context) {
        if (input == null || !input.isObject() || !input.isEmpty()) {
            return new ToolResult.Err("validation_error", "Input must be an empty object");
        }
        List<Map<String, Object>> result = cards.list();
        return new ToolResult.Ok(result, "Listed " + result.size() + " cards");
    }
}
