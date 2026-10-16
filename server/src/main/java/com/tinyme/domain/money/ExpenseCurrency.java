package com.tinyme.domain.money;

import com.tinyme.settings.UserCurrency;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Spending said in another currency ("spent $20 on lunch") is kept in the owner's, like card payments: the amount is
 * converted at that day's rate and the original stays in original_amount / original_currency.
 */
@Component
public class ExpenseCurrency {
    private final ExchangeRates rates;
    private final UserCurrency currency;

    public ExpenseCurrency(ExchangeRates rates, UserCurrency currency) {
        this.rates = rates;
        this.currency = currency;
    }

    /**
     * {@code data} with its amount in the owner's currency. Only money is touched: an {@code amount} number with a
     * {@code currency} code. Already-converted data (currency is the owner's) comes back as it was.
     *
     * @throws IllegalArgumentException for an unknown currency, or when no rate can be had right now
     */
    public Map<String, Object> inOwnersCurrency(Map<String, Object> data, LocalDate day) {
        if (data == null || !(data.get("currency") instanceof String code) || !(data.get("amount") instanceof Number number)) {
            return data;
        }
        String from = UserCurrency.normalize(code);
        String home = currency.current();
        Map<String, Object> result = new LinkedHashMap<>(data);
        result.put("currency", from);
        if (from.equals(home)) return result;
        BigDecimal amount = new BigDecimal(number.toString());
        try {
            var conversion = rates.convert(amount.abs(), from, home, day);
            BigDecimal converted = amount.signum() < 0 ? conversion.amount().negate() : conversion.amount();
            result.put("amount", converted);
            result.put("currency", home);
            result.put("original_amount", amount);
            result.put("original_currency", from);
            result.put("rate", conversion.rate());
            return result;
        } catch (IOException | InterruptedException unavailable) {
            if (unavailable instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new IllegalArgumentException("Couldn't get an exchange rate for " + from + " right now; "
                    + "ask the user for the amount in " + home + " instead");
        }
    }
}
