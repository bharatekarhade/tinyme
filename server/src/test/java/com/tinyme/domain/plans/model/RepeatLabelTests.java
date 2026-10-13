package com.tinyme.domain.plans.model;

import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class RepeatLabelTests {
    @Test
    void formatsCommonRepeatRulesAsPlainLanguage() {
        assertThat(RepeatLabel.format(new Repeat(RepeatFrequency.WEEKLY, 1,
                Set.of(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY), null),
                LocalDate.of(2026, 10, 12))).isEqualTo("Every Mon, Wed, Fri");
        assertThat(RepeatLabel.format(new Repeat(RepeatFrequency.MONTHLY, 1, Set.of(), null),
                LocalDate.of(2026, 10, 14))).isEqualTo("Every month on the 14th");
        assertThat(RepeatLabel.format(new Repeat(RepeatFrequency.MONTHLY, 1, Set.of(), null),
                LocalDate.of(2026, 10, 31))).isEqualTo("Every month on the 31st");
        assertThat(RepeatLabel.format(new Repeat(RepeatFrequency.DAILY, 1, Set.of(), null),
                LocalDate.of(2026, 10, 13))).isEqualTo("Every day");
    }
}
