package com.tinyme.domain.cards.model;

import com.tinyme.domain.entries.model.aggregate.AggregateQuery;
import java.util.List;
import java.util.Objects;

public record LatestBlock(String label, List<String> kinds, List<String> fields) implements Block {
    public LatestBlock {
        Objects.requireNonNull(label, "latest.label is required");
        kinds = List.copyOf(AggregateQuery.validateKinds(kinds));
        if (kinds.contains("*")) throw new IllegalArgumentException("latest.kinds cannot contain '*'");
        fields = List.copyOf(fields);
        if (fields.isEmpty() || fields.size() > 3) throw new IllegalArgumentException("latest.fields must contain 1–3 fields");
        if (fields.stream().anyMatch(field -> field == null || !field.matches("^[a-z][a-z0-9_]{0,31}$"))) {
            throw new IllegalArgumentException("latest.fields must contain valid data field names");
        }
    }
}
