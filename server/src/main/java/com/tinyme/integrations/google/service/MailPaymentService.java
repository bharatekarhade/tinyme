package com.tinyme.integrations.google.service;

import com.tinyme.domain.entries.model.add.AddCommand;
import com.tinyme.domain.entries.model.update.EntryPatch;
import com.tinyme.domain.entries.repository.EntryRepository;
import com.tinyme.domain.entries.service.EntryService;
import com.tinyme.domain.money.ExchangeRates;
import com.tinyme.settings.UserCurrency;
import com.tinyme.settings.UserZone;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Card payments the mail scan found. Nothing counts as spending until the owner confirms it; confirming turns it
 * into an `expense` entry in their currency, or attaches it to the same expense they already logged in chat.
 */
@Service
public class MailPaymentService {
    private static final Logger log = LoggerFactory.getLogger(MailPaymentService.class);
    static final String SOURCE = "gmail";
    /** How close a chat-logged amount must be to count as the same purchase: people round ("about ¥1,200"). */
    private static final BigDecimal MATCH_TOLERANCE = new BigDecimal("0.10");

    private final MailScanRepository payments;
    private final EntryService entries;
    private final EntryRepository entryRows;
    private final ExchangeRates rates;
    private final UserCurrency currency;
    private final UserZone zone;
    private final JdbcTemplate jdbc;

    public MailPaymentService(MailScanRepository payments, EntryService entries, EntryRepository entryRows,
                              ExchangeRates rates, UserCurrency currency, UserZone zone, JdbcTemplate jdbc) {
        this.payments = payments;
        this.entries = entries;
        this.entryRows = entryRows;
        this.rates = rates;
        this.currency = currency;
        this.zone = zone;
        this.jdbc = jdbc;
    }

    /** Payments waiting for the owner, each with its amount in their currency, a category guess and likely matches. */
    public List<Pending> pending() {
        String home = currency.current();
        List<Pending> result = new ArrayList<>();
        for (var payment : payments.listNewPayments()) {
            Money money = null;
            try {
                money = inHomeCurrency(payment, home);
            } catch (IOException | InterruptedException failure) {
                if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
                log.warn("No exchange rate for a payment yet ({})", failure.getClass().getSimpleName());
            }
            BigDecimal signed = money == null ? null : signed(payment, money.amount());
            result.add(new Pending(payment, money, guessCategory(payment.merchant()),
                    signed == null ? List.of() : matches(payment.day(), signed)));
        }
        return result;
    }

    /**
     * Confirms a payment. {@code amount} (in the owner's currency) and {@code category} override what was read and
     * guessed; {@code linkEntryId} attaches it to an expense already logged in chat instead of adding a second one.
     */
    @Transactional
    public Optional<Confirmed> confirm(UUID id, BigDecimal amount, String category, UUID linkEntryId)
            throws IOException, InterruptedException {
        var payment = payments.lockPayment(id).filter(found -> "new".equals(found.status())).orElse(null);
        if (payment == null) return Optional.empty();
        String home = currency.current();
        Money money = amount == null ? inHomeCurrency(payment, home) : Money.entered(amount, home, payment);
        if (money.amount().signum() <= 0) throw new IllegalArgumentException("amount must be more than zero");
        String chosen = cleanCategory(category);
        UUID entryId = linkEntryId == null
                ? addEntry(payment, money, chosen != null ? chosen : guessCategory(payment.merchant()))
                : linkEntry(payment, money, chosen, linkEntryId);
        if (!payments.acceptPayment(id, entryId)) throw new IllegalStateException("Payment was already handled");
        return Optional.of(new Confirmed(id, entryId, signed(payment, money.amount()), money.currency()));
    }

    @Transactional
    public boolean dismiss(UUID id) {
        return payments.lockPayment(id).filter(found -> "new".equals(found.status())).isPresent() && payments.dismiss(id);
    }

    /**
     * A later email about a payment that was already confirmed (the bank's final amount): the payment takes the new
     * details and its entry the new amount. Skipped if the owner deleted the entry or set the amount themselves.
     */
    @Transactional
    public void followUp(UUID paymentId, String messageId, MailScanRepository.Candidate candidate) {
        var before = payments.lockPayment(paymentId).orElse(null);
        if (before == null || before.entryId() == null) return;
        payments.updatePayment(paymentId, messageId, candidate);
        var after = payments.lockPayment(paymentId).orElseThrow();
        var entry = entryRows.findLive(before.entryId()).orElse(null);
        if (entry == null || Boolean.TRUE.equals(entry.data().get("amount_entered"))) return;
        try {
            Money money = inHomeCurrency(after, currency.current());
            Map<String, Object> data = new LinkedHashMap<>(entry.data());
            data.put("amount", signed(after, money.amount()));
            putOriginal(data, after, money);
            data.put("provisional", after.provisional());
            entries.update(new EntryPatch(entry.id(), null, null, null, data, null), zone.current());
        } catch (IOException | InterruptedException failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            log.warn("Could not apply a payment's final amount ({})", failure.getClass().getSimpleName());
        }
    }

    private UUID addEntry(MailScanRepository.Payment payment, Money money, String category) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("amount", signed(payment, money.amount()));
        data.put("currency", money.currency());
        if (category != null) data.put("category", category);
        data.put("merchant", payment.merchant());
        describe(data, payment, money);
        ZoneId userZone = zone.current();
        String text = payment.refund() ? "Refund from " + payment.merchant() : payment.merchant();
        return entries.add(new AddCommand("expense", BigDecimal.ONE, text, data, List.of(),
                chargedAt(payment, userZone), userZone, SOURCE)).id();
    }

    private UUID linkEntry(MailScanRepository.Payment payment, Money money, String category, UUID entryId) {
        var entry = entryRows.findLive(entryId)
                .filter(found -> "expense".equals(found.kind()))
                .filter(found -> found.data().get("payment_id") == null)
                .orElseThrow(() -> new IllegalArgumentException("That expense can't be linked"));
        Map<String, Object> data = new LinkedHashMap<>(entry.data());
        // The bank's figure is the true one; what the owner said in chat keeps its words and category.
        data.put("amount", signed(payment, money.amount()));
        data.put("currency", money.currency());
        // A category chosen now wins; otherwise the one said in chat stays, and only a missing one is guessed.
        if (category != null) data.put("category", category);
        else if (data.get("category") == null) {
            String guess = guessCategory(payment.merchant());
            if (guess != null) data.put("category", guess);
        }
        data.putIfAbsent("merchant", payment.merchant());
        describe(data, payment, money);
        entries.update(new EntryPatch(entryId, null, null, null, data, null), zone.current());
        return entryId;
    }

    private static void describe(Map<String, Object> data, MailScanRepository.Payment payment, Money money) {
        if (payment.card() != null) data.put("card", payment.card());
        if (payment.reference() != null) data.put("reference", payment.reference());
        data.put("payment_id", payment.id().toString());
        data.put("gmail_message_id", payment.gmailMessageId());
        if (payment.provisional()) data.put("provisional", true);
        if (money.entered()) data.put("amount_entered", true);
        putOriginal(data, payment, money);
    }

    private static void putOriginal(Map<String, Object> data, MailScanRepository.Payment payment, Money money) {
        if (money.originalCurrency().equals(money.currency())) {
            data.remove("original_amount");
            data.remove("original_currency");
            data.remove("rate");
            return;
        }
        data.put("original_amount", payment.refund() ? money.originalAmount().negate() : money.originalAmount());
        data.put("original_currency", money.originalCurrency());
        if (money.rate() != null) data.put("rate", money.rate());
    }

    /**
     * The amount in the owner's currency: the email's own figure when it is in that currency (or the card states what
     * it took from the account in it), otherwise converted at the day's rate.
     */
    Money inHomeCurrency(MailScanRepository.Payment payment, String home) throws IOException, InterruptedException {
        if (payment.currency().equals(home)) {
            return Money.exact(ExchangeRates.round(payment.amount(), home), home, payment);
        }
        if (home.equals(payment.chargedCurrency()) && payment.chargedAmount() != null) {
            return new Money(ExchangeRates.round(payment.chargedAmount(), home), home, payment.amount(),
                    payment.currency(), null, "card", false);
        }
        var conversion = rates.convert(payment.amount(), payment.currency(), home, payment.day());
        return new Money(conversion.amount(), home, payment.amount(), payment.currency(), conversion.rate(),
                conversion.source(), false);
    }

    private static BigDecimal signed(MailScanRepository.Payment payment, BigDecimal amount) {
        return payment.refund() ? amount.negate() : amount;
    }

    private static Instant chargedAt(MailScanRepository.Payment payment, ZoneId zone) {
        LocalTime time = payment.time() == null ? LocalTime.NOON : LocalTime.parse(payment.time());
        return payment.day().atTime(time).atZone(zone).toInstant();
    }

    /** The category last used for this shop, so "LAWSON" learns from the owner's corrections. */
    String guessCategory(String merchant) {
        if (merchant == null || merchant.isBlank()) return null;
        return jdbc.query("""
                SELECT data ->> 'category' FROM entries
                WHERE deleted_at IS NULL AND kind = 'expense' AND data ->> 'category' IS NOT NULL
                  AND lower(data ->> 'merchant') = lower(?)
                ORDER BY ts DESC LIMIT 1
                """, result -> result.next() ? result.getString(1) : null, merchant.strip());
    }

    /** Expenses logged by hand around that day for about the same amount, not yet tied to a payment. */
    List<Match> matches(LocalDate day, BigDecimal amount) {
        BigDecimal tolerance = amount.abs().multiply(MATCH_TOLERANCE).max(BigDecimal.ONE);
        return jdbc.query("""
                SELECT id, text, (data ->> 'amount')::numeric, ts FROM entries
                WHERE deleted_at IS NULL AND kind = 'expense' AND source <> 'gmail'
                  AND data ->> 'payment_id' IS NULL AND jsonb_typeof(data -> 'amount') = 'number'
                  AND local_day BETWEEN ? AND ?
                  AND abs((data ->> 'amount')::numeric - ?) <= ?
                ORDER BY abs((data ->> 'amount')::numeric - ?), ts DESC LIMIT 3
                """, (row, n) -> new Match(row.getObject(1, UUID.class), row.getString(2), row.getBigDecimal(3),
                        row.getTimestamp(4).toInstant()),
                day.minusDays(1), day.plusDays(1), amount, tolerance, amount);
    }

    private static String cleanCategory(String category) {
        if (category == null || category.isBlank()) return null;
        String clean = category.strip().toLowerCase(Locale.ROOT);
        if (clean.length() > 40) throw new IllegalArgumentException("category must be at most 40 characters");
        return clean;
    }

    /**
     * An amount in the owner's currency and where it came from. {@code rate} is null when no conversion was needed
     * or the card stated the figure; {@code entered} when the owner typed it.
     */
    public record Money(BigDecimal amount, String currency, BigDecimal originalAmount, String originalCurrency,
                        BigDecimal rate, String source, boolean entered) {
        static Money exact(BigDecimal amount, String currency, MailScanRepository.Payment payment) {
            return new Money(amount, currency, payment.amount(), payment.currency(), null, "email", false);
        }

        static Money entered(BigDecimal amount, String home, MailScanRepository.Payment payment) {
            return new Money(ExchangeRates.round(amount.abs(), home), home, payment.amount(),
                    payment.currency(), null, "entered", true);
        }
    }

    public record Pending(MailScanRepository.Payment payment, Money home, String categoryGuess, List<Match> matches) { }

    public record Match(UUID entryId, String text, BigDecimal amount, Instant ts) { }

    public record Confirmed(UUID paymentId, UUID entryId, BigDecimal amount, String currency) { }

}
