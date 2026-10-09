package com.tinyme.tools.handlers;

import com.tinyme.domain.entries.model.EntrySnapshot;
import com.tinyme.domain.entries.model.EntryWriteResult;
import com.tinyme.domain.entries.service.EntryService;
import com.tinyme.tools.model.ToolContext;
import com.tinyme.tools.model.ToolHandler;
import com.tinyme.tools.model.ToolResult;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Component
public class EntriesDeleteTool implements ToolHandler {
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("d MMM HH:mm", Locale.ENGLISH);
    private static final DateTimeFormatter SHORT_DATE = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH);

    private final EntryService entries;

    public EntriesDeleteTool(EntryService entries) {
        this.entries = entries;
    }

    @Override
    public String name() {
        return "entries_delete";
    }

    @Override
    public ToolResult handle(JsonNode input, ToolContext context) {
        if (input == null || !input.isObject()) return invalid("Input must be an object");
        try {
            var id = ToolInputs.requiredUuid(input, "id");
            Optional<EntryWriteResult> deleted = entries.delete(id, context.now());
            if (deleted.isEmpty()) return new ToolResult.Err("not_found", "No entry found with that id");

            EntryWriteResult result = deleted.get();
            return new ToolResult.Ok(output(result, context), summary(result, context));
        } catch (IllegalArgumentException exception) {
            return invalid(exception.getMessage());
        }
    }

    private static Map<String, Object> output(EntryWriteResult result, ToolContext context) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("entry", ToolOutputs.entry(result.entry(), context.zone()));
        output.put("day_total", result.dayTotal());
        return output;
    }

    private static String summary(EntryWriteResult result, ToolContext context) {
        EntrySnapshot entry = result.entry();
        StringBuilder summary = new StringBuilder("Deleted ").append(entry.kind());
        Object type = entry.data().get("type");
        if (type instanceof String typeName) summary.append(" · ").append(typeName);
        String when = DATE_TIME.format(OffsetDateTime.ofInstant(entry.ts(), context.zone()));
        return summary.append(" (").append(when).append("), ")
                .append(result.dayTotal().stripTrailingZeros().toPlainString()).append(" on ")
                .append(SHORT_DATE.format(entry.localDay())).toString();
    }

    private static ToolResult.Err invalid(String message) {
        return new ToolResult.Err("validation_error", message == null ? "Invalid entry id" : message);
    }
}
