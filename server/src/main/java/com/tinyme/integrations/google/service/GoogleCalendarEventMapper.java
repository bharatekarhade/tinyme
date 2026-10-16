package com.tinyme.integrations.google.service;

import com.tinyme.domain.plans.entity.PlanEntity;
import com.tinyme.domain.plans.model.PlanKind;
import com.tinyme.domain.plans.model.Repeat;
import com.tinyme.domain.plans.model.RepeatFrequency;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Converts plan/event fields while keeping timed events in their stored wall-clock zone. */
@Component
public class GoogleCalendarEventMapper {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    public ObjectNode toGoogle(PlanEntity plan) {
        ObjectNode event = JSON.createObjectNode();
        event.put("summary", plan.getTitle());
        put(event, "description", plan.getNotes());
        put(event, "location", plan.getPlace());
        ZoneId zone = ZoneId.of(plan.getZone());
        if (plan.getStartTime() == null) {
            event.putObject("start").put("date", plan.getStartDay().toString());
            LocalDate exclusiveEnd = (plan.getEndDay() == null ? plan.getStartDay() : plan.getEndDay()).plusDays(1);
            event.putObject("end").put("date", exclusiveEnd.toString());
        } else {
            LocalDateTime start = LocalDateTime.of(plan.getStartDay(), plan.getStartTime());
            LocalDateTime end;
            if (plan.getEndTime() == null) end = start.plusHours(1);
            else end = LocalDateTime.of(plan.getEndDay() == null ? plan.getStartDay() : plan.getEndDay(), plan.getEndTime());
            putDateTime(event.putObject("start"), start, zone);
            putDateTime(event.putObject("end"), end, zone);
        }
        Repeat repeat = plan.getRepeat();
        if (repeat != null) event.putArray("recurrence").add(recurrence(repeat, plan.getStartTime() != null, zone));
        ObjectNode extended = event.putObject("extendedProperties").putObject("private");
        extended.put("tinyme_plan_id", plan.getId().toString());
        return event;
    }

    public ImportedEvent fromGoogle(JsonNode event, ZoneId fallbackZone) {
        JsonNode start = event.path("start");
        JsonNode end = event.path("end");
        String startDate = text(start, "date");
        String summary = text(event, "summary");
        if (summary == null || summary.isBlank()) summary = "Untitled event";
        if (summary.length() > 120) summary = summary.substring(0, 120).stripTrailing();
        String description = text(event, "description");
        String place = text(event, "location");
        if (startDate != null) {
            LocalDate first = LocalDate.parse(startDate);
            String endDate = text(end, "date");
            LocalDate lastInclusive = endDate == null ? first : LocalDate.parse(endDate).minusDays(1);
            return new ImportedEvent(summary, description, place, fallbackZone,
                    first, null, null, lastInclusive.equals(first) ? null : lastInclusive,
                    parseRepeat(event), true);
        }
        String startDateTime = text(start, "dateTime");
        String endDateTime = text(end, "dateTime");
        if (startDateTime == null || endDateTime == null) return null;
        ZoneId zone = zone(start, startDateTime, fallbackZone);
        LocalDateTime startLocal = localDateTime(startDateTime, zone);
        LocalDateTime endLocal = localDateTime(endDateTime, zone);
        return new ImportedEvent(summary, description, place, zone, startLocal.toLocalDate(),
                startLocal.toLocalTime(), endLocal.toLocalTime(),
                endLocal.toLocalDate().equals(startLocal.toLocalDate()) ? null : endLocal.toLocalDate(),
                parseRepeat(event), false);
    }

    public Repeat parseRepeat(JsonNode event) {
        JsonNode recurrence = event.path("recurrence");
        if (!recurrence.isArray() || recurrence.isEmpty()) return null;
        if (recurrence.size() != 1 || !recurrence.get(0).isString()) return null;
        String line = recurrence.get(0).stringValue();
        if (!line.startsWith("RRULE:")) return null;
        Map<String, String> values = new LinkedHashMap<>();
        for (String token : line.substring(6).split(";")) {
            String[] pair = token.split("=", 2);
            if (pair.length != 2) return null;
            values.put(pair[0].toUpperCase(Locale.ROOT), pair[1].toUpperCase(Locale.ROOT));
        }
        if (values.keySet().stream().anyMatch(key -> !List.of("FREQ", "INTERVAL", "BYDAY", "UNTIL").contains(key))) return null;
        RepeatFrequency frequency = switch (values.getOrDefault("FREQ", "")) {
            case "DAILY" -> RepeatFrequency.DAILY;
            case "WEEKLY" -> RepeatFrequency.WEEKLY;
            case "MONTHLY" -> RepeatFrequency.MONTHLY;
            case "YEARLY" -> RepeatFrequency.YEARLY;
            default -> null;
        };
        if (frequency == null) return null;
        try {
            int interval = Integer.parseInt(values.getOrDefault("INTERVAL", "1"));
            if (interval < 1 || interval > 52) return null;
            var days = new java.util.LinkedHashSet<DayOfWeek>();
            String byDay = values.get("BYDAY");
            if (byDay != null) {
                if (frequency != RepeatFrequency.WEEKLY) return null;
                for (String day : byDay.split(",")) days.add(switch (day) {
                    case "MO" -> DayOfWeek.MONDAY;
                    case "TU" -> DayOfWeek.TUESDAY;
                    case "WE" -> DayOfWeek.WEDNESDAY;
                    case "TH" -> DayOfWeek.THURSDAY;
                    case "FR" -> DayOfWeek.FRIDAY;
                    case "SA" -> DayOfWeek.SATURDAY;
                    case "SU" -> DayOfWeek.SUNDAY;
                    default -> throw new IllegalArgumentException();
                });
            }
            LocalDate until = values.containsKey("UNTIL") ? parseUntil(values.get("UNTIL")) : null;
            return new Repeat(frequency, interval, days, until);
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    public String externalId(String calendarId, String eventId) { return calendarId + ":" + eventId; }
    public UUID tinyMePlanId(JsonNode event) {
        String id = text(event.path("extendedProperties").path("private"), "tinyme_plan_id");
        if (id == null) return null;
        try { return UUID.fromString(id); } catch (IllegalArgumentException ignored) { return null; }
    }

    public String originalStartDay(JsonNode event, ZoneId zone) {
        JsonNode original = event.path("originalStartTime");
        String date = text(original, "date");
        if (date != null) return LocalDate.parse(date).toString();
        String dateTime = text(original, "dateTime");
        if (dateTime == null) return null;
        try { return OffsetDateTime.parse(dateTime).toInstant().atZone(zone).toLocalDate().toString(); }
        catch (RuntimeException invalidOffset) {
            try { return Instant.parse(dateTime).atZone(zone).toLocalDate().toString(); }
            catch (RuntimeException invalidInstant) { return LocalDateTime.parse(dateTime.replace("Z", "")).toLocalDate().toString(); }
        }
    }

    public static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() || !value.isString() ? null : value.stringValue();
    }

    private static void put(ObjectNode node, String field, String value) {
        if (value != null && !value.isBlank()) node.put(field, value);
    }

    private static void putDateTime(ObjectNode node, LocalDateTime local, ZoneId zone) {
        ZonedDateTime zoned = local.atZone(zone);
        node.put("dateTime", DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(zoned));
        node.put("timeZone", zone.getId());
    }

    private static ZoneId zone(JsonNode time, String dateTime, ZoneId fallback) {
        String name = text(time, "timeZone");
        if (name != null) try { return ZoneId.of(name); } catch (RuntimeException ignored) { }
        try { return OffsetDateTime.parse(dateTime).getOffset(); } catch (RuntimeException ignored) { return fallback; }
    }

    private static LocalDateTime localDateTime(String text, ZoneId zone) {
        try { return OffsetDateTime.parse(text).toInstant().atZone(zone).toLocalDateTime(); }
        catch (RuntimeException ignored) { return LocalDateTime.parse(text.replace("Z", "")); }
    }

    private static String recurrence(Repeat repeat, boolean timed, ZoneId zone) {
        StringBuilder rule = new StringBuilder("RRULE:FREQ=").append(repeat.freq().name())
                .append(";INTERVAL=").append(repeat.interval());
        if (!repeat.days().isEmpty()) {
            List<String> days = new ArrayList<>();
            for (DayOfWeek day : repeat.days()) days.add(switch (day) {
                case MONDAY -> "MO"; case TUESDAY -> "TU"; case WEDNESDAY -> "WE";
                case THURSDAY -> "TH"; case FRIDAY -> "FR"; case SATURDAY -> "SA"; case SUNDAY -> "SU";
            });
            rule.append(";BYDAY=").append(String.join(",", days));
        }
        if (repeat.until() != null) {
            String until = timed
                    ? repeat.until().plusDays(1).atStartOfDay(zone).toInstant().minusSeconds(1)
                        .atOffset(java.time.ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'"))
                    : repeat.until().format(DateTimeFormatter.BASIC_ISO_DATE);
            rule.append(";UNTIL=").append(until);
        }
        return rule.toString();
    }

    private static LocalDate parseUntil(String value) {
        if (value.length() == 8) return LocalDate.parse(value, DateTimeFormatter.BASIC_ISO_DATE);
        return Instant.from(DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssX").parse(value))
                .atZone(ZoneId.of("UTC")).toLocalDate();
    }

    public record ImportedEvent(String title, String notes, String place, ZoneId zone,
                                LocalDate startDay, LocalTime startTime, LocalTime endTime, LocalDate endDay,
                                Repeat repeat, boolean allDay) { }
}
