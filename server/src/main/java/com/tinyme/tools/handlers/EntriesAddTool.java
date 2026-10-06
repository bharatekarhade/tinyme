package com.tinyme.tools.handlers;

import com.tinyme.domain.entries.model.AddCommand;
import com.tinyme.domain.entries.model.AddResult;
import com.tinyme.domain.entries.service.EntryService;
import com.tinyme.tools.model.ToolContext;
import com.tinyme.tools.model.ToolHandler;
import com.tinyme.tools.model.ToolResult;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class EntriesAddTool implements ToolHandler {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TypeReference<Map<String, Object>> DATA_TYPE = new TypeReference<>() {};

    private final EntryService entries;

    public EntriesAddTool(EntryService entries) {
        this.entries = entries;
    }

    @Override
    public String name() {
        return "entries_add";
    }

    @Override
    public ToolResult handle(JsonNode input, ToolContext ctx) {
        if (input == null || !input.isObject()) {
            return invalid("Input must be an object");
        }
        JsonNode kindNode = input.get("kind");
        if (kindNode == null || !kindNode.isString()) {
            return invalid("kind must be a string");
        }
        String kind = kindNode.stringValue().trim().toLowerCase(Locale.ROOT);
        if (!kind.matches("^[a-z][a-z0-9_]{1,31}$")) {
            return invalid("kind must contain 2–32 lowercase letters, digits or underscores, starting with a letter");
        }

        JsonNode quantityNode = input.get("quantity");
        if (quantityNode != null && !quantityNode.isNumber()) {
            return invalid("quantity must be a number greater than zero");
        }
        BigDecimal quantity = quantityNode == null ? BigDecimal.ONE : quantityNode.decimalValue();
        if (quantity.signum() <= 0) {
            return invalid("quantity must be greater than zero");
        }

        Instant ts = ctx.now();
        JsonNode tsNode = input.get("ts");
        if (tsNode != null) {
            if (!tsNode.isString()) {
                return invalid("ts must be an ISO date-time with an offset");
            }
            try {
                ts = OffsetDateTime.parse(tsNode.stringValue()).toInstant();
            } catch (DateTimeParseException exception) {
                return invalid("ts must be an ISO date-time with an offset");
            }
        }
        if (ts.isAfter(ctx.now().plus(1, ChronoUnit.DAYS))) {
            return invalid("ts must not be more than one day in the future");
        }

        JsonNode dataNode = input.get("data");
        if (dataNode != null && !dataNode.isObject()) {
            return invalid("data must be an object");
        }
        Map<String, Object> data = dataNode == null ? Map.of() : JSON.convertValue(dataNode, DATA_TYPE);
        JsonNode textNode = input.get("text");
        if (textNode != null && !textNode.isString()) {
            return invalid("text must be a string");
        }
        String text = textNode == null ? null : textNode.stringValue();

        // EntryService derives local_day from ts and this zone.
        AddResult result = entries.add(new AddCommand(kind, quantity, text, data, List.of(), ts, ctx.zone(), "chat"));
        String label = result.kind();
        if (data.get("type") instanceof String type) {
            label += " (" + type + ")";
        }
        return new ToolResult.Ok(Map.of(
                "id", result.id().toString(),
                "kind", result.kind(),
                "local_day", result.localDay().toString(),
                "quantity", result.quantity(),
                "today_total", result.todayTotal()),
                "Logged " + label + ", " + result.todayTotal().stripTrailingZeros().toPlainString() + " today");
    }

    private static ToolResult.Err invalid(String message) {
        return new ToolResult.Err("validation_error", message);
    }
}
