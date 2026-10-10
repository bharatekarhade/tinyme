package com.tinyme.domain.people.service;

import java.text.Normalizer;
import java.util.Collection;
import java.util.HashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

public final class Slugger {
    private static final int MAX_BASE_LENGTH = 40;

    private Slugger() {
    }

    public static String base(String value) {
        String normalized = Normalizer.normalize(value.strip().toLowerCase(Locale.ROOT), Normalizer.Form.NFKD)
                .replaceAll("\\p{M}+", "");
        String slug = normalized.replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
        if (slug.isEmpty()) return "person";
        if (slug.length() <= MAX_BASE_LENGTH) return slug;
        return slug.substring(0, MAX_BASE_LENGTH).replaceAll("-+$", "");
    }

    public static String next(String base, Collection<String> taken) {
        Objects.requireNonNull(base, "base");
        Objects.requireNonNull(taken, "taken");
        Set<String> existing = new HashSet<>(taken);
        if (!existing.contains(base)) return base;

        for (long suffix = 2; ; suffix++) {
            String candidate = base + "-" + suffix;
            if (candidate.length() > 48) {
                throw new IllegalStateException("No available person slug within 48 characters for '" + base + "'");
            }
            if (!existing.contains(candidate)) return candidate;
        }
    }
}
