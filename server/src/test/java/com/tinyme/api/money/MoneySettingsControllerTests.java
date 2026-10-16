package com.tinyme.api.money;

import com.tinyme.settings.UserCurrency;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MoneySettingsControllerTests {
    private final UserCurrency currency = mock(UserCurrency.class);
    private final MoneySettingsController controller = new MoneySettingsController(currency);

    @Test
    void reportsTheDefaultAsNotChosenSoTheAppSendsThePhonesCurrency() {
        when(currency.configured()).thenReturn(Optional.empty());
        when(currency.current()).thenReturn("JPY");

        assertThat(controller.get()).isEqualTo(new MoneySettingsController.CurrencyResponse("JPY", false));
    }

    @Test
    void savesAChoiceAndRefusesAnythingThatIsNotACurrencyCode() {
        when(currency.set("eur")).thenReturn("EUR");
        when(currency.set("yen")).thenThrow(new IllegalArgumentException("currency must be a 3-letter ISO 4217 code"));

        assertThat(controller.set(new MoneySettingsController.CurrencyRequest("eur")))
                .isEqualTo(new MoneySettingsController.CurrencyResponse("EUR", true));
        assertThatThrownBy(() -> controller.set(new MoneySettingsController.CurrencyRequest("yen")))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }
}
