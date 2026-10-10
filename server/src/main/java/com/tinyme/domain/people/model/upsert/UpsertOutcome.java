package com.tinyme.domain.people.model.upsert;

import com.tinyme.domain.people.model.PersonSnapshot;

import java.util.Objects;

public sealed interface UpsertOutcome permits UpsertOutcome.Created, UpsertOutcome.Updated,
        UpsertOutcome.NotFound, UpsertOutcome.Duplicate {
    record Created(PersonSnapshot person) implements UpsertOutcome {
        public Created { Objects.requireNonNull(person, "person"); }
    }

    record Updated(PersonSnapshot person) implements UpsertOutcome {
        public Updated { Objects.requireNonNull(person, "person"); }
    }

    record NotFound(String slug) implements UpsertOutcome {
        public NotFound { Objects.requireNonNull(slug, "slug"); }
    }

    record Duplicate(PersonSnapshot existing) implements UpsertOutcome {
        public Duplicate { Objects.requireNonNull(existing, "existing"); }
    }
}
