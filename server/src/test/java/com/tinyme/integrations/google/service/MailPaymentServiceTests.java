package com.tinyme.integrations.google.service;

import com.tinyme.agent.support.AgentDatabaseConfiguration;
import com.tinyme.domain.money.RateProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "tinyme.agent.setup-enabled=false",
        "TINYME_OWNER_EMAIL=owner@test.tinyme.local",
        "TINYME_OWNER_PASSWORD=test-only-owner-password"
})
@Import({AgentDatabaseConfiguration.class, MailPaymentServiceTests.FixedRates.class})
class MailPaymentServiceTests {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired MailPaymentService payments;
    @Autowired MailScanRepository repository;
    @Autowired JdbcTemplate jdbc;

    @TestConfiguration
    static class FixedRates {
        /** A dollar is ¥150 every day; nothing goes to the network. */
        @Bean @Primary
        RateProvider fixedRates() {
            return new RateProvider() {
                @Override public Quote rate(LocalDate day, String base, String quote) {
                    return new Quote(new BigDecimal("150"), day);
                }
                @Override public String name() { return "fixed"; }
            };
        }
    }

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM suggestions");
        jdbc.update("DELETE FROM entries WHERE kind = 'expense'");
        jdbc.update("DELETE FROM exchange_rates");
        jdbc.update("DELETE FROM settings WHERE key IN ('user.currency', 'user.tz')");
    }

    private UUID payment(String messageId, String merchant, String amount, String currency, Map<String, Object> extra) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("title", merchant);
        payload.put("merchant", merchant);
        payload.put("day", "2026-10-15");
        payload.put("time", "13:57");
        payload.put("amount", amount);
        payload.put("currency", currency);
        payload.put("direction", "purchase");
        payload.put("provisional", false);
        payload.putAll(extra);
        return repository.savePayment(messageId,
                new MailScanRepository.Candidate("payment", payload, 0.95, "利用金額 " + amount)).paymentId();
    }

    private UUID chatExpense(String text, int amount, String category, String merchant, String ts) {
        var data = new LinkedHashMap<String, Object>(Map.of("amount", amount));
        if (category != null) data.put("category", category);
        if (merchant != null) data.put("merchant", merchant);
        return jdbc.queryForObject("""
                INSERT INTO entries (ts, local_day, kind, text, data, source)
                VALUES (?::timestamptz, (?::timestamptz AT TIME ZONE 'Asia/Tokyo')::date, 'expense', ?, ?::jsonb, 'chat')
                RETURNING id""", UUID.class, ts, ts, text, JSON.writeValueAsString(data));
    }

    private JsonNode entryData(UUID id) {
        return JSON.readTree(jdbc.queryForObject("SELECT data::text FROM entries WHERE id = ?", String.class, id));
    }

    @Test
    void aWaitingPaymentShowsItsAmountACategoryLearnedFromTheShopAndChatExpensesThatMayBeTheSame() {
        chatExpense("coffee", 600, "coffee", "Blue Bottle", "2026-10-01T01:00:00Z");
        UUID nearMatch = chatExpense("coffee at blue bottle", 590, null, null, "2026-10-15T05:30:00Z");
        chatExpense("dinner", 5000, "food", null, "2026-10-15T11:00:00Z");
        payment("mail-1", "BLUE BOTTLE", "620", "JPY", Map.of("card", "ゆうちょデビット", "reference", "1"));

        var pending = payments.pending();

        assertThat(pending).singleElement().satisfies(item -> {
            assertThat(item.home().amount()).isEqualByComparingTo("620");
            assertThat(item.home().source()).isEqualTo("email");
            assertThat(item.categoryGuess()).isEqualTo("coffee");     // learned from "Blue Bottle", any case
            assertThat(item.matches()).extracting(MailPaymentService.Match::entryId).containsExactly(nearMatch);
        });
    }

    @Test
    void confirmingAddsAnExpenseInTheOwnersCurrencyOnceAndKeepsWhereItCameFrom() throws Exception {
        UUID id = payment("mail-1", "ORACLE CORPORATION JAPAN", "155", "JPY",
                Map.of("card", "ゆうちょデビット", "reference", "304395", "provisional", true));

        var confirmed = payments.confirm(id, null, "Subscriptions", null).orElseThrow();

        var row = jdbc.queryForMap("SELECT kind, source, text, ts, local_day FROM entries WHERE id = ?", confirmed.entryId());
        assertThat(row).containsEntry("kind", "expense").containsEntry("source", "gmail")
                .containsEntry("text", "ORACLE CORPORATION JAPAN");
        assertThat(((java.sql.Timestamp) row.get("ts")).toInstant()).isEqualTo(Instant.parse("2026-10-15T04:57:00Z"));
        JsonNode data = entryData(confirmed.entryId());
        assertThat(data.path("amount").decimalValue()).isEqualByComparingTo("155");
        assertThat(data.path("currency").asString()).isEqualTo("JPY");
        assertThat(data.path("category").asString()).isEqualTo("subscriptions");
        assertThat(data.path("card").asString()).isEqualTo("ゆうちょデビット");
        assertThat(data.path("reference").asString()).isEqualTo("304395");
        assertThat(data.path("payment_id").asString()).isEqualTo(id.toString());
        assertThat(data.path("provisional").asBoolean()).isTrue();
        assertThat(data.has("original_currency")).isFalse();
        assertThat(jdbc.queryForObject("SELECT status FROM suggestions WHERE id = ?", String.class, id)).isEqualTo("accepted");

        assertThat(payments.confirm(id, null, null, null)).as("only once").isEmpty();
        assertThat(payments.pending()).isEmpty();
    }

    @Test
    void aForeignPurchaseIsConvertedAtTheDaysRateUnlessTheCardSaidWhatItTook() throws Exception {
        UUID converted = payment("mail-1", "CORNER CAFE NYC", "20.5", "USD", Map.of());
        UUID stated = payment("mail-2", "BOOKSHOP LONDON", "12", "USD",
                Map.of("charged_amount", "1777", "charged_currency", "JPY"));

        JsonNode first = entryData(payments.confirm(converted, null, null, null).orElseThrow().entryId());
        JsonNode second = entryData(payments.confirm(stated, null, null, null).orElseThrow().entryId());

        assertThat(first.path("amount").decimalValue()).isEqualByComparingTo("3075");   // 20.50 × 150
        assertThat(first.path("original_amount").decimalValue()).isEqualByComparingTo("20.5");
        assertThat(first.path("original_currency").asString()).isEqualTo("USD");
        assertThat(first.path("rate").decimalValue()).isEqualByComparingTo("150");
        assertThat(second.path("amount").decimalValue()).as("the card's own figure").isEqualByComparingTo("1777");
        assertThat(second.has("rate")).isFalse();
    }

    @Test
    void linkingToTheChatExpenseKeepsItsWordsAndCategoryButTakesTheBanksAmount() throws Exception {
        UUID chat = chatExpense("coffee at blue bottle", 590, "coffee", null, "2026-10-15T05:30:00Z");
        UUID id = payment("mail-1", "BLUE BOTTLE", "620", "JPY", Map.of("reference", "77"));

        var confirmed = payments.confirm(id, null, null, chat).orElseThrow();

        assertThat(confirmed.entryId()).isEqualTo(chat);
        JsonNode data = entryData(chat);
        assertThat(data.path("amount").decimalValue()).isEqualByComparingTo("620");
        assertThat(data.path("category").asString()).isEqualTo("coffee");
        assertThat(data.path("merchant").asString()).isEqualTo("BLUE BOTTLE");
        assertThat(data.path("payment_id").asString()).isEqualTo(id.toString());
        assertThat(jdbc.queryForObject("SELECT text FROM entries WHERE id = ?", String.class, chat))
                .isEqualTo("coffee at blue bottle");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM entries WHERE kind = 'expense'", Integer.class)).isEqualTo(1);

        UUID another = payment("mail-2", "BLUE BOTTLE", "640", "JPY", Map.of("reference", "78"));
        assertThatThrownBy(() -> payments.confirm(another, null, null, chat))
                .as("an expense takes one payment").isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aRefundCountsAgainstSpendingAndADismissedPaymentNeverCounts() throws Exception {
        UUID refund = payment("mail-1", "AMAZON.CO.JP", "2980", "JPY", Map.of("direction", "refund"));
        UUID dismissed = payment("mail-2", "ORACLE CORPORATION JAPAN", "155", "JPY", Map.of());

        var confirmed = payments.confirm(refund, null, null, null).orElseThrow();
        assertThat(payments.dismiss(dismissed)).isTrue();

        assertThat(confirmed.amount()).isEqualByComparingTo("-2980");
        assertThat(jdbc.queryForObject("SELECT text FROM entries WHERE id = ?", String.class, confirmed.entryId()))
                .isEqualTo("Refund from AMAZON.CO.JP");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM entries WHERE kind = 'expense'", Integer.class)).isEqualTo(1);
        assertThat(payments.dismiss(dismissed)).as("only once").isFalse();
    }

    @Test
    void theBanksFinalAmountUpdatesAConfirmedPaymentUnlessTheOwnerTypedTheirOwn() throws Exception {
        UUID automatic = payment("mail-1", "SHOP A", "155", "JPY", Map.of("reference", "1", "provisional", true));
        UUID typed = payment("mail-2", "SHOP B", "300", "JPY", Map.of("reference", "2", "provisional", true));
        UUID autoEntry = payments.confirm(automatic, null, null, null).orElseThrow().entryId();
        UUID typedEntry = payments.confirm(typed, new BigDecimal("280"), null, null).orElseThrow().entryId();

        for (var item : Map.of(automatic, "1", typed, "2").entrySet()) {
            var payload = new LinkedHashMap<String, Object>(Map.of("title", "SHOP", "merchant", "SHOP", "day", "2026-10-15",
                    "amount", "160", "currency", "JPY", "direction", "purchase", "provisional", false,
                    "reference", item.getValue()));
            payments.followUp(item.getKey(), "final-" + item.getValue(),
                    new MailScanRepository.Candidate("payment", payload, 0.95, "確定 160"));
        }

        assertThat(entryData(autoEntry).path("amount").decimalValue()).isEqualByComparingTo("160");
        assertThat(entryData(autoEntry).path("provisional").asBoolean()).isFalse();
        assertThat(entryData(typedEntry).path("amount").decimalValue()).isEqualByComparingTo("280");
        assertThat(entryData(typedEntry).path("amount_entered").asBoolean()).isTrue();
    }
}
