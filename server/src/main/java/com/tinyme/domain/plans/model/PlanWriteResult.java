package com.tinyme.domain.plans.model;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record PlanWriteResult(UUID id, String title, List<PlanOccurrence> occurrences,
                              List<String> conflicts, List<String> warnings) {
    public PlanWriteResult {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(title, "title");
        occurrences = List.copyOf(occurrences);
        conflicts = List.copyOf(conflicts);
        warnings = List.copyOf(warnings);
    }
}
