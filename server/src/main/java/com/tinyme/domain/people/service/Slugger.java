package com.tinyme.domain.people.service;

import java.text.Normalizer;
import java.util.Locale;

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
}
