package com.tinyme.agent.bootstrap;

import com.tinyme.agent.service.TurnRunner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.ZoneId;

@Component
@Profile("dev")
@Order(1) // AgentSetupRunner provisions the resources first.
class DevChatRunner implements CommandLineRunner {
    private final TurnRunner turns;
    private final ConfigurableApplicationContext context;
    private final ZoneId zone;

    DevChatRunner(TurnRunner turns, ConfigurableApplicationContext context,
                  @Value("${tinyme.dev.zone:}") String zone) {
        this.turns = turns;
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
            System.out.println(turns.run(message, zone).replyText());
        } finally {
            // Release database/HTTP resources; the non-web application can then exit naturally.
            context.close();
        }
    }
}
