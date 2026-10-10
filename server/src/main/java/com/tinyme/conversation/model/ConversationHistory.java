package com.tinyme.conversation.model;

import java.util.List;
import java.util.Objects;

public record ConversationHistory(List<ConversationHistoryMessage> messages) {
    public ConversationHistory {
        messages = List.copyOf(Objects.requireNonNull(messages, "messages"));
    }
}
