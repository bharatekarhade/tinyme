package com.tinyme.domain.entries.model.query;

import java.time.LocalDate;
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
        var filters = new EntryFilters(kind == null ? java.util.List.of() : java.util.List.of(kind),
                text, where, from, to, null);
        kind = filters.kinds().isEmpty() ? null : filters.kinds().getFirst();
        text = filters.text();
        where = filters.where();

        if (limit < 1 || limit > 20) throw new IllegalArgumentException("limit must be between %d and %d".formatted(1, MAX_LIMIT));
    }

    public EntryFilters filters() {
        return new EntryFilters(kind == null ? java.util.List.of() : java.util.List.of(kind),
                text, where, from, to, null);
    }

    public EntryQuery withKind(String resolvedKind) {
        return new EntryQuery(resolvedKind, text, where, from, to, limit);
    }
}
