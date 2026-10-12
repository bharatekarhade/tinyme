package com.tinyme.domain.plans.model;

import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RepeatTests {
    @Test
    void weekdaysKeepMondayToSundayOrder() {
        Repeat repeat = new Repeat(RepeatFrequency.WEEKLY, 1,
                Set.of(DayOfWeek.FRIDAY, DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY), null);

        assertThat(repeat.days()).containsExactly(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY);
    }

    @Test
    void daysAreOnlyAllowedForWeeklyRepeats() {
        assertThatThrownBy(() -> new Repeat(RepeatFrequency.DAILY, 1, Set.of(DayOfWeek.MONDAY), null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
