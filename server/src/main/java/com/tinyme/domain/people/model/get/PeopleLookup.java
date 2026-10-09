package com.tinyme.domain.people.model.get;

import java.util.List;
import java.util.Objects;

public record PeopleLookup(PersonMatchType match, List<PersonMatch> matches) {
    public static final int MAX_MATCHES = 5;

    public PeopleLookup {
        Objects.requireNonNull(match, "match");
        matches = List.copyOf(matches);
        if ((match == PersonMatchType.NONE) != matches.isEmpty()) {
            throw new IllegalArgumentException("none must be used exactly when there are no matches");
        }
    }
}
