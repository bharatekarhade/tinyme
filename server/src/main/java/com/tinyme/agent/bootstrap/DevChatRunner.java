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

import java.time.ZoneId;
import java.util.UUID;

@Component
@Profile("dev")
@Order(1) // AgentSetupRunner provisions the resources first.
class DevChatRunner implements CommandLineRunner {
    private final TurnRunner turns;
    private final SessionManager sessions;
    private final MessageRepository messages;
    private final ConfigurableApplicationContext context;
    private final ZoneId zone;

    DevChatRunner(TurnRunner turns, SessionManager sessions, MessageRepository messages,
                  ConfigurableApplicationContext context,
                  @Value("${tinyme.dev.zone:}") String zone) {
        this.turns = turns;
        this.sessions = sessions;
        this.messages = messages;
        this.context = context;
        this.zone = zone.isBlank() ? ZoneId.systemDefault() : ZoneId.of(zone);
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
            System.out.println(turns.run(request, printer).replyText());
        } finally {
            // Release database/HTTP resources; the non-web application can then exit naturally.
            context.close();
        }
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
