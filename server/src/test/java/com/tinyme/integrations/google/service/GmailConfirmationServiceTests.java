package com.tinyme.integrations.google.service;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.Optional;
import java.util.UUID;
import java.util.Base64;
import java.nio.charset.StandardCharsets;
import java.util.List;

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
        String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString("Mail body".getBytes(StandardCharsets.UTF_8));
        JsonNode draft = JSON.readTree("""
                {"message":{"payload":{"mimeType":"text/plain","headers":[
                 {"name":"To","value":"person@example.test"},{"name":"Subject","value":"Subject"}],
                 "body":{"data":"%s"}}}}
                """.formatted(encoded));
        when(repository.pendingPayload(confirmationId)).thenReturn(Optional.of(JSON.readTree("""
                {"kind":"email","draft_id":"draft-42","draft_hash":"%s"}
                """.formatted(GmailConfirmationService.hash("person@example.test", null, null,
                "Subject", "Mail body", List.of())))));
        when(gmail.getDraft("draft-42")).thenReturn(draft);
        when(repository.decide(confirmationId, true)).thenReturn(Optional.of(JSON.readTree("""
                {"draft_id":"draft-42"}
                """)));
        when(gmail.sendDraft("draft-42")).thenReturn(JSON.readTree("{\"id\":\"sent-42\"}"));
        GmailConfirmationService service = service(repository, gmail);

        var result = service.decide(confirmationId, "send");

        assertThat(result.status()).isEqualTo("sent");
        assertThat(result.messageId()).isEqualTo("sent-42");
        verify(gmail).sendDraft("draft-42");
        verify(repository).markCompleted(confirmationId, "sent");
    }

    @Test
    void changedDraftExpiresConfirmationAndIsNeverSent() throws Exception {
        GmailConfirmationRepository repository = mock(GmailConfirmationRepository.class);
        GmailClient gmail = mock(GmailClient.class);
        UUID confirmationId = UUID.randomUUID();
        when(repository.pendingPayload(confirmationId)).thenReturn(Optional.of(JSON.readTree("""
                {"kind":"email","draft_id":"draft-42","draft_hash":"old-hash"}
                """)));
        when(gmail.getDraft("draft-42")).thenReturn(JSON.readTree("""
                {"message":{"payload":{"mimeType":"text/plain","headers":[
                 {"name":"To","value":"attacker@example.test"},{"name":"Subject","value":"Changed"}],
                 "body":{"data":"%s"}}}}
                """.formatted(Base64.getUrlEncoder().withoutPadding().encodeToString(
                "Changed body".getBytes(StandardCharsets.UTF_8)))));

        assertThatThrownBy(() -> service(repository, gmail).decide(confirmationId, "send"))
                .isInstanceOf(GmailConfirmationService.ConfirmationChangedException.class);
        verify(repository).expirePending(confirmationId);
        verify(repository, never()).decide(confirmationId, true);
        verify(gmail, never()).sendDraft(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void missingOrPreviouslyHandledConfirmationCannotSend() throws Exception {
        GmailConfirmationRepository repository = mock(GmailConfirmationRepository.class);
        GmailClient gmail = mock(GmailClient.class);
        UUID confirmationId = UUID.randomUUID();
        when(repository.decide(confirmationId, true)).thenReturn(Optional.empty());
        GmailConfirmationService service = service(repository, gmail);

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
        GmailConfirmationService service = service(repository, gmail);

        assertThat(service.decide(confirmationId, "cancel").status()).isEqualTo("rejected");
        verify(gmail, never()).sendDraft(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void confirmsCalendarReplyOnlyAfterUserApproval() throws Exception {
        GmailConfirmationRepository repository = mock(GmailConfirmationRepository.class);
        GmailClient gmail = mock(GmailClient.class);
        CalendarClient calendar = mock(CalendarClient.class);
        GoogleAccountRepository accounts = mock(GoogleAccountRepository.class);
        UUID confirmationId = UUID.randomUUID();
        when(repository.decide(confirmationId, true)).thenReturn(Optional.of(JSON.readTree("""
                {"kind":"calendar_invite","calendar_id":"primary","event_id":"invite-7","response":"declined"}
                """)));
        when(repository.pendingPayload(confirmationId)).thenReturn(Optional.of(JSON.readTree("""
                {"kind":"calendar_invite"}
                """)));
        when(accounts.find()).thenReturn(Optional.of(new GoogleAccountRepository.GoogleAccount(
                "owner@example.test", java.util.List.of(), new byte[]{1}, "connected", java.time.Instant.now())));
        GmailConfirmationService service = new GmailConfirmationService(repository, gmail, calendar, accounts);

        assertThat(service.decide(confirmationId, "send").status()).isEqualTo("responded");
        verify(calendar).respondToInvite("primary", "invite-7", "owner@example.test", "declined");
        verify(gmail, never()).sendDraft(org.mockito.ArgumentMatchers.anyString());
    }

    private static GmailConfirmationService service(GmailConfirmationRepository repository, GmailClient gmail) {
        return new GmailConfirmationService(repository, gmail, mock(CalendarClient.class),
                mock(GoogleAccountRepository.class));
    }
}
