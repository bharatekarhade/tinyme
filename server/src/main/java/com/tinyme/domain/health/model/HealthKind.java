package com.tinyme.domain.health.model;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** A kind Apple Health fills in, the Apple Health group it belongs to, and its data fields. */
public record HealthKind(String kind, HealthGroup group, String description, Map<String, String> dataHints) {
    /** Same rule as the entry_kinds.kind CHECK. */
    private static final Pattern KIND = Pattern.compile("^[a-z][a-z0-9_]{1,31}$");
    private static final Set<String> HINT_TYPES = Set.of("number", "string", "any");

    public HealthKind {
        Objects.requireNonNull(kind, "kind");
        if (!KIND.matcher(kind).matches()) {
            throw new IllegalArgumentException("kind '" + kind + "' must match " + KIND.pattern());
        }
        Objects.requireNonNull(group, () -> "kind " + kind + " needs a group");
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("kind " + kind + " needs a description");
        }
        Objects.requireNonNull(dataHints, () -> "kind " + kind + " needs data_hints");
        if (dataHints.isEmpty()) throw new IllegalArgumentException("kind " + kind + " needs at least one data field");
        dataHints.forEach((field, type) -> {
            if (!HINT_TYPES.contains(type)) {
                throw new IllegalArgumentException("kind " + kind + " field " + field
                        + " has type '" + type + "'; use number, string or any");
            }
        });
        dataHints = Map.copyOf(dataHints);
    }
}
