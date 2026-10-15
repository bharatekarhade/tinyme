package com.tinyme.tools.handlers;

import com.tinyme.integrations.google.service.GmailClient;
import com.tinyme.integrations.google.service.GoogleMessageFormat;
import com.tinyme.tools.model.ToolContext;
import com.tinyme.tools.model.ToolResult;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GmailToolsTests {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final ToolContext context = new ToolContext(null, null, null, null, null);

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
}
