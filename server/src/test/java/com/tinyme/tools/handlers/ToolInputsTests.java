package com.tinyme.tools.handlers;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ToolInputsTests {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void optionalFieldsReturnNullWhenMissingAndConvertWhenPresent() {
        var input = JSON.readTree("""
                {"name":"coffee","limit":12,"quantity":2.5,
                 "day":"2026-10-09","ts":"2026-10-09T12:30:00+09:00"}
                """);

        assertThat(ToolInputs.optionalString(input, "name")).isEqualTo("coffee");
        assertThat(ToolInputs.optionalString(input, "missing")).isNull();
        assertThat(ToolInputs.optionalInt(input, "limit")).isEqualTo(12);
        assertThat(ToolInputs.optionalDecimal(input, "quantity")).isEqualByComparingTo("2.5");
        assertThat(ToolInputs.optionalDate(input, "day")).isEqualTo(LocalDate.of(2026, 10, 9));
        assertThat(ToolInputs.optionalInstant(input, "ts")).isEqualTo(Instant.parse("2026-10-09T03:30:00Z"));
        assertThat(ToolInputs.optionalObject(input, "missing")).isNull();
    }

    @Test
    void objectConversionDistinguishesMissingFromEmptyObject() {
        var input = JSON.readTree("{\"data\":{}}");

        assertThat(ToolInputs.optionalObject(input, "missing")).isNull();
        assertThat(ToolInputs.optionalObject(input, "data")).isEqualTo(Map.of());
    }

    @Test
    void requiredValuesAndInvalidTypesHaveClearErrors() {
        var input = JSON.readTree("{\"id\":\"not-a-uuid\",\"date\":\"yesterday\",\"count\":1.5}");

        assertThatThrownBy(() -> ToolInputs.requiredString(input, "missing"))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("missing is required");
        assertThatThrownBy(() -> ToolInputs.requiredUuid(input, "id"))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("id must be a UUID");
        assertThatThrownBy(() -> ToolInputs.optionalDate(input, "date"))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("date must be an ISO date");
        assertThatThrownBy(() -> ToolInputs.optionalInt(input, "count"))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("count must be an integer");
    }

    @Test
    void futureDateLimitAllowsOneDayButRejectsAnythingLater() {
        Instant now = Instant.parse("2026-10-09T00:00:00Z");
        ToolInputs.notFarInFuture(now.plus(1, ChronoUnit.DAYS), now, "ts");

        assertThatThrownBy(() -> ToolInputs.notFarInFuture(now.plus(1, ChronoUnit.DAYS).plusNanos(1), now, "ts"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("ts must not be more than one day in the future");
    }
}
