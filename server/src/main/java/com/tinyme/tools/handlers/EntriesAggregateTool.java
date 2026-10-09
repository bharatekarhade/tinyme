package com.tinyme.tools.handlers;

import com.tinyme.domain.entries.model.aggregate.AggregateMetric;
import com.tinyme.domain.entries.model.aggregate.AggregateQuery;
import com.tinyme.domain.entries.model.aggregate.AggregateResult;
import com.tinyme.domain.entries.model.aggregate.Bucket;
import com.tinyme.domain.entries.model.aggregate.GroupBy;
import com.tinyme.domain.entries.service.EntryService;
import com.tinyme.tools.model.ToolContext;
import com.tinyme.tools.model.ToolHandler;
import com.tinyme.tools.model.ToolResult;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class EntriesAggregateTool implements ToolHandler {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final DateTimeFormatter SHORT_DATE = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH);

    private final EntryService entries;

    public EntriesAggregateTool(EntryService entries) {
        this.entries = entries;
    }

    @Override
    public String name() {
        return "entries_aggregate";
    }

    @Override
    public ToolResult handle(JsonNode input, ToolContext context) {
        if (input == null || !input.isObject()) return invalid("Input must be an object");
        try {
            String kind = requiredString(input, "kind").trim().toLowerCase(Locale.ROOT);
            if (!kind.matches("^[a-z][a-z0-9_]{1,31}$")) {
                return invalid("kind must contain 2–32 lowercase letters, digits or underscores, starting with a letter");
            }
            AggregateMetric metric;
            try {
                metric = AggregateMetric.valueOf(requiredString(input, "metric").toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("metric must be count, sum, avg, min or max");
            }
            String field = optionalString(input, "field");
            Map<String, Object> where = readWhere(input.get("where"));
            LocalDate from = readDate(input, "from");
            LocalDate to = readDate(input, "to");
            JsonNode groupNode = input.get("group_by");
            GroupBy groupBy = GroupBy.NONE;
            if (groupNode != null) {
                if (!groupNode.isString()) throw new IllegalArgumentException("group_by must be none, day, week or month");
                try {
                    groupBy = GroupBy.valueOf(groupNode.stringValue().toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException exception) {
                    throw new IllegalArgumentException("group_by must be none, day, week or month");
                }
            }

            AggregateQuery query = new AggregateQuery(kind, metric, field, where, from, to, groupBy);
            AggregateResult result = entries.aggregate(query);
            return new ToolResult.Ok(output(result), summary(result));
        } catch (IllegalArgumentException exception) {
            return invalid(exception.getMessage());
        }
    }

    private static Map<String, Object> output(AggregateResult result) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("kind", result.kind());
        output.put("kind_known", result.kindKnown());
        output.put("metric", result.metric().name().toLowerCase(Locale.ROOT));
        if (result.field() != null) output.put("field", result.field());
        if (!result.where().isEmpty()) output.put("where", result.where());
        if (result.from() != null) output.put("from", result.from().toString());
        if (result.to() != null) output.put("to", result.to().toString());
        if (result.groupBy().grouped()) {
            output.put("group_by", result.groupBy().name().toLowerCase(Locale.ROOT));
            List<Map<String, Object>> buckets = new ArrayList<>();
            for (Bucket bucket : result.buckets()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("period", bucket.period().toString());
                row.put("value", bucket.value());
                row.put("entries", bucket.entries());
                buckets.add(row);
            }
            output.put("buckets", buckets);
        } else {
            output.put("value", result.value());
            output.put("entries", result.entries());
        }
        return output;
    }

    private static String summary(AggregateResult result) {
        StringBuilder label = new StringBuilder(result.kind());
        Object type = result.where().get("type");
        if (type instanceof String typeName) label.append(" · ").append(typeName);
        if (result.metric().requiresField()) {
            label.append(' ').append(result.field()).append(' ').append(result.metric() == AggregateMetric.AVG
                    ? "avg" : result.metric().name().toLowerCase(Locale.ROOT));
        } else if (result.metric() != AggregateMetric.COUNT) {
            label.append(' ').append(result.metric().name().toLowerCase(Locale.ROOT));
        }

        String period = dateRange(result.from(), result.to());
        if (result.groupBy().grouped()) {
            label.append(" by ").append(result.groupBy().name().toLowerCase(Locale.ROOT));
            if (result.buckets().isEmpty()) label.append(": no data");
            else label.append(": ").append(result.buckets().size()).append(" buckets");
        } else {
            label.append(": ").append(formatValue(result.value(), result.metric()));
        }
        return period.isEmpty() ? label.toString() : label + " (" + period + ")";
    }

    private static String formatValue(BigDecimal value, AggregateMetric metric) {
        if (value == null) return "no data";
        if (metric == AggregateMetric.AVG) {
            int scale = Math.max(1, value.stripTrailingZeros().scale());
            return value.setScale(scale).toPlainString();
        }
        return value.stripTrailingZeros().toPlainString();
    }

    private static String dateRange(LocalDate from, LocalDate to) {
        if (from == null && to == null) return "all time";
        if (from == null) return "through " + SHORT_DATE.format(to);
        if (to == null) return "from " + SHORT_DATE.format(from);
        if (from.equals(to)) return SHORT_DATE.format(from);
        if (from.getMonth() == to.getMonth()) {
            return from.getDayOfMonth() + "–" + SHORT_DATE.format(to);
        }
        return SHORT_DATE.format(from) + "–" + SHORT_DATE.format(to);
    }

    private static String requiredString(JsonNode input, String key) {
        JsonNode value = input.get(key);
        if (value == null || !value.isString() || value.stringValue().isBlank()) {
            throw new IllegalArgumentException(key + " is required");
        }
        return value.stringValue();
    }

    private static String optionalString(JsonNode input, String key) {
        JsonNode value = input.get(key);
        if (value == null) return null;
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

    private static Map<String, Object> readWhere(JsonNode node) {
        if (node == null) return Map.of();
        if (!node.isObject()) throw new IllegalArgumentException("where must be an object");
        return JSON.convertValue(node, MAP_TYPE);
    }

    private static ToolResult.Err invalid(String message) {
        return new ToolResult.Err("validation_error", message == null ? "Invalid aggregate query" : message);
    }
}
