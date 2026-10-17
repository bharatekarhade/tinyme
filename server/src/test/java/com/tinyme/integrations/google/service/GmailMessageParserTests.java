package com.tinyme.integrations.google.service;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class GmailMessageParserTests {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void readsHeadersPlainTextAndListsAttachmentsWithoutDecodingThem() throws Exception {
        String body = "Hi, Aiko emailed about Friday's dinner.\n\nOn Thu, Oct 15, 2026 at 9:00 AM, Aiko wrote:\n> old text";
        String encoded = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(body.getBytes(StandardCharsets.UTF_8));
        String fixture = """
                {"id":"m1","threadId":"t1","snippet":"Friday dinner",
                 "payload":{"mimeType":"multipart/mixed","headers":[
                   {"name":"From","value":"Aiko <aiko@example.test>"},
                   {"name":"Subject","value":"Dinner Friday"},
                   {"name":"Date","value":"Thu, 15 Oct 2026 09:00:00 +0900"}],
                   "parts":[
                     {"mimeType":"text/plain","body":{"data":"%s"}},
                     {"filename":"menu.pdf","mimeType":"application/pdf","body":{"attachmentId":"att1"}}]}}
                """.formatted(encoded);

        var message = new GmailMessageParser().parse(JSON.readTree(fixture), true, 8_000);

        assertThat(message.id()).isEqualTo("m1");
        assertThat(message.threadId()).isEqualTo("t1");
        assertThat(message.from()).isEqualTo("Aiko <aiko@example.test>");
        assertThat(message.subject()).isEqualTo("Dinner Friday");
        assertThat(message.body()).isEqualTo("Hi, Aiko emailed about Friday's dinner.");
        assertThat(message.attachments()).containsExactly(
                new GmailMessageParser.Attachment("menu.pdf", "application/pdf", "att1"));
    }

    @Test
    void readsTheBodyOfARealGmailMessageWhosePartsHaveEmptyFilenames() throws Exception {
        // Gmail's format=full: every part carries "filename": "", attachments a real name.
        String plain = "利用加盟店：ローソン 渋谷店\n利用金額：1,280円";
        String html = "<p>HTML version</p>";
        String fixture = """
                {"id":"m2","payload":{"partId":"","mimeType":"multipart/mixed","filename":"","headers":[
                   {"name":"Subject","value":"【ゆうちょデビット】ご利用のお知らせ"}],"body":{"size":0},
                   "parts":[
                     {"partId":"0","mimeType":"multipart/alternative","filename":"","body":{"size":0},"parts":[
                       {"partId":"0.0","mimeType":"text/plain","filename":"","headers":[
                         {"name":"Content-Type","value":"text/plain; charset=UTF-8"}],"body":{"size":40,"data":"%s"}},
                       {"partId":"0.1","mimeType":"text/html","filename":"","body":{"size":20,"data":"%s"}}]},
                     {"partId":"1","mimeType":"text/plain","filename":"notes.txt",
                      "body":{"attachmentId":"att2","size":10}}]}}
                """.formatted(Base64.getUrlEncoder().withoutPadding().encodeToString(plain.getBytes(StandardCharsets.UTF_8)),
                Base64.getUrlEncoder().withoutPadding().encodeToString(html.getBytes(StandardCharsets.UTF_8)));

        var message = new GmailMessageParser().parse(JSON.readTree(fixture), true, 8_000);

        assertThat(message.body()).isEqualTo(plain);
        assertThat(message.attachments()).containsExactly(
                new GmailMessageParser.Attachment("notes.txt", "text/plain", "att2"));
    }

    @Test
    void boundsBodiesAndFallsBackToHtmlWithoutQuotedReply() throws Exception {
        String fixture = """
                {"payload":{"mimeType":"text/html","body":{"data":"%s"}}}
                """.formatted(Base64.getUrlEncoder().withoutPadding().encodeToString(
                "<p>Hello &amp; welcome</p><blockquote>older reply</blockquote>".getBytes(StandardCharsets.UTF_8)));
        var message = new GmailMessageParser().parse(JSON.readTree(fixture), true, 5);
        assertThat(message.body()).isEqualTo("Hello");
    }

    @Test
    void decodesIso2022JpAndStripsHtmlNoiseAndNumericEntities() throws Exception {
        String japanese = "こんにちは、メールです。";
        String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(
                japanese.getBytes(java.nio.charset.Charset.forName("ISO-2022-JP")));
        String fixture = """
                {"id":"jp1","payload":{"mimeType":"text/plain","headers":[
                  {"name":"Content-Type","value":"text/plain; charset=ISO-2022-JP"}],
                  "body":{"data":"%s"}}}
                """.formatted(encoded);
        var message = new GmailMessageParser().parse(JSON.readTree(fixture), true, 8_000);
        assertThat(message.body()).isEqualTo(japanese);

        String html = "<head><title>noise</title></head><style>.x{color:red}</style>"
                + "<script>bad()</script><p>Hello &#39;friend&#39; &#x1F44B;</p>";
        String htmlFixture = """
                {"payload":{"mimeType":"text/html","body":{"data":"%s"}}}
                """.formatted(Base64.getUrlEncoder().withoutPadding().encodeToString(
                html.getBytes(StandardCharsets.UTF_8)));
        var htmlMessage = new GmailMessageParser().parse(JSON.readTree(htmlFixture), true, 8_000);
        assertThat(htmlMessage.body()).isEqualTo("Hello 'friend' 👋");
    }

    @Test
    void decodesTheNamedEntitiesPaymentNoticesUse() throws Exception {
        String html = "<td>ご利用金額</td><td>&yen;440</td><p>Tom &amp; Jerry&nbsp;&middot; &amp;#39; &unknown;</p>";
        String fixture = """
                {"payload":{"mimeType":"text/html","body":{"data":"%s"}}}
                """.formatted(Base64.getUrlEncoder().withoutPadding().encodeToString(html.getBytes(StandardCharsets.UTF_8)));
        var message = new GmailMessageParser().parse(JSON.readTree(fixture), true, 8_000);
        assertThat(message.body()).isEqualTo("ご利用金額¥440Tom & Jerry · &#39; &unknown;");
    }
}
