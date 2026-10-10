package com.tinyme.conversation.service;

import com.tinyme.agent.model.SessionRef;
import com.tinyme.agent.model.TurnRequest;
import com.tinyme.agent.model.TurnResult;
import com.tinyme.agent.repository.AgentSettingsRepository;
import com.tinyme.agent.repository.MessageRepository;
import com.tinyme.agent.service.SessionManager;
import com.tinyme.agent.service.TurnListener;
import com.tinyme.agent.service.TurnRunner;
import com.tinyme.conversation.model.ConversationMessageRequest;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@Service
public class ConversationService implements ConversationOperations {
    private static final String DEFAULT_ZONE = "Asia/Tokyo";

    private final SessionManager sessions;
    private final MessageRepository messages;
    private final AgentSettingsRepository settings;
    private final TurnRunner turns;

    public ConversationService(SessionManager sessions, MessageRepository messages,
                               AgentSettingsRepository settings, TurnRunner turns) {
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.turns = Objects.requireNonNull(turns, "turns");
    }

    @Override
    public Acceptance accept(ConversationMessageRequest request) throws IOException, InterruptedException {
        Objects.requireNonNull(request, "request");
        ZoneId zone = resolveZone(request.clientContext());
        SessionRef session = sessions.todaySession(zone);
        try {
            UUID userMessageId = messages.insertUser(session.sessionRowId(), request.clientMessageId(), request.text());
            return new Accepted(session, userMessageId, request.text(), zone);
        } catch (MessageRepository.DuplicateMessage duplicate) {
            return new Duplicate(duplicate.existingId());
        }
    }

    @Override
    public CompletableFuture<TurnResult> runAsync(Accepted accepted, TurnListener listener) {
        Objects.requireNonNull(accepted, "accepted");
        Objects.requireNonNull(listener, "listener");
        var future = new CompletableFuture<TurnResult>();
        Thread.ofVirtual().name("tinyme-conversation-turn").start(() -> {
            try {
                future.complete(turns.run(new TurnRequest(accepted.session(), accepted.userMessageId(),
                        accepted.text(), accepted.zone()), listener));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                future.completeExceptionally(interrupted);
            } catch (Throwable failure) {
                future.completeExceptionally(failure);
            }
        });
        return future;
    }

    private ZoneId resolveZone(ConversationMessageRequest.ClientContext clientContext) {
        String requested = clientContext == null ? null : clientContext.tz();
        if (requested != null) {
            try {
                return ZoneId.of(requested);
            } catch (DateTimeException invalidZone) {
                throw new InvalidClientTimezoneException("client_context.tz must be a valid time zone", invalidZone);
            }
        }
        String configured = settings.userTimezone().orElse(DEFAULT_ZONE);
        try {
            return ZoneId.of(configured);
        } catch (DateTimeException invalidZone) {
            throw new IllegalStateException("The configured user.tz setting must be a valid time zone", invalidZone);
        }
    }

    public sealed interface Acceptance permits Accepted, Duplicate {
    }

    public record Accepted(SessionRef session, UUID userMessageId, String text, ZoneId zone)
            implements Acceptance {
    }

    public record Duplicate(UUID existingId) implements Acceptance {
    }

    public static final class InvalidClientTimezoneException extends IllegalArgumentException {
        public InvalidClientTimezoneException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
