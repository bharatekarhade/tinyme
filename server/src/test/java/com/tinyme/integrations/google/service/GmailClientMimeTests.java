package com.tinyme.integrations.google.service;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GmailClientMimeTests {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void mimeEncodesInternationalSubjectAndValidatesRecipientAddresses() throws Exception {
        GoogleApiHttpClient http = mock(GoogleApiHttpClient.class);
        when(http.sendJson(eq("POST"), any(URI.class), any(JsonNode.class)))
                .thenReturn(JSON.readTree("{\"id\":\"draft-1\"}"));
        GmailClient client = new GmailClient(http);

        client.saveDraft("aiko@example.test", null, null, "こんにちは 🌸", "Body", null);

        var captor = org.mockito.ArgumentCaptor.forClass(JsonNode.class);
        verify(http).sendJson(eq("POST"), any(URI.class), captor.capture());
        byte[] raw = Base64.getUrlDecoder().decode(captor.getValue().path("message").path("raw").stringValue());
        String mime = new String(raw, StandardCharsets.UTF_8);
        assertThat(mime).contains("=?UTF-8?").contains("Body");
        assertThatThrownBy(() -> client.saveDraft("not an address", null, null, "Subject", "Body", null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Email address is invalid");
    }
}
