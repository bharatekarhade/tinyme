package com.tinyme.domain.people.model.upsert;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

public record PersonUpsert(String slug, String displayName, List<String> aliases, String relationship) {
    private static final Pattern SLUG_PATTERN = Pattern.compile("^[a-z0-9-]{1,48}$");

    public PersonUpsert {
        Objects.requireNonNull(displayName, "displayName");
        displayName = displayName.strip();
        if (displayName.isEmpty()) throw new IllegalArgumentException("display_name is required");
        if (slug != null) {
            slug = slug.strip();
            if (!SLUG_PATTERN.matcher(slug).matches()) {
                throw new IllegalArgumentException("slug must contain 1 to 48 lowercase letters, numbers or hyphens");
            }
        }
        relationship = relationship == null || relationship.isBlank() ? null : relationship.strip();
        aliases = cleanAliases(aliases, displayName);
    }

    private static List<String> cleanAliases(List<String> values, String displayName) {
        if (values == null || values.isEmpty()) return List.of();
        Set<String> seen = new HashSet<>();
        List<String> cleaned = new ArrayList<>();
        for (String value : values) {
            if (value == null) continue;
            String alias = value.strip();
            if (alias.isEmpty() || alias.equalsIgnoreCase(displayName)) continue;
            if (seen.add(alias.toLowerCase(Locale.ROOT))) cleaned.add(alias);
        }
        return List.copyOf(cleaned);
    }
}
