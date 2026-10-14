package com.tinyme.domain.entries.service;

import com.tinyme.domain.entries.model.EntrySnapshot;
import com.tinyme.domain.entries.model.update.EntryPatch;
import com.tinyme.domain.health.repository.HealthSettingsRepository;
import com.tinyme.domain.health.service.HealthKinds;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Apple Health entries in chat and History (guide §4.5). TinyMe never changes what Health measured: the numbers
 * are read-only, and deleting one here would only bring it back on the next sync. It can add beside them: a note,
 * a workout name, or an entry for what Health missed ("my watch died, I slept 7 more hours"), marked
 * {@code data.adds_to_health}. Without that mark a Health kind isn't logged from chat while Health fills it in,
 * so the day isn't counted twice. Workouts are the exception: one told in chat is merged with the Watch's later.
 */
@Component
public class HealthEntryRules {
    public static final String SOURCE = "health";
    /** What a person may set on a Health entry: a note, and a name for a workout ("push day"). */
    public static final Set<String> EDITABLE_FIELDS = Set.of("note", "label");
    /** Set on a chat entry that adds what Health missed, so it's counted alongside Health's instead of refused. */
    public static final String ADDS_TO_HEALTH = "adds_to_health";
    private static final String WORKOUT = "workout";

    private final HealthKinds healthKinds;
    private final HealthSettingsRepository settings;

    public HealthEntryRules(HealthKinds healthKinds, HealthSettingsRepository settings) {
        this.healthKinds = healthKinds;
        this.settings = settings;
    }

    /** Refuses a chat entry for a Health kind whose group is connected, unless it adds what Health missed. */
    void checkAdd(String requestedKind, String canonicalKind, String source, Map<String, Object> data) {
        if (SOURCE.equals(source)) return;
        if (Boolean.TRUE.equals(data.get(ADDS_TO_HEALTH))) return;
        var kind = healthKinds.find(canonicalKind).or(() -> healthKinds.find(requestedKind));
        if (kind.isEmpty() || WORKOUT.equals(kind.get().kind())) return;
        if (!settings.load().groups().contains(kind.get().group())) return;
        throw new IllegalArgumentException("Apple Health fills in " + kind.get().kind().replace('_', ' ')
                + " while it's connected, so this wasn't logged again. If the user says Health missed some "
                + "(their watch died, they forgot it), log only the missing part with data.adds_to_health: true; "
                + "it's counted alongside Health's. Otherwise add a note to that day's entry (entries_update with data.note).");
    }

    /** The patch with only what may change: text, and data.note / data.label merged into the measured data. */
    EntryPatch checkUpdate(EntryPatch patch, EntrySnapshot current) {
        if (patch.kind() != null && !patch.kind().equals(current.kind())) throw readOnly();
        if (patch.ts() != null && !patch.ts().equals(current.ts())) throw readOnly();
        if (patch.quantity() != null && patch.quantity().compareTo(current.quantity()) != 0) throw readOnly();
        Map<String, Object> data = null;
        if (patch.data() != null) {
            data = new LinkedHashMap<>(current.data());
            for (var field : patch.data().entrySet()) {
                if (EDITABLE_FIELDS.contains(field.getKey())) {
                    if (field.getValue() == null || (field.getValue() instanceof String text && text.isBlank())) {
                        data.remove(field.getKey());
                    } else {
                        data.put(field.getKey(), field.getValue());
                    }
                } else if (!same(field.getValue(), current.data().get(field.getKey()))) {
                    throw readOnly();
                }
            }
        }
        if (patch.text() == null && data == null) throw readOnly();
        return new EntryPatch(patch.id(), null, null, patch.text(), data, null);
    }

    void refuseDelete() {
        throw new IllegalArgumentException("That came from Apple Health, so deleting it here would only bring it back "
                + "on the next sync. Delete it in the Health app and it disappears from TinyMe too.");
    }

    private static IllegalArgumentException readOnly() {
        return new IllegalArgumentException("That came from Apple Health, so its numbers can't be changed here; "
                + "change them in the Health app. You can add a note (data.note), or a name for a workout (data.label).");
    }

    private static boolean same(Object given, Object stored) {
        if (given instanceof Number a && stored instanceof Number b) {
            return new BigDecimal(a.toString()).compareTo(new BigDecimal(b.toString())) == 0;
        }
        return Objects.equals(given, stored);
    }
}
