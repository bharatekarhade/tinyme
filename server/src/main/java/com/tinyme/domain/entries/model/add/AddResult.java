package com.tinyme.domain.entries.model.add;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record AddResult(UUID id, String kind, LocalDate localDay,
                        BigDecimal quantity, BigDecimal todayTotal) {
}
