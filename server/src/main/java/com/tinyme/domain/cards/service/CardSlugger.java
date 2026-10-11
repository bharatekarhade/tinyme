package com.tinyme.domain.cards.service;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

public final class CardSlugger {
    private static final Pattern NON_ASCII_WORDS = Pattern.compile("[^a-z0-9]+");
    private static final Pattern EDGE_DASHES = Pattern.compile("^-|-$");

    private CardSlugger() {
    }

    public static String base(String title) {
        Objects.requireNonNull(title, "title");
        String normalized = Normalizer.normalize(title, Normalizer.Form.NFKD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT);
        String slug = NON_ASCII_WORDS.matcher(normalized).replaceAll("-");
        slug = EDGE_DASHES.matcher(slug).replaceAll("");
        if (slug.length() > 40) slug = slug.substring(0, 40);
        slug = EDGE_DASHES.matcher(slug).replaceAll("");
        return slug.isEmpty() ? "card" : slug;
    }
}
