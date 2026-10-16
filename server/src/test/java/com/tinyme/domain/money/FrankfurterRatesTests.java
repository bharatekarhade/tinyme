package com.tinyme.domain.money;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FrankfurterRatesTests {
    private HttpServer server;
    private final AtomicReference<String> asked = new AtomicReference<>();
    private volatile int status = 200;
    private volatile String body = "";

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1", exchange -> {
            asked.set(exchange.getRequestURI().toString());
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() { server.stop(0); }

    private FrankfurterRates rates() {
        return new FrankfurterRates(HttpClient.newHttpClient(), "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/");
    }

    @Test
    void readsTheRateAndTheMarketDayItCameFrom() throws Exception {
        body = """
                {"amount":1.0,"base":"USD","date":"2026-10-16","rates":{"JPY":151.23}}
                """;

        var quote = rates().rate(LocalDate.of(2026, 10, 18), "USD", "JPY");

        assertThat(asked.get()).isEqualTo("/v1/2026-10-18?base=USD&symbols=JPY");
        assertThat(quote.rate()).isEqualByComparingTo("151.23");
        assertThat(quote.rateDay()).isEqualTo(LocalDate.of(2026, 10, 16));   // a Sunday falls back to Friday
    }

    @Test
    void aCurrencyTheServiceDoesNotKnowIsReportedAsUnsupported() {
        status = 404;
        body = "{\"message\":\"not found\"}";

        assertThatThrownBy(() -> rates().rate(LocalDate.of(2026, 10, 16), "USD", "XOF"))
                .isInstanceOf(RateProvider.UnsupportedPair.class);
    }
}
