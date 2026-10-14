package com.tinyme.domain.health.service;

import com.tinyme.domain.health.model.HealthGroup;
import com.tinyme.domain.health.model.HealthKind;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/**
 * The kinds Apple Health fills in, read once from seed/health/kinds.json (the iOS catalog is checked against it).
 * A broken file stops startup with an error naming the file and the kind.
 */
@Component
public class HealthKinds {
    private static final String FILE = "seed/health/kinds.json";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final Map<String, HealthKind> byKind;
    private final List<HealthKind> ordered;

    public HealthKinds(@Value("classpath:" + FILE) Resource seed) {
        JsonNode root;
        try (var input = seed.getInputStream()) {
            root = JSON.readTree(input);
        } catch (IOException failure) {
            throw new UncheckedIOException(FILE + ": could not be read", failure);
        }
        Map<String, HealthKind> kinds = new LinkedHashMap<>();
        for (JsonNode node : root.path("kinds")) {
            String name = node.path("kind").isString() ? node.path("kind").asString() : "(no kind)";
            HealthKind kind;
            try {
                kind = parse(node, name);
            } catch (IllegalArgumentException | NullPointerException invalid) {
                throw new IllegalStateException(FILE + ": " + invalid.getMessage(), invalid);
            }
            if (kinds.put(kind.kind(), kind) != null) {
                throw new IllegalStateException(FILE + ": kind " + kind.kind() + " is listed twice");
            }
        }
        if (kinds.isEmpty()) throw new IllegalStateException(FILE + ": no kinds listed");
        this.byKind = Map.copyOf(kinds);
        this.ordered = List.copyOf(kinds.values());
    }

    public List<HealthKind> all() {
        return ordered;
    }

    public Optional<HealthKind> find(String kind) {
        return Optional.ofNullable(byKind.get(kind));
    }

    private static HealthKind parse(JsonNode node, String name) {
        String kind = text(node, "kind", name);
        String groupKey = text(node, "group", name);
        HealthGroup group = HealthGroup.fromKey(groupKey).orElseThrow(() ->
                new IllegalArgumentException("kind " + name + " has an unknown group '" + groupKey + "'"));
        JsonNode hints = node.path("data_hints");
        if (!hints.isObject()) throw new IllegalArgumentException("kind " + name + " needs a data_hints object");
        Map<String, String> dataHints = new LinkedHashMap<>();
        for (var field : hints.properties()) {
            if (!field.getValue().isString()) {
                throw new IllegalArgumentException("kind " + name + " field " + field.getKey() + " needs a type name");
            }
            dataHints.put(field.getKey(), field.getValue().asString());
        }
        JsonNode quantity = node.path("quantity_field");
        if (!quantity.isMissingNode() && !quantity.isNull() && !quantity.isString()) {
            throw new IllegalArgumentException("kind " + name + " quantity_field must be a field name or null");
        }
        Set<String> required = new LinkedHashSet<>();
        for (JsonNode field : node.path("required")) {
            if (!field.isString()) throw new IllegalArgumentException("kind " + name + " required must list field names");
            required.add(field.asString());
        }
        Map<String, HealthKind.Range> ranges = new LinkedHashMap<>();
        for (var range : node.path("ranges").properties()) {
            JsonNode limits = range.getValue();
            if (!limits.isArray() || limits.size() != 2 || !limits.get(0).isNumber() || !limits.get(1).isNumber()) {
                throw new IllegalArgumentException("kind " + name + " range for " + range.getKey() + " must be [min, max]");
            }
            ranges.put(range.getKey(), new HealthKind.Range(limits.get(0).asDouble(), limits.get(1).asDouble()));
        }
        return new HealthKind(kind, group, text(node, "description", name), dataHints,
                quantity.isString() ? quantity.asString() : null, required, ranges, node.path("day_total").asBoolean(false));
    }

    /** A required string field: a missing or misspelled key fails here instead of turning into "". */
    private static String text(JsonNode node, String field, String name) {
        JsonNode value = node.path(field);
        if (!value.isString()) throw new IllegalArgumentException("kind " + name + " needs a " + field);
        return value.asString();
    }
}
