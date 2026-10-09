package com.tinyme.tools.handlers;

import com.tinyme.domain.entries.model.EntrySnapshot;
import com.tinyme.domain.entries.model.query.EntryQuery;
import com.tinyme.domain.entries.model.query.EntryQueryResult;
import com.tinyme.domain.entries.service.EntryService;
import com.tinyme.tools.model.ToolContext;
import com.tinyme.tools.model.ToolHandler;
import com.tinyme.tools.model.ToolResult;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class EntriesQueryTool implements ToolHandler {
    private static final DateTimeFormatter SHORT_DATE = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH);

    private final EntryService entries;

    public EntriesQueryTool(EntryService entries) {
        this.entries = entries;
    }

    @Override
    public String name() {
        return "entries_query";
    }

    @Override
    public ToolResult handle(JsonNode input, ToolContext context) {
        if (input == null || !input.isObject()) return invalid("Input must be an object");
        try {
            EntryQuery query = new EntryQuery(ToolInputs.optionalString(input, "kind"),
                    ToolInputs.optionalString(input, "text"), ToolInputs.optionalObject(input, "where"),
                    ToolInputs.optionalDate(input, "from"), ToolInputs.optionalDate(input, "to"),
                    ToolInputs.optionalInt(input, "limit"));
            EntryQueryResult result = entries.query(query);
            return new ToolResult.Ok(toData(result, context), summary(result, query.where(), context));
        } catch (IllegalArgumentException exception) {
            return invalid(exception.getMessage());
        }
    }

    private static Map<String, Object> toData(EntryQueryResult result, ToolContext context) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (EntrySnapshot entry : result.entries()) {
            rows.add(ToolOutputs.entry(entry, context.zone()));
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("kind_known", result.kindKnown());
        data.put("entries", rows);
        data.put("truncated", result.truncated());
        return data;
    }

    private static String summary(EntryQueryResult result, Map<String, Object> where, ToolContext context) {
        if (result.entries().isEmpty()) return "No matching entries";

        EntrySnapshot latest = result.entries().getFirst();
        StringBuilder summary = new StringBuilder("Found ").append(result.entries().size());
        if (latest.kind() != null) summary.append(' ').append(latest.kind());
        Object type = where.get("type");
        if (type instanceof String typeName) summary.append(" · ").append(typeName);
        String latestDay = SHORT_DATE.format(latest.ts().atZone(context.zone()));
        return summary.append(" (latest ").append(latestDay).append(')').toString();
    }

    private static ToolResult.Err invalid(String message) {
        return new ToolResult.Err("validation_error", message == null ? "Invalid entry query" : message);
    }
}
