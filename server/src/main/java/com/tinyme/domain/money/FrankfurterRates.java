package com.tinyme.domain.money;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;

/** European Central Bank reference rates through frankfurter.dev: free, no key, about 30 currencies. */
@Component
public class FrankfurterRates implements RateProvider {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final HttpClient http;
    private final String baseUrl;

    @Autowired
    public FrankfurterRates(@Value("${tinyme.money.rates-url:https://api.frankfurter.dev/v1}") String baseUrl) {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(), baseUrl);
    }

    FrankfurterRates(HttpClient http, String baseUrl) {
        this.http = http;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    @Override
    public Quote rate(LocalDate day, String base, String quote) throws IOException, InterruptedException {
        var request = HttpRequest.newBuilder(URI.create(baseUrl + "/" + day + "?base=" + base + "&symbols=" + quote))
                .timeout(Duration.ofSeconds(15)).GET().build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 404 || response.statusCode() == 422) {
            throw new UnsupportedPair("No exchange rate for " + base + " to " + quote);
        }
        if (response.statusCode() != 200) throw new IOException("Exchange rate service returned HTTP " + response.statusCode());
        JsonNode body = JSON.readTree(response.body());
        JsonNode rate = body.path("rates").get(quote);
        if (rate == null || !rate.isNumber() || rate.decimalValue().signum() <= 0) {
            throw new UnsupportedPair("No exchange rate for " + base + " to " + quote);
        }
        String rateDay = body.path("date").asString("");
        return new Quote(rate.decimalValue(), rateDay.isEmpty() ? day : LocalDate.parse(rateDay));
    }

    @Override
    public String name() {
        return "ecb-frankfurter";
    }
}
