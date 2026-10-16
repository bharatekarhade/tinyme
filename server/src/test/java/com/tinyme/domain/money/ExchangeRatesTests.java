package com.tinyme.domain.money;

import com.tinyme.agent.support.AgentDatabaseConfiguration;
import com.tinyme.settings.UserCurrency;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "tinyme.agent.setup-enabled=false",
        "TINYME_OWNER_EMAIL=owner@test.tinyme.local",
        "TINYME_OWNER_PASSWORD=test-only-owner-password"
})
@Import(AgentDatabaseConfiguration.class)
class ExchangeRatesTests {
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 16);
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-16T03:00:00Z"), ZoneOffset.UTC);

    @Autowired JdbcTemplate jdbc;
    @Autowired UserCurrency currency;

    private final FakeRates provider = new FakeRates();
    private ExchangeRates rates;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM exchange_rates");
        jdbc.update("DELETE FROM settings WHERE key = 'user.currency'");
        rates = new ExchangeRates(jdbc, provider, CLOCK);
    }

    @Test
    void theSameCurrencyIsKeptAsIsAndRoundedToItsUsualDecimals() throws Exception {
        var yen = rates.convert(new BigDecimal("155"), "jpy", "JPY", TODAY);

        assertThat(yen.amount()).isEqualByComparingTo("155");
        assertThat(yen.amount().scale()).isZero();
        assertThat(yen.rate()).isEqualByComparingTo("1");
        assertThat(provider.calls).isEmpty();
    }

    @Test
    void aForeignAmountIsConvertedAtThatDaysRateOnceAndThenFromTheCache() throws Exception {
        provider.next = new RateProvider.Quote(new BigDecimal("151.234"), LocalDate.of(2026, 10, 14));

        var first = rates.convert(new BigDecimal("20.00"), "USD", "JPY", LocalDate.of(2026, 10, 14));
        var again = rates.convert(new BigDecimal("1.50"), "USD", "JPY", LocalDate.of(2026, 10, 14));

        assertThat(first.amount()).isEqualByComparingTo("3025");          // 3024.68 → no yen decimals
        assertThat(first.originalAmount()).isEqualByComparingTo("20.00");
        assertThat(first.originalCurrency()).isEqualTo("USD");
        assertThat(first.source()).isEqualTo("fake");
        assertThat(again.amount()).isEqualByComparingTo("227");           // 226.85
        assertThat(provider.calls).containsExactly("2026-10-14 USD→JPY");
    }

    @Test
    void yenToDollarsKeepsCents() throws Exception {
        provider.next = new RateProvider.Quote(new BigDecimal("0.0066"), LocalDate.of(2026, 10, 15));

        var dollars = rates.convert(new BigDecimal("1550"), "JPY", "USD", LocalDate.of(2026, 10, 15));

        assertThat(dollars.amount()).isEqualByComparingTo("10.23");
        assertThat(dollars.amount().scale()).isEqualTo(2);
    }

    @Test
    void aDayInTheFutureUsesTodayAndAnUnsettledTodayRateIsNotKept() throws Exception {
        // Today's fix isn't out yet, so the provider answers with yesterday's rate.
        provider.next = new RateProvider.Quote(new BigDecimal("150"), LocalDate.of(2026, 10, 15));

        rates.convert(BigDecimal.TEN, "USD", "JPY", TODAY.plusDays(3));

        assertThat(provider.calls).containsExactly("2026-10-16 USD→JPY");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM exchange_rates", Integer.class)).isZero();
    }

    @Test
    void theOwnersCurrencyDefaultsToYenUntilChosenAndOnlyRealCodesAreAccepted() {
        assertThat(currency.configured()).isEmpty();
        assertThat(currency.current()).isEqualTo("JPY");

        assertThat(currency.set(" usd ")).isEqualTo("USD");
        assertThat(currency.current()).isEqualTo("USD");
        assertThat(currency.configured()).contains("USD");

        assertThatThrownBy(() -> currency.set("yen")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> currency.set("ZZZ")).isInstanceOf(IllegalArgumentException.class);
        assertThat(currency.current()).isEqualTo("USD");
    }

    private static final class FakeRates implements RateProvider {
        final List<String> calls = new ArrayList<>();
        Quote next;

        @Override
        public Quote rate(LocalDate day, String base, String quote) {
            calls.add(day + " " + base + "→" + quote);
            return next;
        }

        @Override
        public String name() { return "fake"; }
    }
}
