package com.tinyme.tools.handlers;

import com.tinyme.domain.entries.model.EntrySnapshot;
import com.tinyme.domain.entries.model.query.EntryQuery;
import com.tinyme.domain.entries.model.query.EntryQueryResult;
import com.tinyme.domain.entries.service.EntryService;
import com.tinyme.tools.model.ToolContext;
import com.tinyme.tools.model.ToolHandler;
import com.tinyme.tools.model.ToolResult;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class EntriesQueryTool implements ToolHandler {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
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
            EntryQuery query = new EntryQuery(optionalString(input, "kind"),
                    optionalString(input, "text"), readWhere(input.get("where")),
                    readDate(input, "from"), readDate(input, "to"), readLimit(input));
            EntryQueryResult result = entries.query(query);
            return new ToolResult.Ok(toData(result, context), summary(result, query.where(), context));
        } catch (IllegalArgumentException exception) {
            return invalid(exception.getMessage());
        }
    }

    private static Map<String, Object> toData(EntryQueryResult result, ToolContext context) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (EntrySnapshot entry : result.entries()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", entry.id().toString());
            row.put("ts", OffsetDateTime.ofInstant(entry.ts(), context.zone())
                    .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
            row.put("local_day", entry.localDay().toString());
            row.put("kind", entry.kind());
            row.put("quantity", entry.quantity());
            row.put("text", entry.text());
            row.put("data", entry.data());
            rows.add(row);
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

    private static String optionalString(JsonNode input, String key) {
        JsonNode value = input.get(key);
        if (value == null || value.isNull()) return null;
        if (!value.isString()) throw new IllegalArgumentException(key + " must be a string");
        return value.stringValue();
    }

    private static LocalDate readDate(JsonNode input, String key) {
        String value = optionalString(input, key);
        if (value == null) return null;
        try {
            return LocalDate.parse(value);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException(key + " must be an ISO date");
        }
    }

    private static Integer readLimit(JsonNode input) {
        JsonNode value = input.get("limit");
        if (value == null || value.isNull()) return null;
        if (!value.isIntegralNumber()) throw new IllegalArgumentException("limit must be an integer between 1 and 20");
        return value.intValue();
    }

    private static Map<String, Object> readWhere(JsonNode node) {
        if (node == null || node.isNull()) return Map.of();
        if (!node.isObject()) throw new IllegalArgumentException("where must be an object");
        return JSON.convertValue(node, MAP_TYPE);
    }

    private static ToolResult.Err invalid(String message) {
        return new ToolResult.Err("validation_error", message == null ? "Invalid entry query" : message);
    }
}
