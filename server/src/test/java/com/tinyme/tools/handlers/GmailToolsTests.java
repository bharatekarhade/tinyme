package com.tinyme.tools.handlers;

import com.tinyme.integrations.google.service.GmailClient;
import com.tinyme.integrations.google.service.GmailConfirmationService;
import com.tinyme.integrations.google.service.GoogleMessageFormat;
import com.tinyme.tools.model.ToolContext;
import com.tinyme.tools.model.ToolResult;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import com.tinyme.agent.service.TurnListener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GmailToolsTests {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final ToolContext context = new ToolContext(null, null, null, null, null);
    private final UUID sessionId = UUID.randomUUID();
    private final UUID toolCallId = UUID.randomUUID();
    private final ToolContext sendContext = new ToolContext(sessionId, toolCallId, ZoneId.of("Asia/Tokyo"),
            LocalDate.of(2026, 10, 15), Instant.parse("2026-10-15T00:00:00Z"), null, null,
            TurnListener.NONE, "tinyme-gmail", "gmail-thread-1");

    @Test
    void searchReturnsBoundedMetadataSummaries() throws Exception {
        GmailClient gmail = mock(GmailClient.class);
        when(gmail.searchMessages("from:aiko newer_than:7d", 5, null))
                .thenReturn(JSON.readTree("""
                        {"messages":[{"id":"m1"}]}
                        """));
        when(gmail.getMessage("m1", GoogleMessageFormat.METADATA)).thenReturn(JSON.readTree("""
                {"id":"m1","threadId":"t1","snippet":"Friday dinner",
                 "payload":{"headers":[{"name":"From","value":"Aiko"},
                 {"name":"Subject","value":"Dinner"},{"name":"Date","value":"Thu"}]}}
                """));

        ToolResult.Ok result = (ToolResult.Ok) new GmailSearchTool(gmail)
                .handle(JSON.readTree("""
                        {"query":"from:aiko newer_than:7d","max_results":5}
                        """), context);

        assertThat(result.data().toString()).contains("Aiko", "Dinner", "Friday dinner", "thread_id")
                .doesNotContain("body");
        verify(gmail).getMessage("m1", GoogleMessageFormat.METADATA);
    }

    @Test
    void readReturnsOnlyTrimmedTextAndAttachmentMetadata() throws Exception {
        GmailClient gmail = mock(GmailClient.class);
        String body = "Aiko said dinner is at seven.\nOn Thu, Aiko wrote:\n> quoted";
        String encoded = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(body.getBytes(StandardCharsets.UTF_8));
        when(gmail.getMessage("m1", GoogleMessageFormat.FULL)).thenReturn(JSON.readTree("""
                {"id":"m1","threadId":"t1","payload":{"mimeType":"multipart/mixed",
                 "headers":[{"name":"From","value":"Aiko"},{"name":"Subject","value":"Friday"}],
                 "parts":[{"mimeType":"text/plain","body":{"data":"%s"}},
                 {"filename":"invite.ics","mimeType":"text/calendar","body":{"attachmentId":"att1"}}]}}
                """.formatted(encoded)));

        ToolResult.Ok result = (ToolResult.Ok) new GmailReadTool(gmail)
                .handle(JSON.readTree("""
                        {"message_id":"m1"}
                        """), context);

        assertThat(result.data().toString()).contains("Aiko said dinner is at seven.", "invite.ics", "att1")
                .doesNotContain("quoted");
        verify(gmail).getMessage("m1", GoogleMessageFormat.FULL);
    }

    @Test
    void threadReturnsShortMessageSummaries() throws Exception {
        GmailClient gmail = mock(GmailClient.class);
        String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(
                "We can meet Friday.".getBytes(StandardCharsets.UTF_8));
        when(gmail.getThread("t1", GoogleMessageFormat.FULL)).thenReturn(JSON.readTree("""
                {"messages":[
                  {"id":"m1","threadId":"t1","payload":{"headers":[{"name":"From","value":"Aiko"},
                   {"name":"Subject","value":"Friday"}],"mimeType":"text/plain","body":{"data":"%s"}}},
                  {"id":"m2","threadId":"t1","payload":{"headers":[{"name":"From","value":"Me"},
                   {"name":"Subject","value":"Re: Friday"}],"mimeType":"text/plain","body":{"data":"%s"}}}
                ]}
                """.formatted(encoded, encoded)));

        ToolResult.Ok result = (ToolResult.Ok) new GmailThreadTool(gmail)
                .handle(JSON.readTree("""
                        {"thread_id":"t1"}
                        """), context);

        assertThat(result.data().toString()).contains("Aiko", "Re: Friday", "We can meet Friday.")
                .doesNotContain("attachmentId");
        verify(gmail).getThread("t1", GoogleMessageFormat.FULL);
    }

    @Test
    void draftCreatesOrUpdatesDraftAndDoesNotReturnOrStoreItsBody() throws Exception {
        GmailClient gmail = mock(GmailClient.class);
        when(gmail.saveDraft("aiko@example.test", null, "Dinner", "Let's meet at seven.", null))
                .thenReturn(JSON.readTree("{\"id\":\"draft-1\"}"));

        ToolResult.Ok result = (ToolResult.Ok) new GmailDraftTool(gmail).handle(JSON.readTree("""
                {"to":"aiko@example.test","subject":"Dinner","body":"Let's meet at seven."}
                """), context);

        assertThat(result.data().toString()).contains("draft-1", "aiko@example.test", "Dinner")
                .doesNotContain("Let's meet at seven.");
        assertThat(result.stored().toString()).doesNotContain("Let's meet at seven.");
        verify(gmail).saveDraft("aiko@example.test", null, "Dinner", "Let's meet at seven.", null);
    }

    @Test
    void sendToolOnlyCreatesConfirmationAndNeverSendsDirectly() throws Exception {
        GmailClient gmail = mock(GmailClient.class);
        GmailConfirmationService confirmations = mock(GmailConfirmationService.class);
        UUID confirmationId = UUID.randomUUID();
        String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(
                "Hello Aiko, see you Friday.".getBytes(StandardCharsets.UTF_8));
        when(gmail.getDraft("draft-1")).thenReturn(JSON.readTree("""
                {"id":"draft-1","message":{"id":"message-1","threadId":"thread-1",
                 "payload":{"mimeType":"text/plain","headers":[
                   {"name":"To","value":"aiko@example.test"},{"name":"Subject","value":"Friday"}],
                   "body":{"data":"%s"}}}}
                """.formatted(encoded)));
        when(confirmations.request(sessionId, "gmail-thread-1", toolCallId, "draft-1",
                "aiko@example.test", "Friday", "Hello Aiko, see you Friday.")).thenReturn(confirmationId);

        ToolResult.Ok result = (ToolResult.Ok) new GmailSendTool(gmail, confirmations)
                .handle(JSON.readTree("{\"draft_id\":\"draft-1\"}"), sendContext);

        assertThat(result.ref()).containsOnlyKeys("confirmation_id")
                .containsEntry("confirmation_id", confirmationId.toString());
        assertThat(result.data().toString()).contains("pending").doesNotContain("Hello Aiko");
        verify(gmail, org.mockito.Mockito.never()).sendDraft("draft-1");
        verify(confirmations).request(sessionId, "gmail-thread-1", toolCallId, "draft-1",
                "aiko@example.test", "Friday", "Hello Aiko, see you Friday.");
    }
}
