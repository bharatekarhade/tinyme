package com.tinyme.integrations.google.service;

import com.tinyme.agent.support.AgentDatabaseConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "tinyme.agent.setup-enabled=false",
        "TINYME_OWNER_EMAIL=owner@test.tinyme.local",
        "TINYME_OWNER_PASSWORD=test-only-owner-password"
})
@Import(AgentDatabaseConfiguration.class)
class MailScanRepositoryTests {
    @Autowired MailScanRepository repository;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM suggestions");
        jdbc.update("DELETE FROM scanned_messages");
        jdbc.update("DELETE FROM mail_scan_state");
        jdbc.update("DELETE FROM mail_scan_runs");
    }

    @Test
    void storesOnlyTheSmallSuggestionAndNeverAnEmailBody() {
        var candidate = new MailScanRepository.Candidate("event",
                Map.of("title", "Flight to Osaka", "day", "2026-10-24", "time", "09:30"),
                0.98, "Flight to Osaka is confirmed for October 24.");

        assertThat(repository.saveSuggestion("message-123", candidate)).isTrue();
        String stored = jdbc.queryForObject("SELECT payload::text || evidence FROM suggestions", String.class);
        assertThat(stored).contains("Flight to Osaka", "2026-10-24", "October 24")
                .doesNotContain("private full email body");
        assertThat(jdbc.queryForList("SELECT column_name FROM information_schema.columns "
                + "WHERE table_name='suggestions'", String.class)).doesNotContain("body", "subject", "from_address");
    }

    private static MailScanRepository.Candidate payment(String amount, boolean provisional, String direction) {
        var payload = new java.util.LinkedHashMap<String, Object>();
        payload.put("title", "ORACLE CORPORATION JAPAN");
        payload.put("merchant", "ORACLE CORPORATION JAPAN");
        payload.put("day", "2026-08-01");
        payload.put("amount", amount);
        payload.put("currency", "JPY");
        payload.put("direction", direction);
        payload.put("provisional", provisional);
        payload.put("card", "ゆうちょデビット");
        payload.put("reference", "304395");
        return new MailScanRepository.Candidate("payment", payload, 0.95, "利用金額　-" + amount + "円");
    }

    @Test
    void aFollowUpEmailAboutTheSameChargeUpdatesThePaymentInsteadOfAddingOne() {
        assertThat(repository.savePayment("mail-1", payment("155", true, "purchase")).outcome())
                .isEqualTo(MailScanRepository.PaymentSave.SAVED);
        assertThat(repository.savePayment("mail-2", payment("160", false, "purchase")).outcome())
                .isEqualTo(MailScanRepository.PaymentSave.UPDATED);
        assertThat(repository.savePayment("mail-3", payment("160", false, "refund")).outcome())
                .as("a refund of that charge is its own item").isEqualTo(MailScanRepository.PaymentSave.SAVED);

        assertThat(jdbc.queryForList("SELECT payload ->> 'amount' FROM suggestions WHERE payload ->> 'direction' = 'purchase'",
                String.class)).containsExactly("160");
        assertThat(jdbc.queryForObject("SELECT gmail_message_id FROM suggestions WHERE payload ->> 'direction' = 'purchase'",
                String.class)).isEqualTo("mail-2");

        jdbc.update("UPDATE suggestions SET status = 'dismissed' WHERE payload ->> 'direction' = 'purchase'");
        assertThat(repository.savePayment("mail-4", payment("170", false, "purchase")).outcome())
                .as("a dismissed payment stays dismissed").isEqualTo(MailScanRepository.PaymentSave.DUPLICATE);
    }

    @Test
    void paymentsNeverExpireAndStayOutOfThePlanSuggestionList() {
        repository.savePayment("mail-1", payment("155", false, "purchase"));
        repository.saveSuggestion("mail-9", new MailScanRepository.Candidate("event",
                Map.of("title", "Dinner", "day", "2026-10-20"), 0.9, "Dinner on the 20th"));

        repository.expireSuggestions(java.time.LocalDate.of(2026, 10, 16));

        assertThat(jdbc.queryForObject("SELECT status FROM suggestions WHERE type = 'payment'", String.class)).isEqualTo("new");
        assertThat(repository.listNew()).extracting(MailScanRepository.Suggestion::type).containsExactly("event");
    }

    @Test
    void initialScanPageAndHistoryCursorPersistAcrossRuns() {
        repository.startInitialScan("12345");
        repository.saveInitialPage("page-2", false);
        assertThat(repository.state()).contains(new MailScanRepository.ScanState("12345", "page-2", false,
                null, List.of(), null));

        repository.saveInitialPage(null, true);
        assertThat(repository.state()).contains(new MailScanRepository.ScanState("12345", null, true,
                null, List.of(), null));
        repository.saveHistoryId("67890");
        assertThat(repository.state()).contains(new MailScanRepository.ScanState("67890", null, true,
                null, List.of(), null));
        repository.saveHistoryProgress(List.of("message-3", "message-4"), "history-page-2", "70000");
        assertThat(repository.state()).contains(new MailScanRepository.ScanState("67890", null, true,
                "history-page-2", List.of("message-3", "message-4"), "70000"));
    }

    @Test
    void extractionFailuresBecomeTerminalAfterTwoAttempts() {
        assertThat(repository.markExtractionFailure("failed-message")).isFalse();
        assertThat(repository.scanned("failed-message")).isFalse();
        assertThat(repository.markExtractionFailure("failed-message")).isTrue();
        assertThat(repository.scanned("failed-message")).isTrue();
        assertThat(jdbc.queryForObject("SELECT attempt_count FROM scanned_messages WHERE gmail_message_id='failed-message'",
                Integer.class)).isEqualTo(2);
    }

    @Test
    void expiresOldNewSuggestionsButKeepsTodayAndFuture() {
        repository.saveSuggestion("past", new MailScanRepository.Candidate("event",
                Map.of("title", "Old flight", "day", "2026-10-15"), 0.9, "Old flight"));
        repository.saveSuggestion("today", new MailScanRepository.Candidate("event",
                Map.of("title", "Today's event", "day", "2026-10-16"), 0.9, "Today's event"));

        repository.expireSuggestions(java.time.LocalDate.of(2026, 10, 16));

        assertThat(jdbc.queryForObject("SELECT status FROM suggestions WHERE gmail_message_id='past'", String.class))
                .isEqualTo("expired");
        assertThat(jdbc.queryForObject("SELECT status FROM suggestions WHERE gmail_message_id='today'", String.class))
                .isEqualTo("new");
    }

    @Test
    void prunesScanHistoryAfterNinetyDaysAndRunRowsAfterThirty() {
        jdbc.update("INSERT INTO scanned_messages (gmail_message_id, scanned_at, outcome) "
                + "VALUES ('old-message', statement_timestamp() - interval '91 days', 'skipped')");
        jdbc.update("INSERT INTO mail_scan_runs (status, started_at) "
                + "VALUES ('succeeded', statement_timestamp() - interval '31 days')");

        repository.startRun();

        assertThat(jdbc.queryForObject("SELECT count(*) FROM scanned_messages WHERE gmail_message_id='old-message'",
                Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM mail_scan_runs "
                + "WHERE started_at < statement_timestamp() - interval '30 days'", Integer.class)).isZero();
    }
}
