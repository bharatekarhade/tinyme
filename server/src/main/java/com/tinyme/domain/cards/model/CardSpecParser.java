package com.tinyme.domain.cards.model;

import com.tinyme.domain.entries.model.aggregate.AggregateMetric;
import com.tinyme.domain.entries.model.aggregate.Period;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Parses and validates the untrusted, agent-authored card JSON specification. */
@Component
public final class CardSpecParser {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final Set<String> CARD_KEYS = Set.of("v", "title", "icon", "accent", "tone", "badge", "blocks");

    public CardSpec parse(JsonNode json) {
        return parse(json, false);
    }

    /** Parses a trusted built-in card spec, such as the seeded Journal card. */
    public CardSpec parseTrusted(JsonNode json) {
        return parse(json, true);
    }

    private CardSpec parse(JsonNode json, boolean system) {
        object(json, "card");
        keys(json, CARD_KEYS, "card");
        int version = integer(required(json, "v", "card"), "v");
        String title = string(required(json, "title", "card"), "title");
        CardIcon icon = enumValue(CardIcon.class, required(json, "icon", "card"), "icon");
        CardAccent accent = enumValue(CardAccent.class, required(json, "accent", "card"), "accent");
        CardTone tone = enumValue(CardTone.class, required(json, "tone", "card"), "tone");
        if (tone == CardTone.BRAND && !system) fail("tone 'brand' is reserved for system cards; valid tones: surface, ink");

        JsonNode blocksNode = required(json, "blocks", "card");
        if (!blocksNode.isArray() || blocksNode.isEmpty() || blocksNode.size() > 4) {
            fail("blocks must contain 1–4 blocks");
        }
        List<Block> blocks = new ArrayList<>();
        for (int i = 0; i < blocksNode.size(); i++) blocks.add(parseBlock(blocksNode.get(i), "blocks[" + i + "]", system));

        JsonNode badgeNode = json.get("badge");
        Badge badge = badgeNode == null || badgeNode.isNull() ? null : parseBadge(badgeNode);
        return new CardSpec(version, title, icon, accent, tone, badge, blocks);
    }

    private Block parseBlock(JsonNode node, String path, boolean system) {
        try {
            object(node, path);
            String type = string(required(node, "type", path), path + ".type");
            return switch (type) {
            case "stat" -> {
                keys(node, Set.of("type", "label", "metric", "period", "unit", "goal", "goal_period", "compare", "better"), path);
                yield new StatBlock(label(node, path), metric(required(node, "metric", path), path + ".metric", system),
                        period(node, path), optionalUnit(node, path), decimal(node.get("goal"), path + ".goal"),
                        optionalEnum(GoalPeriod.class, node.get("goal_period"), path + ".goal_period"),
                        optionalEnum(ComparePeriod.class, node.get("compare"), path + ".compare"),
                        optionalEnum(Better.class, node.get("better"), path + ".better"));
            }
            case "progress" -> {
                keys(node, Set.of("type", "label", "metric", "period", "goal", "unit", "goal_period", "style"), path);
                yield new ProgressBlock(label(node, path), metric(required(node, "metric", path), path + ".metric", system),
                        period(node, path), decimal(required(node, "goal", path), path + ".goal"), optionalUnit(node, path),
                        optionalEnum(GoalPeriod.class, node.get("goal_period"), path + ".goal_period"),
                        enumValueOrDefault(ProgressStyle.class, node.get("style"), ProgressStyle.RING, path + ".style"));
            }
            case "goals" -> {
                keys(node, Set.of("type", "label", "period", "items"), path);
                JsonNode itemsNode = required(node, "items", path);
                if (!itemsNode.isArray() || itemsNode.isEmpty() || itemsNode.size() > 4) fail(path + ".items must contain 1–4 items");
                List<GoalItem> items = new ArrayList<>();
                for (int i = 0; i < itemsNode.size(); i++) {
                    String itemPath = path + ".items[" + i + "]";
                    JsonNode item = itemsNode.get(i); object(item, itemPath);
                    keys(item, Set.of("label", "metric", "goal", "unit"), itemPath);
                    items.add(new GoalItem(label(item, itemPath), metric(required(item, "metric", itemPath), itemPath + ".metric", system),
                            decimal(required(item, "goal", itemPath), itemPath + ".goal"), optionalUnit(item, itemPath)));
                }
                String blockLabel = node.get("label") == null ? "" : label(node, path);
                yield new GoalsBlock(blockLabel, period(node, path), items);
            }
            case "chart" -> {
                keys(node, Set.of("type", "label", "metric", "period", "bucket", "style", "goal_line", "highlight", "unit"), path);
                Period period = period(node, path);
                ChartBucket bucket = enumValueOrDefault(ChartBucket.class, node.get("bucket"), ChartBucket.AUTO, path + ".bucket");
                ensureBucketAllowed(period, bucket, path);
                yield new ChartBlock(label(node, path), metric(required(node, "metric", path), path + ".metric", system), period,
                        bucket, enumValueOrDefault(ChartStyle.class, node.get("style"), ChartStyle.BARS, path + ".style"),
                        decimal(node.get("goal_line"), path + ".goal_line"),
                        enumValueOrDefault(Highlight.class, node.get("highlight"), Highlight.NONE, path + ".highlight"),
                        optionalUnit(node, path));
            }
            case "checkins" -> {
                keys(node, Set.of("type", "label", "metric", "period", "goal", "goal_period"), path);
                Integer goal = node.get("goal") == null || node.get("goal").isNull() ? null : integer(node.get("goal"), path + ".goal");
                yield new CheckinsBlock(label(node, path), metric(required(node, "metric", path), path + ".metric", system),
                        period(node, path), goal, optionalEnum(GoalPeriod.class, node.get("goal_period"), path + ".goal_period"));
            }
            case "breakdown" -> {
                keys(node, Set.of("type", "label", "metric", "period", "by", "top", "style", "unit"), path);
                int top = node.get("top") == null ? 3 : integer(node.get("top"), path + ".top");
                yield new BreakdownBlock(label(node, path), metric(required(node, "metric", path), path + ".metric", system),
                        period(node, path), string(required(node, "by", path), path + ".by"), top,
                        enumValueOrDefault(BreakdownStyle.class, node.get("style"), BreakdownStyle.BARS, path + ".style"), optionalUnit(node, path));
            }
            case "latest" -> {
                keys(node, Set.of("type", "label", "kinds", "fields"), path);
                List<String> kinds = strings(required(node, "kinds", path), path + ".kinds");
                List<String> fields = strings(required(node, "fields", path), path + ".fields");
                yield new LatestBlock(label(node, path), kinds, fields);
            }
            default -> fail(path + ".type '" + type + "' is unknown; valid types: stat, progress, goals, chart, checkins, breakdown, latest");
            };
        } catch (IllegalArgumentException e) {
            String message = e.getMessage() == null ? "invalid block" : e.getMessage();
            if (message.startsWith(path)) throw e;
            throw new IllegalArgumentException(path + ": " + message, e);
        }
    }

    private MetricSpec metric(JsonNode node, String path, boolean system) {
        object(node, path);
        keys(node, Set.of("kinds", "op", "field", "where"), path);
        List<String> kinds = strings(required(node, "kinds", path), path + ".kinds");
        if (kinds.contains("*") && !system) fail("kinds ['*'] is reserved for system cards");
        AggregateMetric op = enumValue(AggregateMetric.class, required(node, "op", path), path + ".op");
        String field = optionalString(node, "field", path);
        JsonNode whereNode = node.get("where");
        Map<String, Object> where = Map.of();
        if (whereNode != null && !whereNode.isNull()) {
            if (!whereNode.isObject()) fail(path + ".where must be an object");
            where = JSON.convertValue(whereNode, MAP_TYPE);
        }
        try {
            return new MetricSpec(kinds, op, field, where);
        } catch (IllegalArgumentException e) {
            fail(path + ": " + e.getMessage());
            throw new AssertionError();
        }
    }

    private Badge parseBadge(JsonNode node) {
        try {
            object(node, "badge"); keys(node, Set.of("kind", "block"), "badge");
            return new Badge(enumValue(BadgeKind.class, required(node, "kind", "badge"), "badge.kind"),
                    integer(required(node, "block", "badge"), "badge.block"));
        } catch (IllegalArgumentException e) {
            String message = e.getMessage() == null ? "invalid badge" : e.getMessage();
            if (message.startsWith("badge")) throw e;
            throw new IllegalArgumentException("badge: " + message, e);
        }
    }

    private static Period period(JsonNode node, String path) {
        return enumValue(Period.class, required(node, "period", path), path + ".period");
    }

    private static String label(JsonNode node, String path) {
        String label = string(required(node, "label", path), path + ".label");
        if (label.isBlank() || label.length() > 40) fail(path + ".label must contain 1–40 characters");
        return label;
    }

    private static String optionalString(JsonNode node, String key, String path) {
        JsonNode value = node.get(key);
        if (value == null || value.isNull()) return null;
        return string(value, path + "." + key);
    }

    private static String optionalUnit(JsonNode node, String path) {
        String unit = optionalString(node, "unit", path);
        if (unit != null && (unit.isBlank() || unit.length() > 8)) {
            fail(path + ".unit must contain 1–8 characters");
        }
        return unit;
    }

    private static BigDecimal decimal(JsonNode node, String path) {
        if (node == null || node.isNull()) return null;
        if (!node.isNumber()) fail(path + " must be a number");
        return node.decimalValue();
    }

    private static int integer(JsonNode node, String path) {
        if (node == null || !node.isIntegralNumber() || !node.canConvertToInt()) fail(path + " must be an integer");
        return node.intValue();
    }

    private static List<String> strings(JsonNode node, String path) {
        if (!node.isArray()) fail(path + " must be an array of strings");
        List<String> values = new ArrayList<>();
        for (int i = 0; i < node.size(); i++) values.add(string(node.get(i), path + "[" + i + "]"));
        return List.copyOf(values);
    }

    private static String string(JsonNode node, String path) {
        if (node == null || !node.isString()) fail(path + " must be a string");
        return node.stringValue();
    }

    private static JsonNode required(JsonNode node, String key, String path) {
        JsonNode value = node.get(key);
        if (value == null || value.isNull()) fail(path + "." + key + " is required");
        return value;
    }

    private static void object(JsonNode node, String path) {
        if (node == null || !node.isObject()) fail(path + " must be an object");
    }

    private static void keys(JsonNode node, Set<String> allowed, String path) {
        node.properties().forEach(entry -> {
            if (!allowed.contains(entry.getKey())) fail(path + " has unknown field '" + entry.getKey() + "'");
        });
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, JsonNode node, String path) {
        String value = string(node, path);
        try {
            return Enum.valueOf(type, value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            String valid = java.util.Arrays.stream(type.getEnumConstants())
                    .map(v -> v.name().toLowerCase(Locale.ROOT)).collect(Collectors.joining(", "));
            fail(path + " value '" + value + "' is invalid; valid values: " + valid);
            throw new AssertionError();
        }
    }

    private static <E extends Enum<E>> E optionalEnum(Class<E> type, JsonNode node, String path) {
        return node == null || node.isNull() ? null : enumValue(type, node, path);
    }

    private static <E extends Enum<E>> E enumValueOrDefault(Class<E> type, JsonNode node, E fallback, String path) {
        return node == null || node.isNull() ? fallback : enumValue(type, node, path);
    }

    private static void ensureBucketAllowed(Period period, ChartBucket bucket, String path) {
        Set<ChartBucket> allowed = ChartBuckets.allowed(period);
        if (allowed.isEmpty()) {
            fail(path + " '" + period.name().toLowerCase(Locale.ROOT)
                    + "' has no chart bucket; use stat or progress for a single day");
        }
        if (period == Period.RANGE) {
            if (bucket != ChartBucket.AUTO) {
                String valid = allowed.stream().map(value -> value.name().toLowerCase(Locale.ROOT))
                        .sorted().collect(Collectors.joining(", "));
                fail(path + " 'range' does not allow bucket '" + bucket.name().toLowerCase(Locale.ROOT)
                        + "'; valid bucket: " + valid);
            }
            return;
        }
        ChartBucket effective = bucket == ChartBucket.AUTO ? ChartBuckets.resolveAuto(period, null) : bucket;
        if (!allowed.contains(effective)) {
            String valid = allowed.stream().map(value -> value.name().toLowerCase(Locale.ROOT)).sorted().collect(Collectors.joining(", "));
            fail(path + " '" + period.name().toLowerCase(Locale.ROOT) + "' does not allow bucket '"
                    + effective.name().toLowerCase(Locale.ROOT) + "'; valid bucket: " + valid);
        }
    }

    private static <T> T fail(String message) {
        throw new IllegalArgumentException(message);
    }
}
