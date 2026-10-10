package com.tinyme.tools.handlers;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

final class ToolInputs {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private ToolInputs() {
    }

    static String optionalString(JsonNode input, String key) {
        JsonNode value = value(input, key);
        if (value == null || value.isNull()) return null;
        if (!value.isString()) throw new IllegalArgumentException(key + " must be a string");
        return value.stringValue();
    }

    static String requiredString(JsonNode input, String key) {
        String value = optionalString(input, key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException(key + " is required");
        return value;
    }

    static Integer optionalInt(JsonNode input, String key) {
        JsonNode value = value(input, key);
        if (value == null || value.isNull()) return null;
        if (!value.isIntegralNumber()) {
            throw new IllegalArgumentException(key + " must be an integer");
        }
        BigInteger integer = value.bigIntegerValue();
        if (integer.compareTo(BigInteger.valueOf(Integer.MIN_VALUE)) < 0
                || integer.compareTo(BigInteger.valueOf(Integer.MAX_VALUE)) > 0) {
            throw new IllegalArgumentException(key + " is outside the supported integer range");
        }
        return integer.intValue();
    }

    static BigDecimal optionalDecimal(JsonNode input, String key) {
        JsonNode value = value(input, key);
        if (value == null || value.isNull()) return null;
        if (!value.isNumber()) throw new IllegalArgumentException(key + " must be a number");
        return value.decimalValue();
    }

    static LocalDate optionalDate(JsonNode input, String key) {
        String value = optionalString(input, key);
        if (value == null) return null;
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException(key + " must be an ISO date");
        }
    }

    static Instant optionalInstant(JsonNode input, String key) {
        String value = optionalString(input, key);
        if (value == null) return null;
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException(key + " must be an ISO date-time with an offset");
        }
    }

    static void notFarInFuture(Instant value, Instant now, String key) {
        if (value == null || now == null) throw new IllegalArgumentException(key + " and now are required");
        if (value.isAfter(now.plus(1, ChronoUnit.DAYS))) {
            throw new IllegalArgumentException(key + " must not be more than one day in the future");
        }
    }

    static UUID requiredUuid(JsonNode input, String key) {
        String value = requiredString(input, key);
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(key + " must be a UUID");
        }
    }

    static Map<String, Object> optionalObject(JsonNode input, String key) {
        JsonNode value = value(input, key);
        if (value == null || value.isNull()) return null;
        if (!value.isObject()) throw new IllegalArgumentException(key + " must be an object");
        return JSON.convertValue(value, MAP_TYPE);
    }

    static List<String> optionalStringList(JsonNode input, String key) {
        JsonNode value = value(input, key);
        if (value == null || value.isNull()) return null;
        if (!value.isArray()) throw new IllegalArgumentException(key + " must be an array of strings");
        List<String> result = new ArrayList<>(value.size());
        for (JsonNode item : value) {
            if (!item.isString()) throw new IllegalArgumentException(key + " must contain only strings");
            result.add(item.stringValue());
        }
        return List.copyOf(result);
    }

    private static JsonNode value(JsonNode input, String key) {
        return input == null ? null : input.get(key);
    }
}
