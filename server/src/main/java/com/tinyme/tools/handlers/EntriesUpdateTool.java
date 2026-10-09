package com.tinyme.tools.handlers;

import com.tinyme.domain.entries.model.EntryWriteResult;
import com.tinyme.domain.entries.model.EntrySnapshot;
import com.tinyme.domain.entries.model.update.EntryPatch;
import com.tinyme.domain.entries.service.EntryService;
import com.tinyme.tools.model.ToolContext;
import com.tinyme.tools.model.ToolHandler;
import com.tinyme.tools.model.ToolResult;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Component
public class EntriesUpdateTool implements ToolHandler {
    private static final DateTimeFormatter SHORT_DATE = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH);

    private final EntryService entries;

    public EntriesUpdateTool(EntryService entries) {
        this.entries = entries;
    }

    @Override
    public String name() {
        return "entries_update";
    }

    @Override
    public ToolResult handle(JsonNode input, ToolContext ctx) {
        if (input == null || !input.isObject()) return invalid("Input must be an object");

        try {
            Instant ts = ToolInputs.optionalInstant(input, "ts");
            if (ts != null) ToolInputs.notFarInFuture(ts, ctx.now(), "ts");
            EntryPatch patch = new EntryPatch(
                    ToolInputs.requiredUuid(input, "id"),
                    ToolInputs.optionalString(input, "kind"),
                    ToolInputs.optionalDecimal(input, "quantity"),
                    ToolInputs.optionalString(input, "text"),
                    ToolInputs.optionalObject(input, "data"),
                    ts);

            Optional<EntryWriteResult> updated = entries.update(patch, ctx.zone());
            if (updated.isEmpty()) {
                return new ToolResult.Err("not_found", "No live entry found with that id");
            }
            EntryWriteResult result = updated.get();
            return new ToolResult.Ok(output(result, ctx), summary(result));
        } catch (IllegalArgumentException exception) {
            return invalid(exception.getMessage());
        }
    }

    private static Map<String, Object> output(EntryWriteResult result, ToolContext ctx) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("entry", ToolOutputs.entry(result.entry(), ctx.zone()));
        output.put("day_total", result.dayTotal());
        return output;
    }

    private static String summary(EntryWriteResult result) {
        EntrySnapshot entry = result.entry();
        StringBuilder summary = new StringBuilder("Updated ").append(entry.kind());
        Object type = entry.data().get("type");
        if (type instanceof String typeName) summary.append(" · ").append(typeName);
        return summary.append(": ").append(result.dayTotal().stripTrailingZeros().toPlainString())
                .append(" on ").append(SHORT_DATE.format(entry.localDay())).toString();
    }

    private static ToolResult.Err invalid(String message) {
        return new ToolResult.Err("validation_error", message == null ? "Invalid entry update" : message);
    }
}
