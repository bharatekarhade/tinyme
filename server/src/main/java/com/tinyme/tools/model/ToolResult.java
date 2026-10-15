package com.tinyme.tools.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public sealed interface ToolResult permits ToolResult.Ok, ToolResult.Err {
    record Ok(Object data, String summary, Map<String, Object> ref, Object stored) implements ToolResult {
        public Ok(Object data, String summary) {
            this(data, summary, Map.of(), null);
        }

        public Ok(Object data, String summary, Map<String, Object> ref) {
            this(data, summary, ref, null);
        }

        public Ok {
            ref = ref == null || ref.isEmpty() ? Map.of()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(ref));
        }
    }

    record Err(String code, String message) implements ToolResult {}
}
