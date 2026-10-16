package com.tinyme.settings;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Currency;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * The currency spending is kept in. The app sets it from the phone's region the first time; the owner can change it.
 * Until then the product default applies, like the time zone's.
 */
@Component
public class UserCurrency {
    static final String KEY = "user.currency";
    private static final String DEFAULT_CURRENCY = "JPY";
    private final JdbcTemplate jdbc;

    public UserCurrency(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    public String current() {
        return configured().orElse(DEFAULT_CURRENCY);
    }

    /** The owner's choice, if one was saved. */
    public Optional<String> configured() {
        return jdbc.query("SELECT value #>> '{}' FROM settings WHERE key = ?",
                statement -> statement.setString(1, KEY),
                result -> result.next() ? Optional.ofNullable(result.getString(1)).map(UserCurrency::normalize)
                        : Optional.empty());
    }

    /** Saves an ISO 4217 code ("jpy" is fine); returns it upper-cased. */
    public String set(String code) {
        String currency = normalize(code);
        jdbc.update("INSERT INTO settings (key, value) VALUES (?, to_jsonb(?::text)) "
                + "ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value", KEY, currency);
        return currency;
    }

    /** "jpy" → "JPY"; anything that isn't an ISO 4217 code is refused. */
    public static String normalize(String code) {
        if (code == null || !code.strip().matches("(?i)[a-z]{3}")) {
            throw new IllegalArgumentException("currency must be a 3-letter ISO 4217 code");
        }
        String upper = code.strip().toUpperCase(Locale.ROOT);
        try {
            return Currency.getInstance(upper).getCurrencyCode();
        } catch (IllegalArgumentException unknown) {
            throw new IllegalArgumentException("Unknown currency " + upper);
        }
    }
}
