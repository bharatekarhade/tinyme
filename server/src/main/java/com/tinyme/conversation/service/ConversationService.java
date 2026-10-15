package com.tinyme.conversation.service;

import com.tinyme.agent.model.SessionRef;
import com.tinyme.agent.model.TurnRequest;
import com.tinyme.agent.model.TurnLocation;
import com.tinyme.agent.model.TurnResult;
import com.tinyme.agent.repository.AgentSessionRepository;
import com.tinyme.agent.repository.MessageRepository;
import com.tinyme.agent.service.SessionManager;
import com.tinyme.agent.service.TurnListener;
import com.tinyme.agent.service.TurnRunner;
import com.tinyme.settings.UserZone;
import com.tinyme.maintenance.MaintenanceGate;
import com.tinyme.conversation.model.ConversationMessageRequest;
import com.tinyme.conversation.model.ConversationImage;
import com.tinyme.conversation.model.ConversationHistory;
import com.tinyme.conversation.model.ConversationHistoryMessage;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.time.Clock;
import java.time.LocalDate;
import java.time.Instant;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@Service
public class ConversationService implements ConversationOperations {

    private final SessionManager sessions;
    private final MessageRepository messages;
    private final UserZone userZone;
    private final TurnRunner turns;
    private final AgentSessionRepository agentSessions;
    private final Clock clock;
    private final MaintenanceGate maintenance;
    private final ConversationImageService images;

    public ConversationService(SessionManager sessions, MessageRepository messages,
                               TurnRunner turns,
                               AgentSessionRepository agentSessions, Clock clock, UserZone userZone,
                               MaintenanceGate maintenance, ConversationImageService images) {
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.userZone = Objects.requireNonNull(userZone, "userZone");
        this.turns = Objects.requireNonNull(turns, "turns");
        this.agentSessions = Objects.requireNonNull(agentSessions, "agentSessions");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.maintenance = Objects.requireNonNull(maintenance, "maintenance");
        this.images = Objects.requireNonNull(images, "images");
    }

    @Override
    @Transactional(readOnly = true)
    public ConversationHistory history(LocalDate requestedDay) {
        ZoneId zone = userZone.current();
        LocalDate day = requestedDay == null ? LocalDate.now(clock.withZone(zone)) : requestedDay;
        var sessionIds = agentSessions.findChatSessionIds(day);
        var rows = messages.findForSessions(sessionIds);
        return new ConversationHistory(rows.stream()
                .map(row -> new ConversationHistoryMessage(row.id(), row.role().name(), row.content(),
                        row.actions(), row.attachments(), row.createdAt()))
                .toList());
    }

    @Override
    public Acceptance accept(ConversationMessageRequest request) throws IOException, InterruptedException {
        Objects.requireNonNull(request, "request");
        var permit = maintenance.enterTurn();
        try {
            ZoneId zone = resolveZone(request.clientContext());
            Instant receivedAt = clock.instant();
            Instant sentAt = request.sentAt() == null ? receivedAt : request.sentAt();
            if (sentAt.isAfter(receivedAt.plus(Duration.ofDays(1)))) {
                throw new InvalidClientTimestampException("sent_at cannot be more than one day in the future");
            }
            TurnLocation location = validatedLocation(request.clientContext(), receivedAt);
            SessionRef session = sessions.todaySession(zone);
            var existing = messages.findIdByClientMessageId(request.clientMessageId());
            if (existing.isPresent()) {
                permit.close();
                return new Duplicate(existing.get());
            }
            var storedImages = new java.util.ArrayList<ConversationImageService.StoredImage>();
            try {
                for (ConversationImage image : request.attachments()) storedImages.add(images.store(image));
                var historyAttachments = storedImages.stream().map(ConversationImageService.StoredImage::history).toList();
                var imageBlocks = storedImages.stream().map(ConversationImageService.StoredImage::model).toList();
                UUID userMessageId = messages.insertUser(session.sessionRowId(), request.clientMessageId(),
                        request.text(), historyAttachments);
                UUID attachedPhotoId = storedImages.isEmpty() ? null : storedImages.getFirst().photoId();
                return new Accepted(session, userMessageId, request.text(), zone, sentAt, imageBlocks,
                        attachedPhotoId, location, permit);
            } catch (RuntimeException | IOException | Error failure) {
                for (var image : storedImages) images.deleteStored(image);
                throw failure;
            }
        } catch (MessageRepository.DuplicateMessage duplicate) {
            permit.close();
            return new Duplicate(duplicate.existingId());
        } catch (IOException | InterruptedException | RuntimeException | Error failure) {
            permit.close();
            throw failure;
        }
    }

    @Override
    public CompletableFuture<TurnResult> runAsync(Accepted accepted, TurnListener listener) {
        Objects.requireNonNull(accepted, "accepted");
        Objects.requireNonNull(listener, "listener");
        var future = new CompletableFuture<TurnResult>();
        try {
          Thread.ofVirtual().name("tinyme-conversation-turn").start(() -> {
            try {
                future.complete(turns.run(new TurnRequest(accepted.session(), accepted.userMessageId(),
                        accepted.text(), accepted.zone(), accepted.sentAt(), accepted.imageBlocks(),
                        accepted.attachedPhotoId(), accepted.location()), listener));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                future.completeExceptionally(interrupted);
            } catch (Throwable failure) {
                future.completeExceptionally(failure);
            } finally {
                accepted.permit().close();
            }
          });
        } catch (RuntimeException | Error failure) {
            accepted.permit().close();
            throw failure;
        }
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
        return userZone.current();
    }

    private static TurnLocation validatedLocation(ConversationMessageRequest.ClientContext clientContext,
                                                  Instant receivedAt) {
        if (clientContext == null || clientContext.location() == null) return null;
        var location = clientContext.location();
        if (location.lat() == null || location.lng() == null || location.accuracyM() == null || location.at() == null) {
            return null;
        }
        if (!Double.isFinite(location.lat()) || location.lat() < -90 || location.lat() > 90
                || !Double.isFinite(location.lng()) || location.lng() < -180 || location.lng() > 180
                || !Double.isFinite(location.accuracyM()) || location.accuracyM() < 0 || location.accuracyM() >= 500
                || location.at().isAfter(receivedAt)
                || location.at().isBefore(receivedAt.minus(Duration.ofMinutes(10)))) {
            return null;
        }
        return new TurnLocation(location.lat(), location.lng(), location.accuracyM(), location.at(), location.area());
    }

    public sealed interface Acceptance permits Accepted, Duplicate {
    }

    public record Accepted(SessionRef session, UUID userMessageId, String text, ZoneId zone, Instant sentAt,
                           java.util.List<java.util.Map<String, Object>> imageBlocks,
                           UUID attachedPhotoId,
                           TurnLocation location,
                           MaintenanceGate.TurnPermit permit)
            implements Acceptance {
        public Accepted(SessionRef session, UUID userMessageId, String text, ZoneId zone) {
            this(session, userMessageId, text, zone, Instant.now(), java.util.List.of(), null, null,
                    MaintenanceGate.detachedPermit());
        }

        public Accepted(SessionRef session, UUID userMessageId, String text, ZoneId zone,
                        MaintenanceGate.TurnPermit permit) {
            this(session, userMessageId, text, zone, Instant.now(), java.util.List.of(), null, null, permit);
        }

        public Accepted(SessionRef session, UUID userMessageId, String text, ZoneId zone, Instant sentAt,
                        java.util.List<java.util.Map<String, Object>> imageBlocks,
                        MaintenanceGate.TurnPermit permit) {
            this(session, userMessageId, text, zone, sentAt, imageBlocks, null, null, permit);
        }

        public Accepted(SessionRef session, UUID userMessageId, String text, ZoneId zone, Instant sentAt,
                        java.util.List<java.util.Map<String, Object>> imageBlocks, UUID attachedPhotoId,
                        MaintenanceGate.TurnPermit permit) {
            this(session, userMessageId, text, zone, sentAt, imageBlocks, attachedPhotoId, null, permit);
        }

        public Accepted {
            Objects.requireNonNull(permit, "permit");
            imageBlocks = java.util.List.copyOf(Objects.requireNonNull(imageBlocks, "imageBlocks"));
        }
    }

    public record Duplicate(UUID existingId) implements Acceptance {
    }

    public static final class InvalidClientTimezoneException extends IllegalArgumentException {
        public InvalidClientTimezoneException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static final class InvalidClientTimestampException extends IllegalArgumentException {
        public InvalidClientTimestampException(String message) { super(message); }
    }
}
