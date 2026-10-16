package com.tinyme.integrations.google.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.tinyme.integrations.google.service.MailPaymentService;
import com.tinyme.integrations.google.service.MailScanScheduler;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Card payments found in email, waiting for the owner. Only confirmed ones count as spending. */
@RestController
@RequestMapping("/integrations/google/payments")
public class MailPaymentsController {
    private final MailPaymentService payments;
    private final MailScanScheduler scans;

    public MailPaymentsController(MailPaymentService payments, MailScanScheduler scans) {
        this.payments = payments;
        this.scans = scans;
    }

    @GetMapping
    public List<PaymentResponse> list() {
        scans.requestNow();
        return payments.pending().stream().map(PaymentResponse::from).toList();
    }

    /** Body optional: amount (in the owner's currency) and category override; link_entry_id joins a chat expense. */
    @PostMapping("/{id}/confirm")
    public ConfirmedResponse confirm(@PathVariable UUID id, @RequestBody(required = false) ConfirmRequest request) {
        var body = request == null ? new ConfirmRequest(null, null, null) : request;
        try {
            return payments.confirm(id, body.amount(), body.category(), body.linkEntryId())
                    .map(done -> new ConfirmedResponse(done.paymentId(), done.entryId(), done.amount(), done.currency()))
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Payment not found"));
        } catch (IllegalArgumentException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, invalid.getMessage(), invalid);
        } catch (IOException | InterruptedException unavailable) {
            if (unavailable instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Couldn't get an exchange rate right now. Enter the amount yourself, or try again later.", unavailable);
        }
    }

    @PostMapping("/{id}/dismiss")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void dismiss(@PathVariable UUID id) {
        if (!payments.dismiss(id)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Payment not found");
    }

    public record ConfirmRequest(BigDecimal amount, String category,
                                 @JsonProperty("link_entry_id") UUID linkEntryId) { }

    public record ConfirmedResponse(@JsonProperty("payment_id") UUID paymentId, @JsonProperty("entry_id") UUID entryId,
                                    BigDecimal amount, String currency) { }

    /**
     * amount/currency are as the email states them; home is the same in the owner's currency (null while no rate is
     * available). Refunds are negative in home.
     */
    public record PaymentResponse(UUID id, String merchant, LocalDate day, String time, BigDecimal amount,
                                  String currency, String direction, boolean provisional, String card, String reference,
                                  Home home, @JsonProperty("category_guess") String categoryGuess,
                                  List<MatchResponse> matches, String evidence,
                                  @JsonProperty("gmail_message_id") String gmailMessageId,
                                  @JsonProperty("created_at") Instant createdAt) {
        static PaymentResponse from(MailPaymentService.Pending pending) {
            var p = pending.payment();
            var money = pending.home();
            Home home = money == null ? null : new Home(p.refund() ? money.amount().negate() : money.amount(),
                    money.currency(), money.rate(), money.source());
            return new PaymentResponse(p.id(), p.merchant(), p.day(), p.time(), p.amount(), p.currency(), p.direction(),
                    p.provisional(), p.card(), p.reference(), home, pending.categoryGuess(),
                    pending.matches().stream().map(m -> new MatchResponse(m.entryId(), m.text(), m.amount(), m.ts())).toList(),
                    p.evidence(), p.gmailMessageId(), p.createdAt());
        }
    }

    public record Home(BigDecimal amount, String currency, BigDecimal rate, String source) { }

    public record MatchResponse(@JsonProperty("entry_id") UUID entryId, String text, BigDecimal amount, Instant ts) { }
}
