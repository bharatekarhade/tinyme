package com.tinyme.api.people;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.tinyme.domain.people.model.PersonSnapshot;

import java.util.List;

public record PersonResponse(String slug,
                             @JsonProperty("display_name") String displayName,
                             List<String> aliases,
                             String relationship) {
    public PersonResponse { aliases = List.copyOf(aliases); }

    public static PersonResponse from(PersonSnapshot person) {
        return new PersonResponse(person.slug(), person.displayName(), person.aliases(), person.relationship());
    }
}
