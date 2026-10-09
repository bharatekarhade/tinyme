package com.tinyme.domain.people.model.get;

import com.tinyme.domain.people.model.PersonSnapshot;

import java.util.Objects;
import java.util.UUID;

/** Internal search result carrying the database ID needed for last_seen lookup. */
public record PersonCandidate(UUID id, PersonSnapshot person) {
    public PersonCandidate {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(person, "person");
    }
}
