package com.tinyme.tools.handlers;

import com.tinyme.domain.entries.model.add.AddCommand;
import com.tinyme.domain.entries.model.add.AddResult;
import com.tinyme.domain.entries.service.EntryService;
import com.tinyme.tools.model.ToolContext;
import com.tinyme.tools.model.ToolHandler;
import com.tinyme.tools.model.ToolResult;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class EntriesAddTool implements ToolHandler {
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
        try {
            String kind = ToolInputs.requiredString(input, "kind").trim().toLowerCase(Locale.ROOT);
            if (!kind.matches("^[a-z][a-z0-9_]{1,31}$")) {
                throw new IllegalArgumentException(
                        "kind must contain 2–32 lowercase letters, digits or underscores, starting with a letter");
            }
            BigDecimal quantity = ToolInputs.optionalDecimal(input, "quantity");
            quantity = quantity == null ? BigDecimal.ONE : quantity;
            if (quantity.signum() <= 0) throw new IllegalArgumentException("quantity must be greater than zero");

            Instant ts = ToolInputs.optionalInstant(input, "ts");
            if (ts != null) ToolInputs.notFarInFuture(ts, ctx.now(), "ts");
            ts = ts == null ? ctx.now() : ts;

            Map<String, Object> data = ToolInputs.optionalObject(input, "data");
            data = data == null ? Map.of() : data;
            String text = ToolInputs.optionalString(input, "text");

            // EntryService derives local_day from ts and this zone.
            AddResult result = entries.add(new AddCommand(kind, quantity, text, data,
                    List.of(), ts, ctx.zone(), "chat"));
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
        } catch (IllegalArgumentException exception) {
            return invalid(exception.getMessage());
        }
    }

    private static ToolResult.Err invalid(String message) {
        return new ToolResult.Err("validation_error", message);
    }
}
