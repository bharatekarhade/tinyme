package com.tinyme.integrations.google.service;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MailScanServiceTests {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 16);
    private static final MailScanAgentRunner.MailInput EMAIL = new MailScanAgentRunner.MailInput(
            "gmail-flight-01", "Example Airlines <tickets@example-air.test>",
            "2026-10-15T18:20:00+09:00", "Your flight to Osaka is confirmed",
            "Your flight to Osaka is confirmed for October 24, 2026. Departure is at 09:30 from Haneda.");

    @Test
    void acceptsOnlyFutureSuggestionsWhoseEvidenceAppearsInTheSourceEmail() {
        var proposal = json("""
                {"type":"event","title":"Flight to Osaka","day":"2026-10-24","time":"09:30",
                 "place":"Haneda Airport","confidence":0.98,
                 "evidence":"Your flight to Osaka is confirmed for October 24, 2026.",
                 "gmail_message_id":"gmail-flight-01"}
                """);

        var result = MailScanService.validate(proposal, EMAIL, TODAY);

        assertThat(result).isNotNull();
        assertThat(result.type()).isEqualTo("event");
        assertThat(result.payload()).containsEntry("day", "2026-10-24").containsEntry("time", "09:30");
    }

    @Test
    void rejectsHallucinatedEvidenceLowConfidenceAndPastDates() {
        assertThat(MailScanService.validate(json("""
                {"type":"event","title":"Flight","day":"2026-10-24","confidence":0.9,
                 "evidence":"The flight is at 2pm.","gmail_message_id":"gmail-flight-01"}
                """), EMAIL, TODAY)).isNull();
        assertThat(MailScanService.validate(json("""
                {"type":"event","title":"Flight","day":"2026-10-24","confidence":0.59,
                 "evidence":"Your flight to Osaka is confirmed","gmail_message_id":"gmail-flight-01"}
                """), EMAIL, TODAY)).isNull();
        assertThat(MailScanService.validate(json("""
                {"type":"event","title":"Flight","day":"2026-10-15","confidence":0.9,
                 "evidence":"Your flight to Osaka is confirmed","gmail_message_id":"gmail-flight-01"}
                """), EMAIL, TODAY)).isNull();
    }

    @Test
    void rejectsTimedTasksAndInvalidEndTime() {
        assertThat(MailScanService.validate(json("""
                {"type":"task","title":"Check in","day":"2026-10-24","time":"09:30","confidence":0.9,
                 "evidence":"Your flight to Osaka is confirmed","gmail_message_id":"gmail-flight-01"}
                """), EMAIL, TODAY)).isNull();
        assertThat(MailScanService.validate(json("""
                {"type":"event","title":"Flight","day":"2026-10-24","time":"10:30","end_time":"09:30",
                 "confidence":0.9,"evidence":"Your flight to Osaka is confirmed","gmail_message_id":"gmail-flight-01"}
                """), EMAIL, TODAY)).isNull();
    }

    @Test
    void convertsEmailLocalTimeIntoTheUsersZoneAndKeepsTheOriginalDisplayTime() {
        var result = MailScanService.validate(json("""
                {"type":"event","title":"Flight","day":"2026-10-24","time":"10:00",
                 "time_zone":"America/Los_Angeles","confidence":0.9,
                 "evidence":"Your flight to Osaka is confirmed","gmail_message_id":"gmail-flight-01"}
                """), EMAIL, TODAY, ZoneId.of("Asia/Tokyo"));

        assertThat(result).isNotNull();
        assertThat(result.payload()).containsEntry("day", "2026-10-25")
                .containsEntry("time", "02:00").containsEntry("source_time", "10:00")
                .containsEntry("source_time_zone", "America/Los_Angeles");
    }

    @Test
    void rejectsAnInvalidIanaTimeZone() {
        assertThat(MailScanService.validate(json("""
                {"type":"event","title":"Flight","day":"2026-10-24","time":"10:00",
                 "time_zone":"Pacific/NotAZone","confidence":0.9,
                 "evidence":"Your flight to Osaka is confirmed","gmail_message_id":"gmail-flight-01"}
                """), EMAIL, TODAY, ZoneId.of("Asia/Tokyo"))).isNull();
    }

    @Test
    void filtersDraftSpamTrashAndChatLabelsBeforeReadingBodies() {
        for (String label : List.of("DRAFT", "SPAM", "TRASH", "CHAT")) {
            assertThat(MailScanService.prefiltered(json("{\"labelIds\":[\"" + label
                    + "\"],\"payload\":{\"headers\":[]}}"),
                    new GmailMessageParser.Message("m", "t", null, null, null, null, "Subject", null,
                            null, null, List.of()))).as(label).isTrue();
        }
    }

    @Test
    void dropsAlreadyScannedHistoryIdsBeforeApplyingTheLimitAndAdvancesAfterTwoRuns() throws Exception {
        var gmail = org.mockito.Mockito.mock(GmailClient.class);
        var scanner = org.mockito.Mockito.mock(MailScanAgentRunner.class);
        var repository = org.mockito.Mockito.mock(MailScanRepository.class);
        var zone = org.mockito.Mockito.mock(com.tinyme.settings.UserZone.class);
        var now = java.time.Instant.parse("2026-10-16T00:00:00Z");
        org.mockito.Mockito.when(zone.current()).thenReturn(ZoneId.of("Asia/Tokyo"));
        org.mockito.Mockito.when(repository.startRun()).thenReturn(java.util.UUID.randomUUID());
        org.mockito.Mockito.when(repository.scannedSince(org.mockito.ArgumentMatchers.any())).thenReturn(0);

        List<String> ids = java.util.stream.IntStream.range(0, 45).mapToObj(i -> "msg-" + i).toList();
        String rows = ids.stream().map(id -> "{\"messagesAdded\":[{\"message\":{\"id\":\"" + id + "\"}}]}")
                .collect(java.util.stream.Collectors.joining(","));
        var history = JSON.readTree("{\"historyId\":\"history-new\",\"history\":[" + rows + "]}");
        org.mockito.Mockito.when(gmail.history("history-old", null, 500)).thenReturn(history);
        var draft = JSON.readTree("{\"labelIds\":[\"DRAFT\"],\"payload\":{\"headers\":[]}}");
        org.mockito.Mockito.when(gmail.getMessage(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq(GoogleMessageFormat.METADATA))).thenReturn(draft);

        var cursor = new java.util.concurrent.atomic.AtomicReference<>(new MailScanRepository.ScanState(
                "history-old", null, true, null, List.of(), null));
        var scanned = java.util.concurrent.ConcurrentHashMap.<String>newKeySet();
        org.mockito.Mockito.when(repository.state()).thenAnswer(ignored -> java.util.Optional.of(cursor.get()));
        org.mockito.Mockito.when(repository.scanned(org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(call -> scanned.contains(call.getArgument(0)));
        org.mockito.Mockito.doAnswer(call -> { scanned.add(call.getArgument(0)); return null; })
                .when(repository).markScanned(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
        org.mockito.Mockito.doAnswer(call -> {
            List<String> pending = call.getArgument(0);
            cursor.set(new MailScanRepository.ScanState("history-old", null, true, call.getArgument(1),
                    pending, call.getArgument(2)));
            return null;
        }).when(repository).saveHistoryProgress(org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.nullable(String.class), org.mockito.ArgumentMatchers.anyString());
        org.mockito.Mockito.doAnswer(call -> {
            cursor.set(new MailScanRepository.ScanState(call.getArgument(0), null, true, null, List.of(), null));
            return null;
        }).when(repository).saveHistoryId(org.mockito.ArgumentMatchers.anyString());

        var service = new MailScanService(gmail, scanner, repository, zone,
                java.time.Clock.fixed(now, ZoneId.of("UTC")), 30, 200);
        var first = service.run();
        assertThat(first.scanned()).isEqualTo(30);
        assertThat(cursor.get().pendingIds()).hasSize(15);

        // Simulate Gmail replaying the old page while the 30 terminal IDs are still present.
        cursor.set(new MailScanRepository.ScanState("history-old", null, true, null, ids, "history-new"));
        var second = service.run();
        assertThat(second.scanned()).isEqualTo(15);
        assertThat(scanned).hasSize(45);
        assertThat(cursor.get().historyId()).isEqualTo("history-new");
        org.mockito.Mockito.verify(scanner, org.mockito.Mockito.never()).extract(
                org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        org.mockito.Mockito.verify(gmail, org.mockito.Mockito.never()).getMessage(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq(GoogleMessageFormat.FULL));
    }

    private static tools.jackson.databind.JsonNode json(String source) {
        return JSON.readTree(source);
    }
}
