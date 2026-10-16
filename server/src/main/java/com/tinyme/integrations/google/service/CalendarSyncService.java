package com.tinyme.integrations.google.service;

import com.tinyme.domain.plans.entity.PlanEntity;
import com.tinyme.domain.plans.entity.PlanOccurrenceEntity;
import com.tinyme.domain.plans.entity.PlanOccurrenceId;
import com.tinyme.domain.plans.model.OccurrenceExceptionStatus;
import com.tinyme.domain.plans.model.PlanKind;
import com.tinyme.domain.plans.model.PlanSource;
import com.tinyme.domain.plans.model.PlanStatus;
import com.tinyme.domain.plans.repository.PlanOccurrenceRepository;
import com.tinyme.domain.plans.repository.PlanRepository;
import com.tinyme.settings.UserZone;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Bidirectional plan/calendar sync. Each remote mutation is conditional and never blindly retried. */
@Service
public class CalendarSyncService {
    private static final Logger log = LoggerFactory.getLogger(CalendarSyncService.class);
    private static final int EXPANSION_DAYS = 62;
    private final CalendarClient calendar;
    private final CalendarSyncRepository state;
    private final PlanRepository plans;
    private final PlanOccurrenceRepository occurrences;
    private final GoogleCalendarEventMapper mapper;
    private final GoogleAccountRepository accounts;
    private final Clock clock;
    private final UserZone userZone;

    public CalendarSyncService(CalendarClient calendar, CalendarSyncRepository state, PlanRepository plans,
                               PlanOccurrenceRepository occurrences, GoogleCalendarEventMapper mapper,
                               GoogleAccountRepository accounts, Clock clock, UserZone userZone) {
        this.calendar = calendar;
        this.state = state;
        this.plans = plans;
        this.occurrences = occurrences;
        this.mapper = mapper;
        this.accounts = accounts;
        this.clock = clock;
        this.userZone = userZone;
    }

    public boolean connected() {
        return accounts.find().filter(account -> "connected".equals(account.status())).isPresent();
    }

    public void synchronize() {
        UUID run = state.startRun();
        List<Map<String, String>> errors = new ArrayList<>();
        try {
            if (!connected()) {
                state.finishRun(run, true, null, Map.of("skipped", "not_connected"));
                return;
            }
            String tinyCalendarId = ensureTinyMeCalendar();
            List<String> externalCalendars = calendarIds(tinyCalendarId);
            Set<String> known = new HashSet<>(externalCalendars);
            known.add(tinyCalendarId);
            state.removeCheckpointsOutside(known);
            try { syncTinyMeCalendar(tinyCalendarId, errors); }
            catch (InterruptedException interrupted) { throw interrupted; }
            catch (Exception failure) { recordCalendarError(errors, tinyCalendarId, failure); }
            for (String calendarId : externalCalendars) {
                try { syncCalendarChanges(calendarId, false, errors); }
                catch (InterruptedException interrupted) { throw interrupted; }
                catch (Exception failure) { recordCalendarError(errors, calendarId, failure); }
            }
            state.finishRun(run, errors.isEmpty(), errors.isEmpty() ? null : "partial_sync",
                    Map.of("calendar_count", externalCalendars.size() + 1, "errors", errors));
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            finishFailure(run, "interrupted", failure);
        } catch (Exception failure) {
            String reason = failure instanceof GoogleApiHttpClient.GoogleApiException api
                    ? api.reason() : failure.getClass().getSimpleName();
            finishFailure(run, reason, failure);
        }
    }

    private String ensureTinyMeCalendar() throws IOException, InterruptedException {
        String saved = state.tinyMeCalendarId().orElse(null);
        if (saved != null) return saved;
        String page = null;
        do {
            JsonNode response = calendar.listCalendars(page);
            for (JsonNode item : response.path("items")) {
                if ("TinyMe".equalsIgnoreCase(item.path("summary").asString(""))) {
                    String id = item.path("id").asString(null);
                    if (id != null) { state.setTinyMeCalendarId(id); return id; }
                }
            }
            page = response.path("nextPageToken").asString(null);
        } while (page != null);
        JsonNode created = calendar.createTinyMeCalendar(userZone.current().getId());
        String id = created.path("id").asString(null);
        if (id == null || id.isBlank()) throw new IOException("Google did not return the TinyMe calendar id");
        state.setTinyMeCalendarId(id);
        return id;
    }

    private List<String> calendarIds(String tinyCalendarId) throws IOException, InterruptedException {
        List<String> ids = new ArrayList<>();
        String page = null;
        do {
            JsonNode response = calendar.listCalendars(page);
            for (JsonNode item : response.path("items")) {
                String id = item.path("id").asString(null);
                boolean selectedOwner = "owner".equals(item.path("accessRole").asString())
                        && item.path("selected").asBoolean(false);
                boolean primary = item.path("primary").asBoolean(false);
                if (id != null && !id.equals(tinyCalendarId) && !item.path("deleted").asBoolean(false)
                        && (primary || selectedOwner))
                    ids.add(id);
            }
            page = response.path("nextPageToken").asString(null);
        } while (page != null);
        return ids;
    }

    private void syncTinyMeCalendar(String calendarId, List<Map<String, String>> errors) throws IOException, InterruptedException {
        syncOutbound(calendarId, errors);
        syncCalendarChanges(calendarId, true, errors);
    }

    private void syncOutbound(String calendarId, List<Map<String, String>> errors) throws IOException, InterruptedException {
        LocalDate cutoff = LocalDate.now(clock.withZone(userZone.current())).minusDays(30);
        for (PlanEntity plan : plans.findMirrorCandidates(cutoff)) {
            try {
                if (plan.getGoogleEventId() == null) {
                    JsonNode existing = findExistingMirror(calendarId, plan.getId());
                    JsonNode remote = existing == null ? calendar.createEvent(calendarId, mapper.toGoogle(plan)) : existing;
                    plan.setGoogleMirror(requiredText(remote, "id"), text(remote, "etag"));
                    plans.saveMirror(plan);
                } else {
                    JsonNode updated = calendar.updateEvent(calendarId, plan.getGoogleEventId(),
                            plan.getGoogleEtag(), mapper.toGoogle(plan));
                    plan.setGoogleMirror(plan.getGoogleEventId(), text(updated, "etag"));
                    plans.saveMirror(plan);
                }
                syncOverrides(calendarId, plan);
            } catch (InterruptedException interrupted) {
                throw interrupted;
            } catch (Exception failure) {
                recordPlanError(errors, plan, failure);
            }
        }
        for (PlanEntity plan : plans.findPendingMirrorDeletes()) {
            try {
                calendar.deleteEvent(calendarId, plan.getGoogleEventId(), plan.getGoogleEtag());
                plan.setGoogleMirror(null, null);
                plans.saveMirror(plan);
            } catch (InterruptedException interrupted) {
                throw interrupted;
            } catch (GoogleApiHttpClient.GoogleApiException failure) {
                if (failure.statusCode() == 404 || failure.statusCode() == 410) {
                    plan.setGoogleMirror(null, null);
                    plans.saveMirror(plan);
                } else recordPlanError(errors, plan, failure);
            } catch (Exception failure) {
                recordPlanError(errors, plan, failure);
            }
        }
    }

    private JsonNode findExistingMirror(String calendarId, UUID planId) throws IOException, InterruptedException {
        String page = null;
        do {
            JsonNode response = calendar.listByPrivateProperty(calendarId, "tinyme_plan_id", planId.toString(), page);
            for (JsonNode event : response.path("items")) {
                if (!"cancelled".equals(text(event, "status")) && planId.equals(mapper.tinyMePlanId(event))) return event;
            }
            page = text(response, "nextPageToken");
        } while (page != null);
        return null;
    }

    private void reconcileTinyMeEvent(String calendarId, PlanEntity plan, JsonNode remote)
            throws IOException, InterruptedException {
        if ("cancelled".equals(text(remote, "status"))) {
            plan.markRemoteDeleted(clock.instant());
            plans.saveMirror(plan);
            return;
        }
        String remoteEtag = text(remote, "etag");
        Instant remoteUpdated = instant(remote, "updated");
        Instant localUpdated = plan.getUpdatedAt();
        boolean localChangedSinceSync = localUpdated != null
                && (plan.getGoogleSyncAt() == null || localUpdated.isAfter(plan.getGoogleSyncAt()));
        if (Objects.equals(remoteEtag, plan.getGoogleEtag()) && !localChangedSinceSync) return;
        boolean localWins = localUpdated != null && (Objects.equals(remoteEtag, plan.getGoogleEtag())
                ? localChangedSinceSync : remoteUpdated == null || localUpdated.isAfter(remoteUpdated));
        if (localWins) {
            JsonNode updated = calendar.updateEvent(calendarId, plan.getGoogleEventId(), remoteEtag, mapper.toGoogle(plan));
            plan.setGoogleMirror(plan.getGoogleEventId(), text(updated, "etag"));
            plans.saveMirror(plan);
            return;
        }
        GoogleCalendarEventMapper.ImportedEvent mapped = mapper.fromGoogle(remote, ZoneId.of(plan.getZone()));
        if (mapped == null) return;
        plan.applyGoogleEdit(mapped.title(), mapped.notes(), mapped.place(), mapped.startDay(), mapped.startTime(),
                mapped.endTime(), mapped.endDay(), mapped.repeat());
        plan.setGoogleMirror(plan.getGoogleEventId(), remoteEtag);
        plans.saveMirror(plan);
    }

    private void syncCalendarChanges(String calendarId, boolean tinyCalendar, List<Map<String, String>> errors)
            throws IOException, InterruptedException {
        String token = state.syncToken(calendarId).orElse(null);
        boolean full = token == null;
        Set<String> seen = new HashSet<>();
        boolean eventFailure = false;
        try {
            String page = null;
            String nextSync = null;
            Instant cutoff = clock.instant().minus(Duration.ofDays(30));
            do {
                JsonNode response = calendar.listSyncEvents(calendarId, token, page);
                for (JsonNode event : response.path("items")) {
                    try {
                        if (tinyCalendar) importTinyMeChange(calendarId, event);
                        else importGoogleChange(calendarId, event, seen, cutoff);
                    } catch (InterruptedException interrupted) {
                        throw interrupted;
                    } catch (Exception failure) {
                        eventFailure = true;
                        recordEventError(errors, calendarId, event, failure);
                    }
                }
                page = text(response, "nextPageToken");
                nextSync = text(response, "nextSyncToken");
            } while (page != null);
            if (full && nextSync == null) throw new IOException("Google did not return a nextSyncToken");
            if (!eventFailure && nextSync != null) {
                if (full && !tinyCalendar) state.cancelImportedCalendarSnapshotExcept(calendarId, seen);
                state.saveSyncToken(calendarId, nextSync);
            }
        } catch (GoogleApiHttpClient.GoogleApiException expired) {
            if (expired.statusCode() != 410 || full) throw expired;
            state.clearSyncToken(calendarId);
            syncCalendarChanges(calendarId, tinyCalendar, errors);
        }
    }

    private void importTinyMeChange(String calendarId, JsonNode event) throws IOException, InterruptedException {
        if (event.has("recurringEventId")) {
            importTinyMeOccurrenceChange(calendarId, event);
            return;
        }
        UUID planId = mapper.tinyMePlanId(event);
        if (planId == null) return;
        PlanEntity plan = plans.findAny(planId).orElse(null);
        if (plan == null || plan.getSource() == PlanSource.GOOGLE) return;
        if ("cancelled".equals(text(event, "status"))) {
            plan.markRemoteDeleted(clock.instant());
            plans.saveMirror(plan);
        } else {
            reconcileTinyMeEvent(calendarId, plan, event);
        }
    }

    private void importGoogleChange(String calendarId, JsonNode event, Set<String> seen, Instant cutoff) {
        String eventId = text(event, "id");
        if (eventId == null) return;
        String externalId = mapper.externalId(calendarId, eventId);
        ZoneId zone = userZone.current();
        if (endedBefore(event, cutoff, zone)) return;
        if (event.has("recurringEventId")) {
            importRecurrenceException(calendarId, event);
            return;
        }
        if ("cancelled".equals(text(event, "status"))) {
            plans.findGoogleImported(externalId).ifPresent(plan -> {
                plan.cancel();
                plans.save(plan);
            });
            return;
        }
        GoogleCalendarEventMapper.ImportedEvent mapped = mapper.fromGoogle(event, zone);
        if (mapped == null) return;
        if (event.path("recurrence").isArray() && mapper.parseRepeat(event) == null) {
            importExpandedInstances(calendarId, eventId, mapped, seen, cutoff);
            return;
        }
        seen.add(externalId);
        upsertImported(externalId, text(event, "etag"), mapped);
    }

    private void importExpandedInstances(String calendarId, String masterId,
                                         GoogleCalendarEventMapper.ImportedEvent master,
                                         Set<String> seen, Instant cutoff) {
        LocalDate from = LocalDate.now(clock.withZone(master.zone()));
        Instant start = from.atStartOfDay(master.zone()).toInstant();
        Instant end = from.plusDays(EXPANSION_DAYS).atStartOfDay(master.zone()).toInstant();
        try {
            String page = null;
            do {
                JsonNode response = calendar.listEventInstances(calendarId, masterId, start, end, page);
                for (JsonNode instance : response.path("items")) {
                    if (endedBefore(instance, cutoff, master.zone())) continue;
                    GoogleCalendarEventMapper.ImportedEvent mapped = mapper.fromGoogle(instance, master.zone());
                    String instanceId = text(instance, "id");
                    if (mapped != null && instanceId != null) {
                        String externalId = mapper.externalId(calendarId, instanceId);
                        seen.add(externalId);
                        upsertImported(externalId, text(instance, "etag"), new GoogleCalendarEventMapper.ImportedEvent(mapped.title(),
                                mapped.notes(), mapped.place(), mapped.zone(), mapped.startDay(), mapped.startTime(),
                                mapped.endTime(), mapped.endDay(), null, mapped.allDay()));
                    }
                }
                page = text(response, "nextPageToken");
            } while (page != null);
        } catch (IOException | InterruptedException failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new IllegalStateException("Could not expand unsupported Google recurrence", failure);
        }
    }

    private void importRecurrenceException(String calendarId, JsonNode event) {
        String recurringId = text(event, "recurringEventId");
        String originalDayText = mapper.originalStartDay(event, userZone.current());
        if (recurringId == null || originalDayText == null) return;
        PlanEntity master = plans.findGoogleImported(mapper.externalId(calendarId, recurringId)).orElse(null);
        if (master == null) return;
        LocalDate originalDay = LocalDate.parse(originalDayText);
        PlanOccurrenceId key = new PlanOccurrenceId(master.getId(), originalDay);
        if ("cancelled".equals(text(event, "status"))) {
            occurrences.save(PlanOccurrenceEntity.deleted(master, originalDay));
            return;
        }
        GoogleCalendarEventMapper.ImportedEvent mapped = mapper.fromGoogle(event, ZoneId.of(master.getZone()));
        if (mapped != null && (!mapped.startDay().equals(originalDay)
                || !Objects.equals(mapped.startTime(), master.getStartTime()))) {
            occurrences.save(PlanOccurrenceEntity.moved(master, originalDay, mapped.startDay(), mapped.startTime(), mapped.endTime()));
        } else if (occurrences.find(key).isPresent()) {
            occurrences.delete(key);
        }
    }

    private void importTinyMeOccurrenceChange(String calendarId, JsonNode event) {
        String recurringId = text(event, "recurringEventId");
        String originalDayText = mapper.originalStartDay(event, userZone.current());
        if (recurringId == null || originalDayText == null) return;
        PlanEntity master = plans.findByGoogleEventId(recurringId).orElse(null);
        if (master == null || master.getSource() == PlanSource.GOOGLE) return;
        LocalDate originalDay = LocalDate.parse(originalDayText);
        PlanOccurrenceId key = new PlanOccurrenceId(master.getId(), originalDay);
        if ("cancelled".equals(text(event, "status"))) {
            occurrences.save(PlanOccurrenceEntity.deleted(master, originalDay));
            return;
        }
        GoogleCalendarEventMapper.ImportedEvent mapped = mapper.fromGoogle(event, ZoneId.of(master.getZone()));
        if (mapped != null && (!mapped.startDay().equals(originalDay)
                || !Objects.equals(mapped.startTime(), master.getStartTime()))) {
            occurrences.save(PlanOccurrenceEntity.moved(master, originalDay, mapped.startDay(), mapped.startTime(), mapped.endTime()));
        } else if (occurrences.find(key).isPresent()) {
            occurrences.delete(key);
        }
    }

    /** Reflect skipped and moved local occurrences as per-instance Google overrides. */
    private void syncOverrides(String calendarId, PlanEntity plan) throws IOException, InterruptedException {
        if (plan.getRepeat() == null || plan.getId() == null || plan.getGoogleEventId() == null) return;
        ZoneId zone = ZoneId.of(plan.getZone());
        LocalDate fromDay = LocalDate.now(clock.withZone(zone)).minusDays(1);
        LocalDate toDay = fromDay.plusDays(EXPANSION_DAYS);
        Map<LocalDate, PlanOccurrenceEntity> byOriginalDay = occurrences.findRelevant(
                        List.of(plan.getId()), fromDay, toDay).stream()
                .collect(java.util.stream.Collectors.toMap(PlanOccurrenceEntity::getDay, item -> item, (a, b) -> a));
        Instant from = fromDay.atStartOfDay(zone).toInstant();
        Instant to = toDay.plusDays(1).atStartOfDay(zone).toInstant();
        String page = null;
        do {
            JsonNode response = calendar.listEventInstances(calendarId, plan.getGoogleEventId(), from, to, page);
            for (JsonNode instance : response.path("items")) {
                String instanceId = text(instance, "id");
                String originalText = mapper.originalStartDay(instance, zone);
                if (instanceId == null || originalText == null) continue;
                PlanOccurrenceEntity exception = byOriginalDay.get(LocalDate.parse(originalText));
                if (exception == null) continue;
                String status = text(instance, "status");
                if (exception.getStatus() == OccurrenceExceptionStatus.SKIPPED
                        || exception.getStatus() == OccurrenceExceptionStatus.DELETED) {
                    if (!"cancelled".equals(status)) {
                        try { calendar.deleteEvent(calendarId, instanceId, text(instance, "etag")); }
                        catch (GoogleApiHttpClient.GoogleApiException conflict) {
                            if (conflict.statusCode() != 404 && conflict.statusCode() != 410) throw conflict;
                        }
                    }
                } else if (exception.getStatus() == OccurrenceExceptionStatus.MOVED) {
                    ObjectNode patch = occurrencePatch(plan, exception);
                    JsonNode remoteStart = instance.path("start");
                    JsonNode remoteEnd = instance.path("end");
                    if (!sameDate(remoteStart, patch.path("start")) || !sameDate(remoteEnd, patch.path("end")))
                        calendar.updateEvent(calendarId, instanceId, text(instance, "etag"), patch);
                }
            }
            page = text(response, "nextPageToken");
        } while (page != null);
    }

    private static ObjectNode occurrencePatch(PlanEntity plan, PlanOccurrenceEntity exception) {
        ObjectNode body = tools.jackson.databind.json.JsonMapper.builder().build().createObjectNode();
        ZoneId zone = ZoneId.of(plan.getZone());
        LocalDate day = exception.getMovedDay();
        if (plan.getStartTime() == null) {
            body.putObject("start").put("date", day.toString());
            body.putObject("end").put("date", day.plusDays(1).toString());
        } else {
            LocalTime startTime = exception.getMovedTime() == null ? plan.getStartTime() : exception.getMovedTime();
            LocalTime endTime = exception.getMovedEndTime() == null ? plan.getEndTime() : exception.getMovedEndTime();
            java.time.LocalDateTime start = day.atTime(startTime);
            java.time.LocalDateTime end = endTime == null ? start.plusHours(1) : day.atTime(endTime);
            body.putObject("start").put("dateTime", start.atZone(zone).toOffsetDateTime().toString()).put("timeZone", zone.getId());
            body.putObject("end").put("dateTime", end.atZone(zone).toOffsetDateTime().toString()).put("timeZone", zone.getId());
        }
        return body;
    }

    private static boolean sameDate(JsonNode left, JsonNode right) {
        String leftDate = text(left, "date");
        String rightDate = text(right, "date");
        if (leftDate != null || rightDate != null) return Objects.equals(leftDate, rightDate);
        return Objects.equals(text(left, "dateTime"), text(right, "dateTime"));
    }

    private void upsertImported(String externalId, String etag, GoogleCalendarEventMapper.ImportedEvent mapped) {
        PlanEntity plan = plans.findGoogleImported(externalId).orElse(null);
        if (plan == null) {
            plan = new PlanEntity(PlanKind.EVENT, mapped.title(), mapped.notes(), mapped.place(), mapped.zone(),
                    mapped.startDay(), mapped.startTime(), mapped.endTime(), mapped.endDay(), mapped.repeat(),
                    null, null, PlanSource.GOOGLE, externalId, Set.of());
        } else {
            plan.restore();
            plan.applyImportedGoogleEvent(PlanKind.EVENT, mapped.title(), mapped.notes(), mapped.place(), mapped.zone(),
                    mapped.startDay(), mapped.startTime(), mapped.endTime(), mapped.endDay(), mapped.repeat(), false);
        }
        plan.setGoogleEtag(etag);
        plans.saveMirror(plan);
    }

    private void finishFailure(UUID run, String reason, Exception failure) {
        log.warn("Google Calendar sync failed ({})", reason);
        try { state.finishRun(run, false, reason, Map.of("failure", failure.getClass().getSimpleName())); }
        catch (RuntimeException recordFailure) { log.warn("Could not record Google Calendar sync failure"); }
    }

    private boolean endedBefore(JsonNode event, Instant cutoff, ZoneId zone) {
        JsonNode recurrence = event.path("recurrence");
        if (recurrence.isArray() && !recurrence.isEmpty()) {
            LocalDate cutoffDay = cutoff.atZone(zone).toLocalDate();
            var repeat = mapper.parseRepeat(event);
            if (repeat != null) return repeat.until() != null && repeat.until().isBefore(cutoffDay);
            for (JsonNode lineNode : recurrence) {
                if (!lineNode.isString()) continue;
                for (String part : lineNode.stringValue().split(";")) {
                    if (!part.startsWith("UNTIL=")) continue;
                    String value = part.substring("UNTIL=".length());
                    try {
                        LocalDate until = value.length() == 8
                                ? LocalDate.parse(value, java.time.format.DateTimeFormatter.BASIC_ISO_DATE)
                                : Instant.from(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssX")
                                        .parse(value)).atZone(zone).toLocalDate();
                        return until.isBefore(cutoffDay);
                    } catch (RuntimeException ignored) { return false; }
                }
            }
            return false;
        }
        JsonNode end = event.path("end");
        String dateTime = text(end, "dateTime");
        if (dateTime != null) {
            try { return Instant.parse(dateTime).isBefore(cutoff); }
            catch (RuntimeException ignored) {
                try { return java.time.OffsetDateTime.parse(dateTime).toInstant().isBefore(cutoff); }
                catch (RuntimeException invalid) { return false; }
            }
        }
        String date = text(end, "date");
        if (date == null) return false;
        try { return LocalDate.parse(date).atStartOfDay(zone).toInstant().isBefore(cutoff); }
        catch (RuntimeException ignored) { return false; }
    }

    private static void recordCalendarError(List<Map<String, String>> errors, String calendarId, Exception failure) {
        errors.add(Map.of("calendar_id", calendarId, "error", failureReason(failure)));
    }

    private static void recordPlanError(List<Map<String, String>> errors, PlanEntity plan, Exception failure) {
        errors.add(Map.of("plan_id", String.valueOf(plan.getId()), "error", failureReason(failure)));
    }

    private static void recordEventError(List<Map<String, String>> errors, String calendarId,
                                         JsonNode event, Exception failure) {
        String id = text(event, "id");
        errors.add(Map.of("calendar_id", calendarId, "event_id", id == null ? "unknown" : id,
                "error", failureReason(failure)));
    }

    private static String failureReason(Exception failure) {
        if (failure instanceof GoogleApiHttpClient.GoogleApiException api)
            return api.reason() == null ? "google_api_error" : api.reason();
        return failure.getClass().getSimpleName();
    }

    private static String requiredText(JsonNode node, String field) throws IOException {
        String value = text(node, field);
        if (value == null || value.isBlank()) throw new IOException("Google response omitted " + field);
        return value;
    }
    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() || !value.isString() ? null : value.stringValue();
    }
    private static Instant instant(JsonNode node, String field) {
        String value = text(node, field);
        try { return value == null ? null : Instant.parse(value); }
        catch (RuntimeException invalid) { return null; }
    }
}
