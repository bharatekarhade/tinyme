package com.tinyme.domain.entries.model.query;

import java.time.LocalDate;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public record EntryFilters(String kind, String text, Map<String, Object> where,
                           LocalDate from, LocalDate to) {
    public EntryFilters {
        kind = kind == null || kind.isBlank() ? null : kind.strip().toLowerCase(Locale.ROOT);
        text = text == null || text.isBlank() ? null : text.strip();
        where = where == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(where));
        if (from != null && to != null && to.isBefore(from)) {
            throw new IllegalArgumentException("to must be on or after from");
        }
    }

    public EntryFilters withKind(String resolvedKind) {
        return new EntryFilters(resolvedKind, text, where, from, to);
    }
}
