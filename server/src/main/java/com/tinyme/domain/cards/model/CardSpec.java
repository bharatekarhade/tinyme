package com.tinyme.domain.cards.model;

import java.util.List;
import java.util.Objects;

public record CardSpec(int v, String title, CardIcon icon, CardAccent accent, CardTone tone,
                       Badge badge, List<Block> blocks) {
    public CardSpec {
        if (v != 1) throw new IllegalArgumentException("v must be 1");
        if (title == null || title.isBlank() || title.length() > 40) throw new IllegalArgumentException("title must contain 1–40 characters");
        Objects.requireNonNull(icon, "icon is required");
        Objects.requireNonNull(accent, "accent is required");
        Objects.requireNonNull(tone, "tone is required");
        blocks = List.copyOf(blocks);
        if (blocks.isEmpty() || blocks.size() > 4) throw new IllegalArgumentException("blocks must contain 1–4 blocks");
        if (badge != null) {
            if (badge.block() >= blocks.size()) throw new IllegalArgumentException("badge.block must point to an existing block");
            Block target = blocks.get(badge.block());
            if (badge.kind() == BadgeKind.DELTA
                    && (!(target instanceof StatBlock stat) || stat.compare() == null)) {
                throw new IllegalArgumentException("delta badge requires a stat block with compare");
            }
            if (badge.kind() == BadgeKind.PERIOD
                    && !(target instanceof MetricBlock || target instanceof GoalsBlock)) {
                throw new IllegalArgumentException("period badge requires a block with a period");
            }
            if (badge.kind() == BadgeKind.STREAK && !(target instanceof MetricBlock)) {
                throw new IllegalArgumentException("streak badge requires a block with a metric");
            }
        }
    }
}
