package com.tinyme.agent.model;

import java.util.Objects;
import java.util.UUID;

public sealed interface TurnEvent permits TurnEvent.ActionDone, TurnEvent.Text, TurnEvent.Failed {
    record ActionDone(String tool, String summary, boolean isError) implements TurnEvent {
        public ActionDone {
            Objects.requireNonNull(tool, "tool");
            Objects.requireNonNull(summary, "summary");
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
