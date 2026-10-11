package com.tinyme.domain.cards.model;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Writes stored card specs with the canonical snake_case and lowercase enum format. */
@Component
public final class CardSpecWriter {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    public JsonNode write(CardSpec spec) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("v", spec.v());
        result.put("title", spec.title());
        result.put("icon", lower(spec.icon()));
        result.put("accent", lower(spec.accent()));
        result.put("tone", lower(spec.tone()));
        if (spec.badge() != null) {
            Map<String, Object> badge = new LinkedHashMap<>();
            badge.put("kind", lower(spec.badge().kind()));
            badge.put("block", spec.badge().block());
            result.put("badge", badge);
        }
        result.put("blocks", spec.blocks().stream().map(this::block).toList());
        return JSON.valueToTree(result);
    }

    private Map<String, Object> block(Block block) {
        return switch (block) {
            case StatBlock stat -> {
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("type", "stat");
                result.put("label", stat.label());
                result.put("metric", metric(stat.metric()));
                result.put("period", lower(stat.period()));
                put(result, "unit", stat.unit());
                put(result, "goal", stat.goal());
                put(result, "goal_period", stat.goalPeriod() == null ? null : lower(stat.goalPeriod()));
                put(result, "compare", stat.compare() == null ? null : lower(stat.compare()));
                put(result, "better", stat.better() == null ? null : lower(stat.better()));
                yield result;
            }
            case ProgressBlock progress -> {
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("type", "progress");
                result.put("label", progress.label());
                result.put("metric", metric(progress.metric()));
                result.put("period", lower(progress.period()));
                result.put("goal", progress.goal());
                put(result, "unit", progress.unit());
                put(result, "goal_period", progress.goalPeriod() == null ? null : lower(progress.goalPeriod()));
                result.put("style", lower(progress.style()));
                yield result;
            }
            case GoalsBlock goals -> {
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("type", "goals");
                if (!goals.label().isEmpty()) result.put("label", goals.label());
                result.put("period", lower(goals.period()));
                result.put("items", goals.items().stream().map(this::goalItem).toList());
                yield result;
            }
            case ChartBlock chart -> {
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("type", "chart");
                result.put("label", chart.label());
                result.put("metric", metric(chart.metric()));
                result.put("period", lower(chart.period()));
                result.put("bucket", lower(chart.bucket()));
                result.put("style", lower(chart.style()));
                put(result, "goal_line", chart.goalLine());
                result.put("highlight", lower(chart.highlight()));
                put(result, "unit", chart.unit());
                yield result;
            }
            case CheckinsBlock checkins -> {
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("type", "checkins");
                result.put("label", checkins.label());
                result.put("metric", metric(checkins.metric()));
                result.put("period", lower(checkins.period()));
                put(result, "goal", checkins.goal());
                put(result, "goal_period", checkins.goalPeriod() == null ? null : lower(checkins.goalPeriod()));
                yield result;
            }
            case BreakdownBlock breakdown -> {
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("type", "breakdown");
                result.put("label", breakdown.label());
                result.put("metric", metric(breakdown.metric()));
                result.put("period", lower(breakdown.period()));
                result.put("by", breakdown.by());
                result.put("top", breakdown.top());
                result.put("style", lower(breakdown.style()));
                put(result, "unit", breakdown.unit());
                yield result;
            }
            case LatestBlock latest -> {
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("type", "latest");
                result.put("label", latest.label());
                result.put("kinds", latest.kinds());
                result.put("fields", latest.fields());
                yield result;
            }
        };
    }

    private Map<String, Object> metric(MetricSpec metric) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("kinds", metric.kinds());
        result.put("op", lower(metric.op()));
        put(result, "field", metric.field());
        if (!metric.where().isEmpty()) result.put("where", metric.where());
        return result;
    }

    private Map<String, Object> goalItem(GoalItem item) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("label", item.label());
        result.put("metric", metric(item.metric()));
        result.put("goal", item.goal());
        put(result, "unit", item.unit());
        return result;
    }

    private static void put(Map<String, Object> map, String key, Object value) {
        if (value != null) map.put(key, value);
    }

    private static String lower(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }
}
