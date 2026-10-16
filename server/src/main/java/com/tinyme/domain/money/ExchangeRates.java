package com.tinyme.domain.money;

import com.tinyme.settings.UserCurrency;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.Objects;
import java.util.Optional;

/**
 * Converts amounts between currencies at a day's rate. Each (day, from, to) rate is fetched once and kept, so a
 * payment converted today shows the same number next year.
 */
@Service
public class ExchangeRates {
    private final JdbcTemplate jdbc;
    private final RateProvider provider;
    private final Clock clock;

    public ExchangeRates(JdbcTemplate jdbc, RateProvider provider, Clock clock) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.provider = Objects.requireNonNull(provider, "provider");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** {@code amount} in {@code from} on {@code day}, in {@code to}, rounded to that currency's usual decimals. */
    public Conversion convert(BigDecimal amount, String from, String to, LocalDate day)
            throws IOException, InterruptedException {
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(day, "day");
        String base = UserCurrency.normalize(from);
        String quote = UserCurrency.normalize(to);
        if (base.equals(quote)) {
            return new Conversion(round(amount, quote), quote, amount, base, BigDecimal.ONE, day, "same");
        }
        LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        LocalDate asked = day.isAfter(today) ? today : day;
        RateProvider.Quote rate = cached(asked, base, quote).orElse(null);
        String source;
        if (rate != null) {
            source = cachedSource(asked, base, quote);
        } else {
            rate = provider.rate(asked, base, quote);
            source = provider.name();
            // Today's rate may still be yesterday's until the day's fix is published; only keep settled ones.
            if (asked.isBefore(today) || rate.rateDay().equals(asked)) save(asked, base, quote, rate, source);
        }
        return new Conversion(round(amount.multiply(rate.rate()), quote), quote, amount, base, rate.rate(),
                rate.rateDay(), source);
    }

    /** Rounds to a currency's usual decimals: yen none, dollars two. */
    public static BigDecimal round(BigDecimal amount, String currency) {
        int digits = Currency.getInstance(currency).getDefaultFractionDigits();
        return amount.setScale(digits < 0 ? 2 : digits, RoundingMode.HALF_UP);
    }

    private Optional<RateProvider.Quote> cached(LocalDate day, String base, String quote) {
        return jdbc.query("SELECT rate, rate_day FROM exchange_rates WHERE day = ? AND base = ? AND quote = ?",
                result -> result.next()
                        ? Optional.of(new RateProvider.Quote(result.getBigDecimal(1), result.getObject(2, LocalDate.class)))
                        : Optional.empty(),
                day, base, quote);
    }

    private String cachedSource(LocalDate day, String base, String quote) {
        return jdbc.queryForObject("SELECT source FROM exchange_rates WHERE day = ? AND base = ? AND quote = ?",
                String.class, day, base, quote);
    }

    private void save(LocalDate day, String base, String quote, RateProvider.Quote rate, String source) {
        jdbc.update("INSERT INTO exchange_rates (day, base, quote, rate, rate_day, source) VALUES (?, ?, ?, ?, ?, ?) "
                + "ON CONFLICT (day, base, quote) DO NOTHING", day, base, quote, rate.rate(), rate.rateDay(), source);
    }

    /**
     * The converted {@code amount} in {@code currency}, from {@code originalAmount} in {@code originalCurrency} at
     * {@code rate} (the market rate of {@code rateDay}, from {@code source}).
     */
    public record Conversion(BigDecimal amount, String currency, BigDecimal originalAmount, String originalCurrency,
                             BigDecimal rate, LocalDate rateDay, String source) { }
}
