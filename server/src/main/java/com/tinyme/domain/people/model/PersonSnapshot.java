package com.tinyme.domain.people.model;

import java.util.List;

public record PersonSnapshot(
        String slug,
        String displayName,
        List<String> aliases,
        String relationship,
        String memoryPath
) {
    public PersonSnapshot {
        aliases = List.copyOf(aliases);
    }
}
