package com.tinyme.domain.entries.model.aggregate;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PeriodTests {
    private static final LocalDate WEDNESDAY = LocalDate.of(2026, 10, 7);

    @Test
    void resolvesEveryPeriodAgainstEveryRange() {
        Map<Period, DateRange> fixedPeriods = Map.of(
                Period.TODAY, new DateRange(WEDNESDAY, WEDNESDAY),
                Period.YESTERDAY, new DateRange(WEDNESDAY.minusDays(1), WEDNESDAY.minusDays(1)),
                Period.THIS_WEEK, new DateRange(LocalDate.of(2026, 10, 5), LocalDate.of(2026, 10, 11)),
                Period.LAST_7_DAYS, new DateRange(LocalDate.of(2026, 10, 1), WEDNESDAY),
                Period.THIS_MONTH, new DateRange(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31)),
                Period.LAST_30_DAYS, new DateRange(LocalDate.of(2026, 9, 8), WEDNESDAY),
                Period.LAST_12_WEEKS, new DateRange(LocalDate.of(2026, 7, 20), WEDNESDAY),
                Period.THIS_YEAR, new DateRange(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31)));

        for (Range range : Range.values()) {
            assertThat(Period.RANGE.resolve(range, WEDNESDAY))
                    .as("range %s", range)
                    .isEqualTo(switch (range) {
                        case DAY -> new DateRange(WEDNESDAY, WEDNESDAY);
                        case WEEK -> fixedPeriods.get(Period.THIS_WEEK);
                        case MONTH -> fixedPeriods.get(Period.THIS_MONTH);
                        case YEAR -> fixedPeriods.get(Period.THIS_YEAR);
                    });
            fixedPeriods.forEach((period, dates) -> assertThat(period.resolve(range, WEDNESDAY))
                    .as("period %s with range %s", period, range)
                    .isEqualTo(dates));
        }
    }

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
    void previousToDateHandlesAllPeriodsAndRangeEdgesOnWednesday() {
        assertThat(Period.RANGE.previous(Range.DAY, WEDNESDAY))
                .isEqualTo(new DateRange(LocalDate.of(2026, 10, 6), LocalDate.of(2026, 10, 6)));
        assertThat(Period.RANGE.previous(Range.WEEK, WEDNESDAY))
                .isEqualTo(new DateRange(LocalDate.of(2026, 9, 28), LocalDate.of(2026, 9, 30)));
        assertThat(Period.RANGE.previous(Range.MONTH, WEDNESDAY))
                .isEqualTo(new DateRange(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 7)));
        assertThat(Period.RANGE.previous(Range.YEAR, WEDNESDAY))
                .isEqualTo(new DateRange(LocalDate.of(2025, 1, 1), LocalDate.of(2025, 10, 7)));
        assertThat(Period.TODAY.previous(Range.WEEK, WEDNESDAY))
                .isEqualTo(new DateRange(LocalDate.of(2026, 10, 6), LocalDate.of(2026, 10, 6)));
        assertThat(Period.YESTERDAY.previous(Range.WEEK, WEDNESDAY))
                .isEqualTo(new DateRange(LocalDate.of(2026, 10, 5), LocalDate.of(2026, 10, 5)));
        assertThat(Period.THIS_WEEK.previous(Range.MONTH, WEDNESDAY))
                .isEqualTo(new DateRange(LocalDate.of(2026, 9, 28), LocalDate.of(2026, 9, 30)));
        assertThat(Period.LAST_7_DAYS.previous(Range.DAY, WEDNESDAY))
                .isEqualTo(new DateRange(LocalDate.of(2026, 9, 24), LocalDate.of(2026, 9, 30)));
        assertThat(Period.THIS_MONTH.previous(Range.YEAR, WEDNESDAY))
                .isEqualTo(new DateRange(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 7)));
        assertThat(Period.LAST_30_DAYS.previous(Range.MONTH, WEDNESDAY))
                .isEqualTo(new DateRange(LocalDate.of(2026, 8, 9), LocalDate.of(2026, 9, 7)));
        assertThat(Period.LAST_12_WEEKS.previous(Range.WEEK, WEDNESDAY))
                .isEqualTo(new DateRange(LocalDate.of(2026, 5, 1), LocalDate.of(2026, 7, 19)));
        assertThat(Period.THIS_YEAR.previous(Range.DAY, WEDNESDAY))
                .isEqualTo(new DateRange(LocalDate.of(2025, 1, 1), LocalDate.of(2025, 10, 7)));
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
