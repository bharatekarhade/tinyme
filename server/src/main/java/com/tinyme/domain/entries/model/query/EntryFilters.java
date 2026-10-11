package com.tinyme.domain.entries.model.query;

import java.time.LocalDate;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/** Filters shared by journal queries, the app entry list, and card drill-downs. */
public record EntryFilters(List<String> kinds, String text, Map<String, Object> where,
                           LocalDate from, LocalDate to, String numericField) {
    private static final Pattern KIND_PATTERN = Pattern.compile("^[a-z][a-z0-9_]{1,31}$");
    private static final Pattern FIELD_PATTERN = Pattern.compile("^[a-z][a-z0-9_]{0,31}$");
    public static final int MAX_KINDS = 5;

    public EntryFilters {
        kinds = normalizeKinds(kinds);
        text = text == null || text.isBlank() ? null : text.strip();
        where = where == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(where));
        numericField = numericField == null || numericField.isBlank() ? null : numericField.strip();
        if (numericField != null && !FIELD_PATTERN.matcher(numericField).matches()) {
            throw new IllegalArgumentException("numericField must start with a lowercase letter and contain only lowercase letters, digits or underscores");
        }
        if (from != null && to != null && to.isBefore(from)) {
            throw new IllegalArgumentException("to must be on or after from");
        }
    }

    /** Empty means all kinds. Requested values are normalized and de-duplicated in input order. */
    private static List<String> normalizeKinds(List<String> requested) {
        if (requested == null || requested.isEmpty()) return List.of();
        if (requested.size() > MAX_KINDS) {
            throw new IllegalArgumentException("kind may be repeated at most " + MAX_KINDS + " times");
        }
        var normalized = new LinkedHashSet<String>();
        for (String value : requested) {
            Objects.requireNonNull(value, "kind");
            String kind = value.strip().toLowerCase(Locale.ROOT);
            if (!KIND_PATTERN.matcher(kind).matches()) {
                throw new IllegalArgumentException("each kind must contain 2–32 lowercase letters, digits or underscores, starting with a letter");
            }
            normalized.add(kind);
        }
        return List.copyOf(normalized);
    }

    public EntryFilters withKinds(List<String> resolvedKinds) {
        return new EntryFilters(resolvedKinds, text, where, from, to, numericField);
    }
}
