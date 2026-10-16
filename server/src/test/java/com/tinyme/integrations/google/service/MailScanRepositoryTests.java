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
