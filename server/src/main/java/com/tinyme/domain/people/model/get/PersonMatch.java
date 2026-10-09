package com.tinyme.domain.people.model.get;

import com.tinyme.domain.people.model.PersonSnapshot;

import java.time.Instant;
import java.util.Objects;

public record PersonMatch(PersonSnapshot person, Instant lastSeen) {
    public PersonMatch {
        Objects.requireNonNull(person, "person");
    }
}
