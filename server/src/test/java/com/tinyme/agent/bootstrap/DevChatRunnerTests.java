package com.tinyme.agent.bootstrap;

import com.tinyme.agent.service.TurnRunner;
import com.tinyme.agent.model.TurnResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;

import java.io.IOException;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(OutputCaptureExtension.class)
class DevChatRunnerTests {
    private final TurnRunner turns = mock(TurnRunner.class);
    private final ConfigurableApplicationContext context = mock(ConfigurableApplicationContext.class);

    @Test
    void joinsMessageArgumentsPrintsReplyAndCloses(CapturedOutput output) throws Exception {
        var zone = ZoneId.of("Asia/Tokyo");
        when(turns.run("had a coffee", zone)).thenReturn(new TurnResult("Logged coffee, 1 today.", 1));

        new DevChatRunner(turns, context, zone.getId()).run("had", "a", "coffee", "--tinyme.dev.zone=Asia/Tokyo");

        verify(turns).run("had a coffee", zone);
        assertThat(output.getOut()).contains("Logged coffee, 1 today.");
        verify(context).close();
    }

    @Test
    void defaultsToTheComputersTimezoneAndAcceptsAQuotedMessage() throws Exception {
        when(turns.run(anyString(), any())).thenReturn(new TurnResult("Done", 0));

        new DevChatRunner(turns, context, "").run("had a coffee");

        verify(turns).run("had a coffee", ZoneId.systemDefault());
        verify(context).close();
    }

    @Test
    void missingMessageFailsWithUsageAndCloses() {
        assertThatThrownBy(() -> new DevChatRunner(turns, context, "UTC").run("--spring.profiles.active=dev"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Provide a message");
        verifyNoInteractions(turns);
        verify(context).close();
    }

    @Test
    void turnFailurePropagatesAndCloses() throws Exception {
        var failure = new IOException("Turn failed");
        when(turns.run(anyString(), any())).thenThrow(failure);

        assertThatThrownBy(() -> new DevChatRunner(turns, context, "UTC").run("hello"))
                .isSameAs(failure);
        verify(context).close();
    }

    @Test
    void runnerIsAbsentWithoutDevProfile() {
        new ApplicationContextRunner().withUserConfiguration(DevChatRunner.class)
                .run(application -> assertThat(application).doesNotHaveBean(DevChatRunner.class));
    }

    @Test
    void devStartupRunsSetupBeforeChatAndReturnsAClosedNonWebContext(CapturedOutput output) throws Exception {
        var setup = mock(AgentSetupRunner.class);
        when(turns.run("had a coffee", ZoneId.of("Asia/Tokyo")))
                .thenReturn(new TurnResult("Logged from dev.", 1));
        var application = new SpringApplication(DevChatRunner.class);
        application.setRegisterShutdownHook(false);
        application.addInitializers(ctx -> {
            ctx.getBeanFactory().registerSingleton("turnRunner", turns);
            ctx.getBeanFactory().registerSingleton("agentSetupRunner", setup);
        });

        try (var started = application.run("--spring.profiles.active=dev", "--tinyme.dev.zone=Asia/Tokyo",
                "had", "a", "coffee")) {
            var order = inOrder(setup, turns);
            order.verify(setup).run(any());
            order.verify(turns).run("had a coffee", ZoneId.of("Asia/Tokyo"));
            assertThat(started.isActive()).isFalse();
            assertThat(started.getEnvironment().getProperty("spring.main.web-application-type")).isEqualTo("none");
            assertThat(output.getOut()).contains("Logged from dev.");
        }
    }
}
