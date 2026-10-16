package com.tinyme.domain.money;

import com.tinyme.settings.UserCurrency;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ExpenseCurrencyTests {
    private static final LocalDate DAY = LocalDate.of(2026, 10, 15);
    private final ExchangeRates rates = mock(ExchangeRates.class);
    private final UserCurrency currency = mock(UserCurrency.class);
    private final ExpenseCurrency money = new ExpenseCurrency(rates, currency);

    {
        when(currency.current()).thenReturn("JPY");
    }

    @Test
    void anAmountWithoutACurrencyIsAlreadyTheOwnersAndIsLeftAlone() {
        var data = Map.<String, Object>of("amount", 710, "category", "coffee");

        assertThat(money.inOwnersCurrency(data, DAY)).isSameAs(data);
        verifyNoInteractions(rates);
    }

    @Test
    void theOwnersOwnCurrencyIsOnlyTidied() {
        assertThat(money.inOwnersCurrency(Map.of("amount", 710, "currency", "jpy"), DAY))
                .containsEntry("amount", 710).containsEntry("currency", "JPY").doesNotContainKey("original_amount");
        verifyNoInteractions(rates);
    }

    @Test
    void dollarsAreStoredInYenWithTheDollarsKept() throws Exception {
        when(rates.convert(new BigDecimal("20.5"), "USD", "JPY", DAY)).thenReturn(new ExchangeRates.Conversion(
                new BigDecimal("3075"), "JPY", new BigDecimal("20.5"), "USD", new BigDecimal("150"), DAY, "ecb"));

        var stored = money.inOwnersCurrency(Map.of("amount", 20.5, "currency", "USD", "category", "food"), DAY);

        assertThat(stored).containsEntry("amount", new BigDecimal("3075")).containsEntry("currency", "JPY")
                .containsEntry("original_amount", new BigDecimal("20.5")).containsEntry("original_currency", "USD")
                .containsEntry("rate", new BigDecimal("150")).containsEntry("category", "food");
    }

    @Test
    void aRefundStaysNegativeAfterConversion() throws Exception {
        when(rates.convert(new BigDecimal("12"), "EUR", "JPY", DAY)).thenReturn(new ExchangeRates.Conversion(
                new BigDecimal("1968"), "JPY", new BigDecimal("12"), "EUR", new BigDecimal("164"), DAY, "ecb"));

        assertThat(money.inOwnersCurrency(Map.of("amount", -12, "currency", "EUR"), DAY))
                .containsEntry("amount", new BigDecimal("-1968")).containsEntry("original_amount", new BigDecimal("-12"));
    }

    @Test
    void anUnknownCurrencyOrNoRateIsExplainedToTheAgent() throws Exception {
        assertThatThrownBy(() -> money.inOwnersCurrency(Map.of("amount", 5, "currency", "yen"), DAY))
                .isInstanceOf(IllegalArgumentException.class);
        when(rates.convert(BigDecimal.valueOf(5), "XOF", "JPY", DAY)).thenThrow(new IOException("no rate"));
        assertThatThrownBy(() -> money.inOwnersCurrency(Map.of("amount", 5, "currency", "XOF"), DAY))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ask the user for the amount in JPY");
    }
}
