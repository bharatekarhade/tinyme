package com.tinyme.domain.entries.model.query;

import java.time.LocalDate;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record EntryQuery(
        String kind,
        String text,
        Map<String, Object> where,
        LocalDate from,
        LocalDate to,
        Integer limit
) {
    public EntryQuery{
        final int DEFAULT_LIMIT = 10;
        final Integer MAX_LIMIT = 20;
        limit = limit == null ? DEFAULT_LIMIT : limit;
        kind = kind == null || kind.isBlank() ? null : kind.strip().toLowerCase(java.util.Locale.ROOT);
        text = text == null || text.isBlank() ? null : text.strip();
        where = where == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(where));

        if (limit < 1 || limit > 20) throw new IllegalArgumentException("limit must be between %d and %d".formatted(1, MAX_LIMIT));
        if (from != null && to != null && to.isBefore(from)) {
            throw new IllegalArgumentException("to must be on or after from");
        }
    }

    public EntryQuery withKind(String resolvedKind) {
        return new EntryQuery(resolvedKind, text, where, from, to, limit);
    }
}
