package com.tinyme.tools.handlers;

import com.tinyme.domain.plans.model.RepeatFrequency;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlanToolInputsTests {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void parsesRepeatDaysAndDefaultsTheInterval() throws Exception {
        var input = JSON.readTree("""
                {"repeat":{"freq":"weekly","days":["mon","wed","fri"]}}
                """);

        var repeat = PlanToolInputs.optionalRepeat(input);

        assertThat(repeat.freq()).isEqualTo(RepeatFrequency.WEEKLY);
        assertThat(repeat.interval()).isEqualTo(1);
        assertThat(repeat.days()).containsExactly(
                java.time.DayOfWeek.MONDAY, java.time.DayOfWeek.WEDNESDAY, java.time.DayOfWeek.FRIDAY);
    }

    @Test
    void rejectsInvalidRepeatDayAndClockTime() throws Exception {
        assertThatThrownBy(() -> PlanToolInputs.optionalRepeat(JSON.readTree(
                "{\"repeat\":{\"freq\":\"weekly\",\"days\":[\"monday\"]}}")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("repeat.days");
        assertThatThrownBy(() -> PlanToolInputs.optionalTime(JSON.readTree("{\"time\":\"25:00\"}"), "time"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("HH:mm");
    }
}
