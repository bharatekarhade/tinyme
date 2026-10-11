package com.tinyme.domain.entries.model.aggregate;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PeriodTests {
    @Test
    void resolvesRelativePeriodsAcrossCalendarBoundaries() {
        LocalDate monday = LocalDate.of(2026, 10, 5);
        LocalDate sunday = LocalDate.of(2026, 10, 11);
        assertThat(Period.THIS_WEEK.resolve(Range.WEEK, monday))
                .isEqualTo(new DateRange(monday, sunday));
        assertThat(Period.THIS_WEEK.resolve(Range.WEEK, sunday))
                .isEqualTo(new DateRange(monday, sunday));
        assertThat(Period.THIS_MONTH.resolve(Range.MONTH, LocalDate.of(2026, 10, 31)))
                .isEqualTo(new DateRange(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31)));
        assertThat(Period.THIS_YEAR.resolve(Range.YEAR, LocalDate.of(2024, 1, 1)))
                .isEqualTo(new DateRange(LocalDate.of(2024, 1, 1), LocalDate.of(2024, 12, 31)));
        assertThat(Period.TODAY.resolve(Range.DAY, LocalDate.of(2024, 2, 29)))
                .isEqualTo(new DateRange(LocalDate.of(2024, 2, 29), LocalDate.of(2024, 2, 29)));
    }

    @Test
    void previousToDateOnWednesdayReturnsPreviousMondayThroughWednesday() {
        LocalDate monday = LocalDate.of(2026, 10, 5);
        LocalDate wednesday = monday.plusDays(2);
        assertThat(Period.RANGE.previous(Range.WEEK, wednesday))
                .isEqualTo(new DateRange(monday.minusWeeks(1), monday.minusWeeks(1).plusDays(2)));
    }

    @Test
    void previousCalendarPeriodsAndRollingThirtyDaysKeepTheirOwnSemantics() {
        assertThat(Period.THIS_MONTH.previous(Range.MONTH, LocalDate.of(2024, 3, 31)))
                .isEqualTo(new DateRange(LocalDate.of(2024, 2, 1), LocalDate.of(2024, 2, 29)));
        assertThat(Period.THIS_MONTH.previous(Range.MONTH, LocalDate.of(2026, 10, 15)))
                .isEqualTo(new DateRange(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 15)));
        assertThat(Period.THIS_YEAR.previous(Range.YEAR, LocalDate.of(2024, 3, 1)))
                .isEqualTo(new DateRange(LocalDate.of(2023, 1, 1), LocalDate.of(2023, 3, 1)));
        assertThat(Period.LAST_30_DAYS.previous(Range.MONTH, LocalDate.of(2026, 9, 30)))
                .isEqualTo(new DateRange(LocalDate.of(2026, 8, 2), LocalDate.of(2026, 8, 31)));
    }

    @Test
    void resolvesAutoBucketsAndRejectsInvertedRanges() {
        assertThat(Period.autoBucket(Range.DAY)).isEqualTo(GroupBy.DAY);
        assertThat(Period.autoBucket(Range.WEEK)).isEqualTo(GroupBy.DAY);
        assertThat(Period.autoBucket(Range.MONTH)).isEqualTo(GroupBy.DAY);
        assertThat(Period.autoBucket(Range.YEAR)).isEqualTo(GroupBy.MONTH);
        assertThatThrownBy(() -> new DateRange(LocalDate.of(2026, 1, 2), LocalDate.of(2026, 1, 1)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
