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
import tools.jackson.databind.JsonNode;

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
            String kind = ToolInputs.optionalString(input, "kind");
            List<String> kinds = ToolInputs.optionalStringList(input, "kinds");
            if ((kind == null) == (kinds == null)) throw new IllegalArgumentException("provide exactly one of kind or kinds");
            if (kind != null) kinds = List.of(kind);
            kinds = kinds.stream().map(value -> value.trim().toLowerCase(Locale.ROOT)).toList();
            if (kinds.contains("*")) throw new IllegalArgumentException("'*' is reserved for the Journal system card");
            AggregateMetric metric;
            try {
                metric = AggregateMetric.valueOf(ToolInputs.requiredString(input, "metric").toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("metric must be count, sum, avg, min or max");
            }
            String field = ToolInputs.optionalString(input, "field");
            Map<String, Object> where = ToolInputs.optionalObject(input, "where");
            LocalDate from = ToolInputs.optionalDate(input, "from");
            LocalDate to = ToolInputs.optionalDate(input, "to");
            String groupValue = ToolInputs.optionalString(input, "group_by");
            GroupBy groupBy = GroupBy.NONE;
            if (groupValue != null) {
                try {
                    groupBy = GroupBy.valueOf(groupValue.toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException exception) {
                    throw new IllegalArgumentException("group_by must be none, day, week or month");
                }
            }

            if (groupBy.grouped() && from != null && to != null
                    && AggregateQuery.bucketCount(from, to, groupBy) > AggregateQuery.MAX_TOOL_BUCKETS) {
                throw new IllegalArgumentException("date range may produce at most "
                        + AggregateQuery.MAX_TOOL_BUCKETS + " buckets");
            }

            AggregateQuery query = new AggregateQuery(kinds, metric, field, where, from, to, groupBy);
            AggregateResult result = entries.aggregate(query);
            if (groupBy.grouped() && result.buckets().size() > AggregateQuery.MAX_TOOL_BUCKETS) {
                throw new IllegalArgumentException("query may produce at most "
                        + AggregateQuery.MAX_TOOL_BUCKETS + " buckets");
            }
            return new ToolResult.Ok(output(result), summary(result));
        } catch (IllegalArgumentException exception) {
            return invalid(exception.getMessage());
        }
    }

    private static Map<String, Object> output(AggregateResult result) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("kinds", result.kinds());
        output.put("unknown_kinds", result.unknownKinds());
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
        StringBuilder label = new StringBuilder(result.kinds().isEmpty()
                ? "all kinds" : String.join(" + ", result.kinds()));
        if (!result.unknownKinds().isEmpty()) {
            label.append(" (unknown: ").append(String.join(", ", result.unknownKinds())).append(')');
        }
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

    private static ToolResult.Err invalid(String message) {
        return new ToolResult.Err("validation_error", message == null ? "Invalid aggregate query" : message);
    }
}
