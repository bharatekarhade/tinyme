package com.tinyme.domain.entries.model.query;

import com.tinyme.domain.entries.model.EntrySnapshot;

import java.util.List;

public record EntryPage(boolean kindKnown, List<EntrySnapshot> entries, String nextCursor) {
    public EntryPage { entries = List.copyOf(entries); }
}
