package com.tinyme.conversation.service;

import com.tinyme.agent.model.TurnResult;
import com.tinyme.agent.service.TurnListener;
import com.tinyme.conversation.model.ConversationMessageRequest;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;

public interface ConversationOperations {
    ConversationService.Acceptance accept(ConversationMessageRequest request) throws IOException, InterruptedException;

    CompletableFuture<TurnResult> runAsync(ConversationService.Accepted accepted, TurnListener listener);
}
