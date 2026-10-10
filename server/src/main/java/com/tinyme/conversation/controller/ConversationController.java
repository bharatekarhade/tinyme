package com.tinyme.conversation.controller;

import com.tinyme.conversation.model.ConversationMessageRequest;
import com.tinyme.conversation.service.ConversationOperations;
import com.tinyme.conversation.service.ConversationService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.ZoneId;
import java.util.Objects;

@RestController
@RequestMapping("/conversations")
public class ConversationController {
    private final ConversationOperations conversations;
    private final long emitterTimeoutMillis;

    @Autowired
    public ConversationController(ConversationOperations conversations,
                                  @Value("${tinyme.agent.turn-timeout:120s}") Duration turnTimeout) {
        this.conversations = Objects.requireNonNull(conversations, "conversations");
        if (turnTimeout.isNegative() || turnTimeout.isZero()) {
            throw new IllegalArgumentException("Turn timeout must be positive");
        }
        this.emitterTimeoutMillis = turnTimeout.plusSeconds(30).toMillis();
    }

    public ConversationController(ConversationOperations conversations) {
        this(conversations, Duration.ofSeconds(120));
    }

    @PostMapping("/today/messages")
    public ResponseEntity<?> message(@Valid @RequestBody ConversationMessageRequest request)
            throws IOException, InterruptedException {
        validateClientZone(request);
        final ConversationService.Acceptance acceptance;
        try {
            acceptance = conversations.accept(request);
        } catch (ConversationService.InvalidClientTimezoneException invalidZone) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, invalidZone.getMessage(), invalidZone);
        }
        if (acceptance instanceof ConversationService.Duplicate duplicate) {
            var problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                    "This client_msg_id was already accepted");
            problem.setProperty("message_id", duplicate.existingId());
            return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
        }

        var accepted = (ConversationService.Accepted) acceptance;
        var emitter = new SseEmitter(emitterTimeoutMillis);
        var sink = new SseTurnSink(emitter);
        sink.start(accepted.userMessageId());
        try {
            conversations.runAsync(accepted, sink).whenComplete(sink::complete);
        } catch (RuntimeException failure) {
            sink.complete(null, failure);
        }
        return ResponseEntity.ok().contentType(MediaType.TEXT_EVENT_STREAM).body(emitter);
    }

    private static void validateClientZone(ConversationMessageRequest request) {
        String tz = request.clientContext() == null ? null : request.clientContext().tz();
        if (tz == null) return;
        try {
            ZoneId.of(tz);
        } catch (DateTimeException invalidZone) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "client_context.tz must be a valid time zone", invalidZone);
        }
    }

}
