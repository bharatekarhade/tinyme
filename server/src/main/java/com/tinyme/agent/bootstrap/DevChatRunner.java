package com.tinyme.agent.bootstrap;

import com.tinyme.agent.model.MessageRole;
import com.tinyme.agent.model.TurnEvent;
import com.tinyme.agent.model.TurnRequest;
import com.tinyme.agent.repository.MessageRepository;
import com.tinyme.agent.service.SessionManager;
import com.tinyme.agent.service.TurnListener;
import com.tinyme.agent.service.TurnRunner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Component
@Profile("dev")
@Order(1) // AgentSetupRunner provisions the resources first.
class DevChatRunner implements CommandLineRunner {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final TurnRunner turns;
    private final SessionManager sessions;
    private final MessageRepository messages;
    private final ConfigurableApplicationContext context;
    private final ZoneId zone;
    private final String eventCapture;

    DevChatRunner(TurnRunner turns, SessionManager sessions, MessageRepository messages,
                  ConfigurableApplicationContext context,
                  @Value("${tinyme.dev.zone:}") String zone,
                  @Value("${tinyme.dev.event-capture:${TINYME_DEV_EVENT_CAPTURE:}}") String eventCapture) {
        this.turns = turns;
        this.sessions = sessions;
        this.messages = messages;
        this.context = context;
        this.zone = zone.isBlank() ? ZoneId.systemDefault() : ZoneId.of(zone);
        this.eventCapture = eventCapture;
    }

    @Override
    public void run(String... args) throws Exception {
        try {
            String message = String.join(" ", new DefaultApplicationArguments(args).getNonOptionArgs());
            if (message.isBlank()) {
                throw new IllegalArgumentException("Provide a message, for example: "
                        + "./mvnw spring-boot:run -Dspring-boot.run.profiles=dev "
                        + "-Dspring-boot.run.arguments=\"had a coffee\"");
            }
            var session = sessions.todaySession(zone);
            UUID userMessageId = messages.insertUser(session.sessionRowId(), UUID.randomUUID(), message);
            var request = new TurnRequest(session, userMessageId, message, zone);
            var rawEvents = new ArrayList<JsonNode>();
            try {
                System.out.println(turns.run(request, printer, rawEvents::add).replyText());
            } finally {
                if (!eventCapture.isBlank()) writeCapture(rawEvents);
            }
        } finally {
            // Release database/HTTP resources; the non-web application can then exit naturally.
            context.close();
        }
    }

    private void writeCapture(List<JsonNode> events) throws Exception {
        Path destination = Path.of(eventCapture);
        Path parent = destination.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        Files.writeString(destination, JSON.writeValueAsString(events) + System.lineSeparator());
        System.out.println("Raw event fixture written to " + destination.toAbsolutePath());
    }

    TurnListener printer = event -> {
        switch (event) {
            case TurnEvent.ActionDone a -> System.out.println("✓ " + a.tool() + ": " + a.summary()
                    + (a.isError() ? " (error)" : ""));
            case TurnEvent.Text t -> System.out.println("💬 " + t.text());
            case TurnEvent.Failed f -> System.out.println("✗ " + f.code() + ": " + f.message());
        }
    };
}
