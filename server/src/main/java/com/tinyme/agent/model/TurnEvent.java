package com.tinyme.agent.model;

import java.util.Objects;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public sealed interface TurnEvent permits TurnEvent.ActionDone, TurnEvent.Text, TurnEvent.Failed {
    record ActionDone(String tool, String summary, boolean isError, Map<String, Object> ref) implements TurnEvent {
        public ActionDone(String tool, String summary, boolean isError) {
            this(tool, summary, isError, Map.of());
        }

        public ActionDone {
            Objects.requireNonNull(tool, "tool");
            Objects.requireNonNull(summary, "summary");
            ref = ref == null || ref.isEmpty() ? Map.of()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(ref));
        }
    }

    record Text(String text) implements TurnEvent {
        public Text { Objects.requireNonNull(text, "text"); }
    }

    record Failed(String code, String message, boolean retryable, UUID assistantMessageId) implements TurnEvent {
        public Failed(String code, String message, boolean retryable) {
            this(code, message, retryable, null);
        }

        public Failed {
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(message, "message");
        }
    }
}
