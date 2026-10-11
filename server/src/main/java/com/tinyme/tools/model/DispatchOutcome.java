package com.tinyme.tools.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record DispatchOutcome(String resultJson, boolean isError, Map<String, Object> ref) {
    public DispatchOutcome(String resultJson, boolean isError) {
        this(resultJson, isError, Map.of());
    }

    public DispatchOutcome {
        ref = ref == null || ref.isEmpty() ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(ref));
    }
}
