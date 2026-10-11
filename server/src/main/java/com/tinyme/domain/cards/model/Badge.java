package com.tinyme.domain.cards.model;

import java.util.Objects;

public record Badge(BadgeKind kind, int block) {
    public Badge {
        Objects.requireNonNull(kind, "badge kind is required");
        if (block < 0) throw new IllegalArgumentException("badge.block must be zero-based and point to an existing block");
    }
}
