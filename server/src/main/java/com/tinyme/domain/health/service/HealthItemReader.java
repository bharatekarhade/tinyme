package com.tinyme.domain.health.service;

import com.tinyme.domain.health.model.HealthItem;
import com.tinyme.domain.health.model.HealthKind;
import com.tinyme.domain.health.model.InvalidHealthItemException;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Map;

/**
 * Reads one raw item and checks it against its Health kind. Fields are read by hand, not bound by Jackson,
 * so a bad date in one item names that item instead of failing the whole batch.
 */
@Component
public class HealthItemReader {
    static final int MAX_EXTERNAL_ID = 200;
    static final int MAX_STRING = 200;
    /** A night's sleep stages are about 3 KB. */
    static final int MAX_ANY_JSON = 5_000;
    private static final LocalDate EARLIEST = LocalDate.of(2000, 1, 1);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() { };

    private final HealthKinds kinds;

    public HealthItemReader(HealthKinds kinds) {
        this.kinds = kinds;
    }

    public HealthItem read(int index, JsonNode node, ZoneId zone, Instant now) {
        if (node == null || !node.isObject()) throw invalid(index, "must be an object");
        String externalId = externalId(node.path("external_id"), InvalidHealthItemException.Part.ITEMS, index);

        String kindName = string(node, "kind", index);
        HealthKind kind = kinds.find(kindName).orElseThrow(() -> invalid(index, "kind " + kindName + " isn't a Health kind"));

        LocalDate localDay;
        Instant ts;
        try {
            localDay = LocalDate.parse(string(node, "local_day", index));
            // The database keeps microseconds; finer digits would make every resend look like a change.
            ts = Instant.parse(string(node, "ts", index)).truncatedTo(ChronoUnit.MICROS);
        } catch (DateTimeParseException bad) {
            throw invalid(index, "local_day must be YYYY-MM-DD and ts an ISO 8601 instant");
        }
        if (localDay.isBefore(EARLIEST)) throw invalid(index, "local_day is before " + EARLIEST);
        if (ts.isAfter(now.plus(Duration.ofDays(1)))) throw invalid(index, "ts is in the future");
        // A day total sits at local noon and a workout or night starts near its day, so ts and local_day agree to a day.
        LocalDate tsDay = LocalDate.ofInstant(ts, zone);
        if (tsDay.isBefore(localDay.minusDays(1)) || tsDay.isAfter(localDay.plusDays(1))) {
            throw invalid(index, "ts is more than a day away from local_day");
        }

        // Quantity first: a day that dropped to 0 should be sent as a delete, and this says so before any range does.
        JsonNode rawQuantity = node.path("quantity");
        if (!rawQuantity.isNumber()) throw invalid(index, "quantity must be a number");
        BigDecimal quantity = rawQuantity.decimalValue();
        if (quantity.signum() <= 0) throw invalid(index, "quantity must be greater than zero; send a 0 day as a delete");

        JsonNode data = node.path("data");
        if (!data.isObject()) throw invalid(index, "data must be an object");
        for (var field : data.properties()) {
            checkField(kind, field.getKey(), field.getValue(), index);
        }
        for (String field : kind.dataHints().keySet()) {
            if (kind.requires(field) && !data.has(field)) throw invalid(index, kindName + " needs data." + field);
        }

        if (kind.quantityField() == null) {
            if (quantity.compareTo(BigDecimal.ONE) != 0) throw invalid(index, "quantity must be 1 for " + kindName);
        } else if (quantity.compareTo(data.path(kind.quantityField()).decimalValue()) != 0) {
            throw invalid(index, "quantity must equal data." + kind.quantityField());
        }

        return new HealthItem(externalId, kindName, localDay, ts, quantity, JSON.convertValue(data, MAP));
    }

    /** A delete names an entry by its external id only. */
    public String deletedId(int index, JsonNode node) {
        return externalId(node, InvalidHealthItemException.Part.DELETED, index);
    }

    private static String externalId(JsonNode node, InvalidHealthItemException.Part part, int index) {
        if (!node.isString() || node.asString().isBlank()) {
            throw new InvalidHealthItemException(part, index, "external_id is required");
        }
        String value = node.asString();
        if (value.length() > MAX_EXTERNAL_ID) {
            throw new InvalidHealthItemException(part, index, "external_id is longer than " + MAX_EXTERNAL_ID);
        }
        return value;
    }

    private static void checkField(HealthKind kind, String field, JsonNode value, int index) {
        String type = kind.dataHints().get(field);
        if (type == null) throw invalid(index, "data." + field + " isn't a " + kind.kind() + " field");
        switch (type) {
            case "number" -> {
                if (!value.isNumber() || !Double.isFinite(value.asDouble())) {
                    throw invalid(index, "data." + field + " must be a number");
                }
                var range = kind.ranges().get(field);
                if (range != null && !range.contains(value.asDouble())) {
                    throw invalid(index, "data." + field + " must be between " + plain(range.min()) + " and " + plain(range.max()));
                }
            }
            case "string" -> {
                if (!value.isString() || value.asString().isBlank()) throw invalid(index, "data." + field + " must be text");
                if (value.asString().length() > MAX_STRING) {
                    throw invalid(index, "data." + field + " is longer than " + MAX_STRING);
                }
            }
            default -> {
                if (JSON.writeValueAsString(value).length() > MAX_ANY_JSON) {
                    throw invalid(index, "data." + field + " is too large");
                }
            }
        }
    }

    private static String string(JsonNode node, String field, int index) {
        JsonNode value = node.path(field);
        if (!value.isString()) throw invalid(index, field + " is required");
        return value.asString();
    }

    private static String plain(double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value);
    }

    private static InvalidHealthItemException invalid(Integer index, String message) {
        return new InvalidHealthItemException(index, message);
    }
}
