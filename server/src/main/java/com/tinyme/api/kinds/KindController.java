package com.tinyme.api.kinds;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.tinyme.domain.entries.model.RecentKind;
import com.tinyme.domain.entries.service.EntryService;
import com.tinyme.settings.UserZone;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@RestController
@RequestMapping("/kinds")
public class KindController {
    private static final int WINDOW_DAYS = 90;
    private static final int MAX_KINDS = 20;
    private static final Map<String, String> FIXED_LABELS = Map.of(
            "drink", "Drinks",
            "meal", "Meals",
            "expense", "Spending",
            "workout", "Workouts",
            "journal", "Journal",
            "sleep", "Sleep");

    private final EntryService entries;
    private final UserZone userZone;
    private final Clock clock;

    public KindController(EntryService entries, UserZone userZone, Clock clock) {
        this.entries = entries;
        this.userZone = userZone;
        this.clock = clock;
    }

    @GetMapping
    public List<KindResponse> list() {
        ZoneId zone = userZone.current();
        LocalDate today = LocalDate.now(clock.withZone(zone));
        LocalDate from = today.minusDays(WINDOW_DAYS - 1L);
        return entries.recentCanonicalKinds(from, today, MAX_KINDS).stream()
                .map(row -> new KindResponse(row.kind(), label(row.kind()), row.count(), row.lastLoggedOn()))
                .toList();
    }

    private static String label(String kind) {
        String fixed = FIXED_LABELS.get(kind);
        if (fixed != null) return fixed;
        String words = kind.replace('_', ' ');
        return words.isEmpty() ? words : words.substring(0, 1).toUpperCase(Locale.ROOT) + words.substring(1);
    }

    public record KindResponse(String kind, String label, long count,
                               @JsonProperty("last_logged_on") LocalDate lastLoggedOn) { }
}
