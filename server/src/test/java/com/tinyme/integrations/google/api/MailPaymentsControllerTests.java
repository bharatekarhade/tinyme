package com.tinyme.integrations.google.api;

import com.tinyme.integrations.google.service.MailPaymentService;
import com.tinyme.integrations.google.service.MailScanScheduler;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MailPaymentsControllerTests {
    private final MailPaymentService payments = mock(MailPaymentService.class);
    private final MailPaymentsController controller = new MailPaymentsController(payments, mock(MailScanScheduler.class));
    private final UUID id = UUID.randomUUID();

    private static HttpStatus status(Throwable error) {
        return HttpStatus.valueOf(((ResponseStatusException) error).getStatusCode().value());
    }

    @Test
    void confirmsWithoutABodyAndAnswersWhatItBecame() throws Exception {
        UUID entry = UUID.randomUUID();
        when(payments.confirm(id, null, null, null)).thenReturn(Optional.of(
                new MailPaymentService.Confirmed(id, entry, new BigDecimal("155"), "JPY")));

        assertThat(controller.confirm(id, null))
                .isEqualTo(new MailPaymentsController.ConfirmedResponse(id, entry, new BigDecimal("155"), "JPY"));
    }

    @Test
    void explainsAMissingPaymentABadEditAndAMissingRate() throws Exception {
        when(payments.confirm(id, null, null, null)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> controller.confirm(id, null))
                .satisfies(error -> assertThat(status(error)).isEqualTo(HttpStatus.NOT_FOUND));

        var bad = new MailPaymentsController.ConfirmRequest(new BigDecimal("-5"), null, null);
        doThrow(new IllegalArgumentException("amount must be more than zero"))
                .when(payments).confirm(id, new BigDecimal("-5"), null, null);
        assertThatThrownBy(() -> controller.confirm(id, bad))
                .satisfies(error -> assertThat(status(error)).isEqualTo(HttpStatus.BAD_REQUEST));

        UUID foreign = UUID.randomUUID();
        doThrow(new IOException("rate service down")).when(payments).confirm(foreign, null, null, null);
        assertThatThrownBy(() -> controller.confirm(foreign, null))
                .satisfies(error -> assertThat(status(error)).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
    }
}
