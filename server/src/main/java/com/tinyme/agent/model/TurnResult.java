package com.tinyme.agent.model;

import java.util.UUID;

public record TurnResult(String replyText, int toolCalls, UUID assistantMessageId) {
    public TurnResult(String replyText, int toolCalls) {
        this(replyText, toolCalls, null);
    }
}
