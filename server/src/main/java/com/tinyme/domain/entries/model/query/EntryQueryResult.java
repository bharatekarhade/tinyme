package com.tinyme.domain.entries.model.query;

import com.tinyme.domain.entries.model.EntrySnapshot;

import java.util.List;

public record EntryQueryResult(boolean kindKnown, List<EntrySnapshot> entries, boolean truncated) {
    public EntryQueryResult {
        entries = List.copyOf(entries);
    }
}
