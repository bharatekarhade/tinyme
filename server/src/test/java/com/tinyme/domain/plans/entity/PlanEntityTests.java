package com.tinyme.domain.plans.entity;

import com.tinyme.domain.plans.model.PlanKind;
import com.tinyme.domain.plans.model.PlanSource;
import com.tinyme.domain.plans.model.Repeat;
import com.tinyme.domain.plans.model.RepeatFrequency;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlanEntityTests {
    private static final LocalDate START = LocalDate.of(2026, 10, 12);

    @Test
    void stripsTitleAndNormalizesHabitKind() {
        PlanEntity plan = plan("  Gym  ", " Workout ", null, null, null);

        assertThat(plan.getTitle()).isEqualTo("Gym");
        assertThat(plan.getHabitKind()).isEqualTo("workout");
    }

    @Test
    void rejectsBlankTitleAndInvalidEndTimes() {
        assertThatThrownBy(() -> plan("   ", null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> plan("Event", null, null, LocalTime.NOON, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> plan("Event", null, LocalTime.of(22, 0), LocalTime.of(1, 0), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void editValidatesCombinedScheduleAndDoesNotMutateOnFailure() {
        PlanEntity plan = plan("Event", null, LocalTime.of(10, 0), LocalTime.of(11, 0), null);

        assertThatThrownBy(() -> plan.edit("Event", null, null, null, null, null,
                START, null, LocalTime.NOON, null))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(plan.getStartDay()).isEqualTo(START);
        assertThat(plan.getStartTime()).isEqualTo(LocalTime.of(10, 0));
        assertThat(plan.getEndTime()).isEqualTo(LocalTime.of(11, 0));

        LocalDate laterStart = START.plusDays(16);
        PlanEntity later = new PlanEntity(PlanKind.TASK, "Reminder", null, null, ZoneId.of("Asia/Tokyo"),
                laterStart, null, null, null, null, null, null, PlanSource.CHAT, null, Set.of());
        later.edit("Reminder", null, null, null, null,
                new Repeat(RepeatFrequency.DAILY, 1, Set.of(), START.plusDays(18)),
                START.plusDays(14), null, null, null);
        assertThat(later.getStartDay()).isEqualTo(START.plusDays(14));
        assertThat(later.getRepeat().until()).isEqualTo(START.plusDays(18));
    }

    @Test
    void repeatingPlansAreCompletedByOccurrenceNotAsAWhole() {
        PlanEntity plan = plan("Gym", "workout", null, null,
                new Repeat(RepeatFrequency.WEEKLY, 1, Set.of(DayOfWeek.MONDAY), null));

        assertThatThrownBy(() -> plan.markDone(java.time.Instant.now()))
                .isInstanceOf(IllegalStateException.class);
    }

    private static PlanEntity plan(String title, String habitKind, LocalTime startTime, LocalTime endTime,
                                   Repeat repeat) {
        return new PlanEntity(PlanKind.EVENT, title, null, null, ZoneId.of("Asia/Tokyo"), START,
                startTime, endTime, null,
                repeat, habitKind, null, PlanSource.CHAT, null, Set.of());
    }
}
