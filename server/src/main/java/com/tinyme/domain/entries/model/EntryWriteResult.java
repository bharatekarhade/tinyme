package com.tinyme.domain.entries.model;

import java.math.BigDecimal;
import java.util.Objects;

public record EntryWriteResult(EntrySnapshot entry, BigDecimal dayTotal) {
    public EntryWriteResult {
        Objects.requireNonNull(entry, "entry");
        Objects.requireNonNull(dayTotal, "dayTotal");
    }
}
