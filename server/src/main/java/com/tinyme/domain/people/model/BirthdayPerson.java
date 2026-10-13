package com.tinyme.domain.people.model;

import java.util.Objects;

public record BirthdayPerson(String slug, String displayName, java.util.List<String> aliases,
                             String relationship, String memoryPath, Birthday birthday) {
    public BirthdayPerson {
        Objects.requireNonNull(slug, "slug");
        Objects.requireNonNull(displayName, "displayName");
        aliases = java.util.List.copyOf(Objects.requireNonNull(aliases, "aliases"));
        Objects.requireNonNull(birthday, "birthday");
    }
}
