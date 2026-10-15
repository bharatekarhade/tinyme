package com.tinyme.integrations.google.service;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GmailConfirmationServiceTests {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void sendsOnlyAfterConfirmationWasClaimed() throws Exception {
        GmailConfirmationRepository repository = mock(GmailConfirmationRepository.class);
        GmailClient gmail = mock(GmailClient.class);
        UUID confirmationId = UUID.randomUUID();
        when(repository.decide(confirmationId, true)).thenReturn(Optional.of(JSON.readTree("""
                {"draft_id":"draft-42"}
                """)));
        when(gmail.sendDraft("draft-42")).thenReturn(JSON.readTree("{\"id\":\"sent-42\"}"));
        GmailConfirmationService service = new GmailConfirmationService(repository, gmail);

        var result = service.decide(confirmationId, "send");

        assertThat(result.status()).isEqualTo("sent");
        assertThat(result.messageId()).isEqualTo("sent-42");
        verify(gmail).sendDraft("draft-42");
    }

    @Test
    void missingOrPreviouslyHandledConfirmationCannotSend() throws Exception {
        GmailConfirmationRepository repository = mock(GmailConfirmationRepository.class);
        GmailClient gmail = mock(GmailClient.class);
        UUID confirmationId = UUID.randomUUID();
        when(repository.decide(confirmationId, true)).thenReturn(Optional.empty());
        GmailConfirmationService service = new GmailConfirmationService(repository, gmail);

        assertThatThrownBy(() -> service.decide(confirmationId, "send"))
                .isInstanceOf(GmailConfirmationService.MissingConfirmationException.class);
        verify(gmail, never()).sendDraft(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void cancelDoesNotCallGmail() throws Exception {
        GmailConfirmationRepository repository = mock(GmailConfirmationRepository.class);
        GmailClient gmail = mock(GmailClient.class);
        UUID confirmationId = UUID.randomUUID();
        when(repository.decide(confirmationId, false)).thenReturn(Optional.of(JSON.createObjectNode()));
        GmailConfirmationService service = new GmailConfirmationService(repository, gmail);

        assertThat(service.decide(confirmationId, "cancel").status()).isEqualTo("rejected");
        verify(gmail, never()).sendDraft(org.mockito.ArgumentMatchers.anyString());
    }
}
