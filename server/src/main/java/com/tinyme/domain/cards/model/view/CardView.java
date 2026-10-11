package com.tinyme.domain.cards.model.view;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record CardView(UUID id, String slug, String title, String icon, String accent, String tone,
                       String status, Boolean system, BadgeView badge, List<BlockView> blocks, String error) {
    public CardView {
        if (blocks != null) blocks = List.copyOf(blocks);
    }

    public static String wire(Enum<?> value) { return value.name().toLowerCase(java.util.Locale.ROOT); }
}
