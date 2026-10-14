package com.tinyme.domain.health.model;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A kind Apple Health fills in: its Apple Health group, its data fields, and the rules a synced item must meet.
 *
 * @param quantityField the data field the entry's quantity equals, or null when the quantity is always 1
 * @param required      fields every item must carry (the quantity field is always required)
 * @param ranges        inclusive limits for number fields
 * @param dayTotal      one number per day even across several entries (a night plus an addition)
 */
public record HealthKind(String kind, HealthGroup group, String description, Map<String, String> dataHints,
                         String quantityField, Set<String> required, Map<String, Range> ranges, boolean dayTotal) {
    /** Same rule as the entry_kinds.kind CHECK. */
    private static final Pattern KIND = Pattern.compile("^[a-z][a-z0-9_]{1,31}$");
    private static final Set<String> HINT_TYPES = Set.of("number", "string", "any");

    public record Range(double min, double max) {
        public Range {
            if (!(min <= max)) throw new IllegalArgumentException("range min must not be above max");
        }

        public boolean contains(double value) {
            return value >= min && value <= max;
        }
    }

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
        required = required == null ? Set.of() : Set.copyOf(required);
        ranges = ranges == null ? Map.of() : Map.copyOf(ranges);
        if (quantityField != null && !"number".equals(dataHints.get(quantityField))) {
            throw new IllegalArgumentException("kind " + kind + " quantity_field " + quantityField
                    + " must be one of its number fields");
        }
        for (String field : required) {
            if (!dataHints.containsKey(field)) {
                throw new IllegalArgumentException("kind " + kind + " requires " + field + ", which isn't one of its fields");
            }
        }
        for (String field : ranges.keySet()) {
            if (!"number".equals(dataHints.get(field))) {
                throw new IllegalArgumentException("kind " + kind + " has a range for " + field
                        + ", which isn't one of its number fields");
            }
        }
    }

    /** Fields an item must carry: the listed ones plus the quantity field. */
    public boolean requires(String field) {
        return required.contains(field) || field.equals(quantityField);
    }
}
