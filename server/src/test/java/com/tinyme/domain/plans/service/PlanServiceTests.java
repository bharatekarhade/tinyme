package com.tinyme.domain.plans.service;

import com.tinyme.domain.entries.repository.EntryKindRepository;
import com.tinyme.domain.entries.repository.EntryRepository;
import com.tinyme.domain.entries.model.EntryDay;
import com.tinyme.domain.people.model.Birthday;
import com.tinyme.domain.people.model.BirthdayPerson;
import com.tinyme.domain.people.repository.PersonRepository;
import com.tinyme.domain.plans.entity.PlanEntity;
import com.tinyme.domain.plans.entity.PlanOccurrenceEntity;
import com.tinyme.domain.plans.model.OccurrenceExceptionStatus;
import com.tinyme.domain.plans.model.OccurrenceStatus;
import com.tinyme.domain.plans.model.PlanAddCommand;
import com.tinyme.domain.plans.model.PlanChange;
import com.tinyme.domain.plans.model.PlanKind;
import com.tinyme.domain.plans.model.PlanQuery;
import com.tinyme.domain.plans.model.PlanSource;
import com.tinyme.domain.plans.model.Repeat;
import com.tinyme.domain.plans.model.RepeatFrequency;
import com.tinyme.domain.plans.repository.PlanOccurrenceRepository;
import com.tinyme.domain.plans.repository.PlanRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.MonthDay;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlanServiceTests {
    private static final Instant NOW = Instant.parse("2026-10-13T01:00:00Z");
    private static final ZoneId ZONE = ZoneId.of("Asia/Tokyo");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 13);

    private final PlanRepository plans = mock(PlanRepository.class);
    private final PlanOccurrenceRepository occurrences = mock(PlanOccurrenceRepository.class);
    private final PersonRepository people = mock(PersonRepository.class);
    private final EntryRepository entries = mock(EntryRepository.class);
    private final EntryKindRepository kinds = mock(EntryKindRepository.class);
    private final PlanService service = new PlanService(plans, occurrences, people, entries, kinds);

    @Test
    void queryCanFindBirthdayByAlias() {
        when(plans.findForCalendar(TODAY, TODAY)).thenReturn(List.of());
        when(occurrences.findMovedIntoRange(TODAY, TODAY)).thenReturn(List.of());
        when(people.findLiveBirthdays()).thenReturn(List.of(new BirthdayPerson("kenji", "Kenji", List.of("Ken"),
                "friend", "people/kenji.md", new Birthday(MonthDay.of(10, 13), 1990))));
        when(kinds.resolveAll()).thenReturn(Map.of());

        var result = service.query(new PlanQuery(TODAY, TODAY, null, null, "Ken", false), ZONE, NOW);

        assertThat(result.occurrences()).singleElement().satisfies(item -> {
            assertThat(item.id()).isEqualTo("birthday:kenji:2026-10-13");
            assertThat(item.people()).singleElement().satisfies(person -> assertThat(person.slug()).isEqualTo("kenji"));
        });
    }

    @Test
    void doneCannotBeAppliedToARepeatingEvent() {
        UUID id = UUID.randomUUID();
        PlanEntity event = mock(PlanEntity.class);
        when(event.getStatus()).thenReturn(com.tinyme.domain.plans.model.PlanStatus.ACTIVE);
        when(event.getKind()).thenReturn(PlanKind.EVENT);
        when(event.getRepeat()).thenReturn(new Repeat(RepeatFrequency.DAILY, 1, Set.of(), null));
        when(plans.findLive(id)).thenReturn(Optional.of(event));

        assertThatThrownBy(() -> service.update(id, new PlanChange.Done(TODAY), ZONE, NOW))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("only tasks");
        verify(occurrences, org.mockito.Mockito.never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void googleImportedPlansCannotBeEditedOrDeletedInTinyMe() {
        UUID id = UUID.randomUUID();
        PlanEntity imported = mock(PlanEntity.class);
        when(imported.getSource()).thenReturn(PlanSource.GOOGLE);
        when(plans.findLive(id)).thenReturn(Optional.of(imported));

        assertThatThrownBy(() -> service.update(id,
                new PlanChange.Cancel(), ZONE, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("This event is from Google Calendar; change it there");
        assertThatThrownBy(() -> service.delete(id, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("This event is from Google Calendar; change it there");
    }

    @Test
    void habitEntryUnderMergedKindMarksItsPlanDone() {
        UUID id = UUID.randomUUID();
        PlanEntity habit = mock(PlanEntity.class);
        when(habit.getId()).thenReturn(id);
        when(habit.getKind()).thenReturn(PlanKind.TASK);
        when(habit.getTitle()).thenReturn("Gym");
        when(habit.getZone()).thenReturn(ZONE.getId());
        when(habit.getStartDay()).thenReturn(TODAY);
        when(habit.getHabitKind()).thenReturn("exercise_old");
        when(habit.getStatus()).thenReturn(com.tinyme.domain.plans.model.PlanStatus.ACTIVE);
        when(habit.getSource()).thenReturn(PlanSource.CHAT);
        when(habit.getPeople()).thenReturn(Set.of());
        when(plans.findForCalendar(TODAY, TODAY)).thenReturn(List.of(habit));
        when(occurrences.findMovedIntoRange(TODAY, TODAY)).thenReturn(List.of());
        when(occurrences.findRelevant(List.of(id), TODAY, TODAY)).thenReturn(List.of());
        when(people.findLiveBirthdays()).thenReturn(List.of());
        when(kinds.resolveAll()).thenReturn(Map.of("exercise_old", "workout", "workout", "workout"));
        when(entries.habitDays(org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.eq(TODAY), org.mockito.ArgumentMatchers.eq(TODAY)))
                .thenReturn(List.of(new EntryDay("exercise_old", TODAY)));

        var result = service.query(new PlanQuery(TODAY, TODAY, null, null, null, true), ZONE, NOW);

        assertThat(result.occurrences()).singleElement()
                .satisfies(item -> assertThat(item.status()).isEqualTo(
                        OccurrenceStatus.DONE));
    }

    @Test
    void upcomingLineOmitsSkippedItemsIncludesInProgressAndTomorrowAllDay() {
        UUID gymId = UUID.randomUUID();
        UUID currentEventId = UUID.randomUUID();
        UUID tomorrowId = UUID.randomUUID();
        PlanEntity gym = plan(gymId, PlanKind.TASK, "Gym", TODAY, LocalTime.of(19, 30),
                new Repeat(RepeatFrequency.DAILY, 1, Set.of(), null));
        PlanEntity currentEvent = plan(currentEventId, PlanKind.EVENT, "Doctor", TODAY, LocalTime.of(9, 0), null);
        when(currentEvent.getEndTime()).thenReturn(LocalTime.of(11, 0));
        PlanEntity tomorrowEvent = plan(tomorrowId, PlanKind.EVENT, "Team sync", TODAY.plusDays(1), null, null);
        PlanEntity skippedTomorrow = plan(UUID.randomUUID(), PlanKind.TASK, "Skipped tomorrow", TODAY.plusDays(1),
                LocalTime.of(8, 0), new Repeat(RepeatFrequency.DAILY, 1, Set.of(), null));
        when(plans.findForCalendar(TODAY, TODAY.plusDays(1)))
                .thenReturn(List.of(gym, currentEvent, tomorrowEvent, skippedTomorrow));
        when(occurrences.findMovedIntoRange(TODAY, TODAY.plusDays(1))).thenReturn(List.of());
        var skipped = PlanOccurrenceEntity.skipped(gym, TODAY);
        var skippedFuture = PlanOccurrenceEntity.skipped(skippedTomorrow, TODAY.plusDays(1));
        when(occurrences.findRelevant(org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.eq(TODAY), org.mockito.ArgumentMatchers.eq(TODAY.plusDays(1))))
                .thenReturn(List.of(skipped, skippedFuture));
        when(people.findLiveBirthdays()).thenReturn(List.of());
        when(kinds.resolveAll()).thenReturn(Map.of());
        when(entries.habitDays(org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.eq(TODAY), org.mockito.ArgumentMatchers.eq(TODAY.plusDays(1))))
                .thenReturn(List.of());

        String context = service.contextLine(ZONE, NOW);

        assertThat(context).contains("09:00 Doctor").contains("tomorrow Team sync")
                .doesNotContain("Gym").doesNotContain("Skipped tomorrow");
    }

    @Test
    void addRejectsPastDatesAndHabitKindsThatDoNotExist() {
        assertThatThrownBy(() -> service.add(new PlanAddCommand(PlanKind.TASK, "Past", TODAY.minusDays(1),
                        null, null, null, null, List.of(), null, null, null, null), ZONE, NOW))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("day is in the past");

        when(kinds.resolveExisting("workout")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.resolveHabitKind(" Workout "))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("existing or card-registered");
    }

    private static PlanEntity plan(UUID id, PlanKind kind, String title, LocalDate day, LocalTime time, Repeat repeat) {
        PlanEntity plan = mock(PlanEntity.class);
        when(plan.getId()).thenReturn(id);
        when(plan.getKind()).thenReturn(kind);
        when(plan.getTitle()).thenReturn(title);
        when(plan.getZone()).thenReturn(ZONE.getId());
        when(plan.getStartDay()).thenReturn(day);
        when(plan.getStartTime()).thenReturn(time);
        when(plan.getRepeat()).thenReturn(repeat);
        when(plan.getStatus()).thenReturn(com.tinyme.domain.plans.model.PlanStatus.ACTIVE);
        when(plan.getSource()).thenReturn(PlanSource.CHAT);
        when(plan.getPeople()).thenReturn(Set.of());
        return plan;
    }
}
