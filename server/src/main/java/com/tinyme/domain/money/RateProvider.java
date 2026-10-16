package com.tinyme.domain.money;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;

/** Where daily exchange rates come from. */
public interface RateProvider {
    /** How many {@code quote} one {@code base} was worth on {@code day}, or the latest market day before it. */
    Quote rate(LocalDate day, String base, String quote) throws IOException, InterruptedException;

    String name();

    record Quote(BigDecimal rate, LocalDate rateDay) { }

    /** The provider has no rate for this pair (an unsupported currency). */
    final class UnsupportedPair extends IOException {
        public UnsupportedPair(String message) { super(message); }
    }
}
