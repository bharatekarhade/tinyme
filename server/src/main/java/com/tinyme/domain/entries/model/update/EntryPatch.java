package com.tinyme.domain.entries.model.update;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record EntryPatch(
        UUID id,
        String kind,
        BigDecimal quantity,
        String text,
        Map<String, Object> data,
        Instant ts
) {
    public EntryPatch{
        Objects.requireNonNull(id, "id");
        if(kind == null && quantity == null && text == null && data == null && ts == null) throw new IllegalArgumentException(
                "There is nothing to update"
        );

        if(quantity != null && quantity.signum() <=0) throw new IllegalArgumentException("The quantity is not correct");
    }
}
