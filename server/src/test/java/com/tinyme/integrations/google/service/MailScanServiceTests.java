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

    private static final MailScanAgentRunner.MailInput CARD_EMAIL = new MailScanAgentRunner.MailInput(
            "gmail-card-01", "yuchodebit <info@example-bank.test>", "2026-10-15T13:58:00+09:00",
            "【ゆうちょデビット】ご利用のお知らせ", """
            ゆうちょデビットのご利用を受け付けました。
            利用日時　2026/10/15 13:57:29
            利用店舗　ORACLE CORPORATION JAPAN
            利用金額　-155円
            利用通貨　JPY
            承認番号　304395
            ※本メールのご利用金額は暫定金額です。""");

    @Test
    void readsACardNoticeAsAPaymentWithTheAmountTheEmailActuallyShows() {
        var result = MailScanService.validate(json("""
                {"type":"payment","title":"ORACLE CORPORATION JAPAN","day":"2026-10-15","time":"13:57",
                 "amount":155,"currency":"JPY","direction":"purchase","provisional":true,
                 "card":"ゆうちょデビット","reference":"304395","confidence":0.95,
                 "evidence":"利用金額　-155円","gmail_message_id":"gmail-card-01"}
                """), CARD_EMAIL, TODAY, ZoneId.of("Asia/Tokyo"));

        assertThat(result).isNotNull();
        assertThat(result.type()).isEqualTo("payment");
        assertThat(result.payload()).containsEntry("merchant", "ORACLE CORPORATION JAPAN")
                .containsEntry("day", "2026-10-15").containsEntry("time", "13:57")
                .containsEntry("amount", "155").containsEntry("currency", "JPY")
                .containsEntry("direction", "purchase").containsEntry("provisional", true)
                .containsEntry("card", "ゆうちょデビット").containsEntry("reference", "304395");
    }

    @Test
    void refusesPaymentsWithAMadeUpAmountAFakeCurrencyOrAnOddDay() {
        String base = """
                {"type":"payment","title":"ORACLE CORPORATION JAPAN","day":"%s","amount":%s,"currency":"%s",
                 "confidence":0.95,"evidence":"利用金額　-155円","gmail_message_id":"gmail-card-01"%s}
                """;
        ZoneId tokyo = ZoneId.of("Asia/Tokyo");
        assertThat(MailScanService.validate(json(base.formatted("2026-10-15", "1550", "JPY", "")), CARD_EMAIL, TODAY, tokyo))
                .as("amount not in the email").isNull();
        assertThat(MailScanService.validate(json(base.formatted("2026-10-15", "155", "YEN", "")), CARD_EMAIL, TODAY, tokyo))
                .as("not a currency code").isNull();
        assertThat(MailScanService.validate(json(base.formatted("2026-08-01", "155", "JPY", "")), CARD_EMAIL, TODAY, tokyo))
                .as("older than 60 days").isNull();
        assertThat(MailScanService.validate(json(base.formatted("2026-10-20", "155", "JPY", "")), CARD_EMAIL, TODAY, tokyo))
                .as("in the future").isNull();
        assertThat(MailScanService.validate(json(base.formatted("2026-10-15", "155", "JPY", ",\"direction\":\"transfer\"")),
                CARD_EMAIL, TODAY, tokyo)).as("unknown direction").isNull();
        assertThat(MailScanService.validate(json(base.formatted("2026-10-15", "0", "JPY", "")), CARD_EMAIL, TODAY, tokyo))
                .as("zero").isNull();
    }

    @Test
    void aForeignPurchaseKeepsTheAmountTakenFromTheAccountAndMovesToTheUsersDay() {
        var email = new MailScanAgentRunner.MailInput("gmail-card-02", "Card <alerts@example-card.test>",
                "2026-10-15T23:30:00-04:00", "Your card was charged", """
                A purchase of $20.50 at CORNER CAFE NYC on Oct 15, 2026 at 9:10 PM EDT.
                Amount debited: ¥3,120 (JPY).""");

        var result = MailScanService.validate(json("""
                {"type":"payment","title":"CORNER CAFE NYC","day":"2026-10-15","time":"21:10",
                 "time_zone":"America/New_York","amount":20.5,"currency":"usd","charged_amount":3120,
                 "charged_currency":"JPY","confidence":0.9,"evidence":"A purchase of $20.50 at CORNER CAFE NYC",
                 "gmail_message_id":"gmail-card-02"}
                """), email, TODAY, ZoneId.of("Asia/Tokyo"));

        assertThat(result).isNotNull();
        assertThat(result.payload()).containsEntry("amount", "20.5").containsEntry("currency", "USD")
                .containsEntry("charged_amount", "3120").containsEntry("charged_currency", "JPY")
                .containsEntry("day", "2026-10-16").containsEntry("time", "10:10");   // 21:10 EDT is 10:10 next day in Tokyo
    }

    @Test
    void anAmountMustAppearAsANumberOfItsOwn() {
        assertThat(MailScanService.mentions("お支払い ¥1,550 です", new java.math.BigDecimal("1550"))).isTrue();
        assertThat(MailScanService.mentions("total $20.50", new java.math.BigDecimal("20.5"))).isTrue();
        assertThat(MailScanService.mentions("利用金額　－１５５円", new java.math.BigDecimal("155"))).isTrue();
        assertThat(MailScanService.mentions("ref 15500", new java.math.BigDecimal("1550"))).isFalse();
        assertThat(MailScanService.mentions("rate 1.55", new java.math.BigDecimal("155"))).isFalse();
        assertThat(MailScanService.mentions("approval 304395", new java.math.BigDecimal("155"))).isFalse();
    }

    @Test
    void cardNoticesWithAnUnsubscribeHeaderStillReachTheScannerButNewslettersDoNot() {
        String headers = "{\"labelIds\":[\"INBOX\"],\"payload\":{\"headers\":[{\"name\":\"List-Unsubscribe\",\"value\":\"<mailto:x@y.test>\"}]}}";
        var notice = new GmailMessageParser.Message("m", "t", "Bank <info@example-bank.test>", null, null, null,
                "【ゆうちょデビット】ご利用のお知らせ", null, null, null, List.of());
        var receipt = new GmailMessageParser.Message("m", "t", "Shop <no-reply@shop.test>", null, null, null,
                "Your payment receipt", null, null, null, List.of());
        var newsletter = new GmailMessageParser.Message("m", "t", "News <news@shop.test>", null, null, null,
                "Autumn sale starts now", null, null, null, List.of());

        assertThat(MailScanService.prefiltered(json(headers), notice)).isFalse();
        assertThat(MailScanService.prefiltered(json(headers), receipt)).isFalse();
        var paypay = new GmailMessageParser.Message("m", "t", "PayPay Card <info@example-card.test>", null, null, null,
                "PayPay Card (Visa) Usage Report", null, null, null, List.of());
        assertThat(MailScanService.prefiltered(json(headers), paypay)).isFalse();
        assertThat(MailScanService.prefiltered(json(headers), newsletter)).isTrue();
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

        var service = new MailScanService(gmail, scanner, repository,
                org.mockito.Mockito.mock(MailPaymentService.class), zone,
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
