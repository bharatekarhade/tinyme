package com.tinyme.api.money;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.tinyme.settings.UserCurrency;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** The currency spending is kept in. The app sends the phone region's currency the first time, then the owner's choice. */
@RestController
@RequestMapping("/settings/currency")
public class MoneySettingsController {
    private final UserCurrency currency;

    public MoneySettingsController(UserCurrency currency) {
        this.currency = currency;
    }

    @GetMapping
    public CurrencyResponse get() {
        var configured = currency.configured();
        return new CurrencyResponse(configured.orElse(currency.current()), configured.isPresent());
    }

    @PutMapping
    public CurrencyResponse set(@RequestBody CurrencyRequest request) {
        try {
            return new CurrencyResponse(currency.set(request == null ? null : request.currency()), true);
        } catch (IllegalArgumentException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, invalid.getMessage(), invalid);
        }
    }

    public record CurrencyRequest(String currency) { }

    /** {@code chosen} is false while the product default applies, so the app knows to send the phone's. */
    public record CurrencyResponse(String currency, @JsonProperty("chosen") boolean chosen) { }
}
