package com.tinyme.domain.entries.model.query;

import com.tinyme.domain.entries.model.EntrySnapshot;

import java.util.List;

public record EntryPage(List<String> unknownKinds, List<EntrySnapshot> entries, String nextCursor) {
    public EntryPage {
        unknownKinds = List.copyOf(unknownKinds);
        entries = List.copyOf(entries);
    }
}
