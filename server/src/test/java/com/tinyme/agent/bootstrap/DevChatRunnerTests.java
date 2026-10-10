package com.tinyme.agent.bootstrap;

import com.tinyme.agent.model.SessionRef;
import com.tinyme.agent.model.TurnRequest;
import com.tinyme.agent.service.TurnRunner;
import com.tinyme.agent.service.SessionManager;
import com.tinyme.agent.service.TurnListener;
import com.tinyme.agent.repository.MessageRepository;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(OutputCaptureExtension.class)
class DevChatRunnerTests {
    private final TurnRunner turns = mock(TurnRunner.class);
    private final SessionManager sessions = mock(SessionManager.class);
    private final MessageRepository messages = mock(MessageRepository.class);
    private final ConfigurableApplicationContext context = mock(ConfigurableApplicationContext.class);
    private final SessionRef session = new SessionRef(UUID.randomUUID(), "sesn_test");
    private static final UUID USER_MESSAGE_ROW_ID = UUID.fromString("29228cb9-f347-4230-a9d3-04f8ad69a862");

    private DevChatRunner runner(String zone) throws Exception {
        when(sessions.todaySession(any())).thenReturn(session);
        when(messages.insertUser(eq(session.sessionRowId()), any(UUID.class), anyString()))
                .thenReturn(USER_MESSAGE_ROW_ID);
        return new DevChatRunner(turns, sessions, messages, context, zone);
    }

    @Test
    void joinsMessageArgumentsPrintsReplyAndCloses(CapturedOutput output) throws Exception {
        var zone = ZoneId.of("Asia/Tokyo");
        when(turns.run(any(TurnRequest.class), any(TurnListener.class)))
                .thenReturn(new TurnResult("Logged coffee, 1 today.", 1));

        runner(zone.getId()).run("had", "a", "coffee", "--tinyme.dev.zone=Asia/Tokyo");

        verify(sessions).todaySession(zone);
        var request = captureRequest("had a coffee", zone);
        assertThat(request.userMessageId()).isEqualTo(USER_MESSAGE_ROW_ID);
        var clientMessageId = org.mockito.ArgumentCaptor.forClass(UUID.class);
        verify(messages).insertUser(eq(session.sessionRowId()), clientMessageId.capture(), eq("had a coffee"));
        assertThat(clientMessageId.getValue()).isNotEqualTo(request.userMessageId());
        assertThat(output.getOut()).contains("Logged coffee, 1 today.");
        verify(context).close();
    }

    @Test
    void defaultsToTheComputersTimezoneAndAcceptsAQuotedMessage() throws Exception {
        when(turns.run(any(TurnRequest.class), any(TurnListener.class))).thenReturn(new TurnResult("Done", 0));

        runner("").run("had a coffee");

        var request = captureRequest("had a coffee", ZoneId.systemDefault());
        assertThat(request.userMessageId()).isEqualTo(USER_MESSAGE_ROW_ID);
        verify(context).close();
    }

    @Test
    void missingMessageFailsWithUsageAndCloses() throws Exception {
        assertThatThrownBy(() -> runner("UTC").run("--spring.profiles.active=dev"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Provide a message");
        verifyNoInteractions(turns);
        verify(context).close();
    }

    @Test
    void turnFailurePropagatesAndCloses() throws Exception {
        var failure = new IOException("Turn failed");
        when(turns.run(any(TurnRequest.class), any(TurnListener.class))).thenThrow(failure);

        assertThatThrownBy(() -> runner("UTC").run("hello"))
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
        var zone = ZoneId.of("Asia/Tokyo");
        when(sessions.todaySession(zone)).thenReturn(session);
        when(messages.insertUser(eq(session.sessionRowId()), any(UUID.class), anyString()))
                .thenReturn(USER_MESSAGE_ROW_ID);
        when(turns.run(any(TurnRequest.class), any(TurnListener.class)))
                .thenReturn(new TurnResult("Logged from dev.", 1));
        var application = new SpringApplication(DevChatRunner.class);
        application.setRegisterShutdownHook(false);
        application.addInitializers(ctx -> {
            ctx.getBeanFactory().registerSingleton("turnRunner", turns);
            ctx.getBeanFactory().registerSingleton("sessionManager", sessions);
            ctx.getBeanFactory().registerSingleton("messageRepository", messages);
            ctx.getBeanFactory().registerSingleton("agentSetupRunner", setup);
        });

        try (var started = application.run("--spring.profiles.active=dev", "--tinyme.dev.zone=Asia/Tokyo",
                "had", "a", "coffee")) {
            var order = inOrder(setup, sessions, messages, turns);
            order.verify(setup).run(any());
            order.verify(sessions).todaySession(zone);
            var clientMessageId = org.mockito.ArgumentCaptor.forClass(UUID.class);
            order.verify(messages).insertUser(eq(session.sessionRowId()), clientMessageId.capture(), eq("had a coffee"));
            assertThat(clientMessageId.getValue()).isNotEqualTo(USER_MESSAGE_ROW_ID);
            order.verify(turns).run(eq(new TurnRequest(session, USER_MESSAGE_ROW_ID, "had a coffee", zone)),
                    any(TurnListener.class));
            assertThat(started.isActive()).isFalse();
            assertThat(started.getEnvironment().getProperty("spring.main.web-application-type")).isEqualTo("none");
            assertThat(output.getOut()).contains("Logged from dev.");
        }
    }

    private TurnRequest captureRequest(String text, ZoneId zone) throws IOException, InterruptedException {
        var captor = org.mockito.ArgumentCaptor.forClass(TurnRequest.class);
        verify(turns).run(captor.capture(), any(TurnListener.class));
        assertThat(captor.getValue().session()).isEqualTo(session);
        assertThat(captor.getValue().userMessageId()).isEqualTo(USER_MESSAGE_ROW_ID);
        assertThat(captor.getValue().text()).isEqualTo(text);
        assertThat(captor.getValue().zone()).isEqualTo(zone);
        return captor.getValue();
    }
}
