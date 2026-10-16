package com.tinyme.integrations.google.service;

import com.tinyme.settings.UserZone;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** Incremental, bounded Gmail scanner. Bodies are passed directly to the isolated scanner, never persisted. */
@Service
public class MailScanService {
    private static final Logger log = LoggerFactory.getLogger(MailScanService.class);
    private static final String INITIAL_QUERY = "newer_than:7d -in:sent -in:drafts -in:spam -in:trash "
            + "-category:promotions -category:social";
    private static final Pattern BOOKING_SUBJECT = Pattern.compile(
            "(?i)\\b(flight|itinerary|booking|reservation|check.?in|ticket|confirmation|eventbrite|hotel)\\b");
    private static final Pattern BOOKING_SENDER = Pattern.compile(
            "(?i)@(air|ana|jal|united|delta|booking|expedia|eventbrite|opentable|resy|agoda|hotels)\\.[a-z.]+$");
    private final GmailClient gmail;
    private final GmailMessageParser parser;
    private final MailScanAgentRunner scanner;
    private final MailScanRepository repository;
    private final UserZone userZone;
    private final Clock clock;
    private final int maxPerRun;
    private final int maxPerDay;

    public MailScanService(GmailClient gmail, MailScanAgentRunner scanner, MailScanRepository repository,
                           UserZone userZone, Clock clock,
                           @Value("${tinyme.mail-scan.max-per-run:30}") int maxPerRun,
                           @Value("${tinyme.mail-scan.max-per-day:200}") int maxPerDay) {
        this.gmail = gmail;
        this.parser = new GmailMessageParser();
        this.scanner = scanner;
        this.repository = repository;
        this.userZone = userZone;
        this.clock = clock;
        this.maxPerRun = Math.max(1, Math.min(100, maxPerRun));
        this.maxPerDay = Math.max(1, Math.min(1000, maxPerDay));
    }

    public RunResult run() {
        UUID runId = repository.startRun();
        Counters counts = new Counters();
        String finalStatus = "succeeded";
        String errorCode = null;
        try {
            repository.expireSuggestions(LocalDate.now(clock.withZone(userZone.current())));
            MailScanRepository.ScanState state = repository.state().orElse(null);
            if (state == null) {
                JsonNode profile = gmail.profile();
                String historyId = text(profile, "historyId");
                if (historyId == null) throw new IOException("Gmail profile has no history cursor");
                repository.startInitialScan(historyId);
                state = repository.state().orElseThrow();
            }
            LocalDate today = LocalDate.now(clock.withZone(userZone.current()));
            int remainingToday = Math.max(0, maxPerDay - repository.scannedSince(today.atStartOfDay(userZone.current()).toInstant()));
            if (remainingToday == 0) {
                repository.finishRun(runId, "succeeded", 0, 0, 0, null);
                return new RunResult(0, 0, 0, "daily_limit");
            }
            int limit = Math.min(maxPerRun, remainingToday);
            boolean checkpointSafe;
            if (!state.initialComplete()) {
                checkpointSafe = processInitialPage(state, today, limit, counts);
            } else {
                checkpointSafe = processHistory(state, today, limit, counts);
            }
            if (!checkpointSafe) finalStatus = "partial";
        } catch (GoogleApiHttpClient.GoogleApiException failure) {
            if (failure.statusCode() == 404) {
                try {
                    String latest = text(gmail.profile(), "historyId");
                    if (latest != null) repository.saveHistoryId(latest);
                    finalStatus = "partial";
                    errorCode = "history_cursor_expired";
                } catch (Exception resetFailure) {
                    finalStatus = "failed";
                    errorCode = "google_unavailable";
                    log.warn("Mail scan cursor reset failed ({})", resetFailure.getClass().getSimpleName());
                }
            } else {
                finalStatus = "failed";
                errorCode = failure.reason() == null ? "google_unavailable" : failure.reason();
                log.warn("Mail scan failed during Google API request (status={}, reason={})",
                        failure.statusCode(), failure.reason());
            }
        } catch (Exception failure) {
            finalStatus = "failed";
            errorCode = failure instanceof GoogleOAuthException oauth ? oauth.reason() : "scan_failed";
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            log.warn("Mail scan failed ({})", failure.getClass().getSimpleName());
        } finally {
            repository.finishRun(runId, finalStatus, counts.scanned, counts.suggested, counts.rejected, errorCode);
        }
        return new RunResult(counts.scanned, counts.suggested, counts.rejected, finalStatus);
    }

    private boolean processInitialPage(MailScanRepository.ScanState state, LocalDate today, int limit, Counters counts)
            throws IOException, InterruptedException {
        JsonNode page = gmail.searchMessages(INITIAL_QUERY, limit, state.initialPageToken());
        List<String> ids = ids(page.path("messages"));
        int consumed = processIds(ids, today, limit, counts);
        if (consumed < ids.size()) return false; // Keep the same page cursor until every item is handled.
        String next = text(page, "nextPageToken");
        boolean complete = next == null || next.isBlank();
        repository.saveInitialPage(complete ? null : next, complete);
        return true;
    }

    private boolean processHistory(MailScanRepository.ScanState state, LocalDate today, int limit, Counters counts)
            throws IOException, InterruptedException {
        List<String> pending = state.pendingIds();
        String pageToken = state.historyPageToken();
        String latestHistoryId = state.pendingHistoryId() == null ? state.historyId() : state.pendingHistoryId();
        if (pending.isEmpty()) {
            JsonNode page = gmail.history(state.historyId(), pageToken, 500);
            String pageHistoryId = text(page, "historyId");
            if (pageHistoryId != null) latestHistoryId = pageHistoryId;
            pending = historyMessageIds(page.path("history"));
            pageToken = text(page, "nextPageToken");
        }
        // Gmail can replay history entries after a crash or cursor retry. Remove terminal IDs
        // before applying the work cap so a large replay cannot starve newer messages.
        pending = pending.stream().filter(id -> !repository.scanned(id)).distinct().toList();
        if (pending.isEmpty()) {
            if (pageToken != null && !pageToken.isBlank()) {
                repository.saveHistoryProgress(List.of(), pageToken, latestHistoryId);
                return false;
            }
            repository.saveHistoryId(latestHistoryId);
            return true;
        }
        int take = Math.min(limit, pending.size());
        int consumed = processIds(pending.subList(0, take), today, take, counts);
        if (consumed < take) return false;
        List<String> remaining = List.copyOf(pending.subList(take, pending.size()));
        repository.saveHistoryProgress(remaining, pageToken, latestHistoryId);
        if (!remaining.isEmpty() || (pageToken != null && !pageToken.isBlank())) return false;
        repository.saveHistoryId(latestHistoryId);
        return true;
    }

    private static List<String> historyMessageIds(JsonNode rows) {
        List<String> ids = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        if (rows.isArray()) for (JsonNode row : rows) {
            JsonNode added = row.path("messagesAdded");
            if (added.isArray()) for (JsonNode item : added) {
                String id = text(item.path("message"), "id");
                if (id != null && seen.add(id)) ids.add(id);
            }
        }
        return List.copyOf(ids);
    }

    private int processIds(List<String> ids, LocalDate today, int limit, Counters counts)
            throws IOException, InterruptedException {
        int consumed = 0;
        int work = 0;
        List<MailScanAgentRunner.MailInput> candidates = new ArrayList<>();
        Map<String, MailScanAgentRunner.MailInput> candidateById = new HashMap<>();
        for (String id : ids) {
            if (repository.scanned(id)) { consumed++; continue; }
            if (work + candidates.size() >= limit) break;
            JsonNode raw;
            try {
                raw = gmail.getMessage(id, GoogleMessageFormat.METADATA);
            } catch (GoogleApiHttpClient.GoogleApiException gone) {
                if (gone.statusCode() != 404) throw gone;
                repository.markScanned(id, "skipped");
                counts.scanned++;
                work++;
                consumed++;
                continue;
            }
            GmailMessageParser.Message message = parser.parse(raw, false, 0);
            if (prefiltered(raw, message)) {
                repository.markScanned(id, "skipped");
                counts.scanned++;
                work++;
                consumed++;
                continue;
            }
            raw = gmail.getMessage(id, GoogleMessageFormat.FULL);
            message = parser.parse(raw, true, 4_000);
            var input = new MailScanAgentRunner.MailInput(id, message.from(), message.date(), message.subject(), message.body());
            candidates.add(input);
            candidateById.put(id, input);
            if (candidates.size() == 10) {
                int batchCount = scanBatch(candidates, candidateById, today, counts);
                work += batchCount;
                consumed += batchCount;
                candidates.clear();
                candidateById.clear();
            }
        }
        if (!candidates.isEmpty()) {
            int batchCount = scanBatch(candidates, candidateById, today, counts);
            work += batchCount;
            consumed += batchCount;
        }
        return consumed;
    }

    private int scanBatch(List<MailScanAgentRunner.MailInput> batch,
                          Map<String, MailScanAgentRunner.MailInput> byId, LocalDate today, Counters counts)
            throws IOException, InterruptedException {
        List<JsonNode> proposals;
        try {
            proposals = scanner.extract(List.copyOf(batch), today, userZone.current());
        } catch (Exception failure) {
            int terminal = 0;
            for (MailScanAgentRunner.MailInput email : batch) {
                if (repository.markExtractionFailure(email.id())) {
                    counts.scanned++;
                    counts.rejected++;
                    terminal++;
                }
            }
            if (terminal != batch.size()) {
                if (failure instanceof InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw interrupted;
                }
                if (failure instanceof IOException io) throw io;
                throw new IOException("Mail scanner extraction failed", failure);
            }
            return terminal;
        }
        Set<String> suggestedIds = new HashSet<>();
        Set<String> proposalIds = new HashSet<>();
        int rejected = 0;
        for (JsonNode proposal : proposals) {
            String messageId = text(proposal, "gmail_message_id");
            MailScanAgentRunner.MailInput source = byId.get(messageId);
            if (messageId == null || !proposalIds.add(messageId)) { rejected++; continue; }
            MailScanRepository.Candidate candidate = validate(proposal, source, today, userZone.current());
            if (candidate == null) { rejected++; continue; }
            if (repository.similarPlanExists(LocalDate.parse((String) candidate.payload().get("day")),
                    (String) candidate.payload().get("title"))) { rejected++; continue; }
            if (repository.saveSuggestion(messageId, candidate)) suggestedIds.add(messageId);
        }
        for (MailScanAgentRunner.MailInput email : batch) {
            boolean has = suggestedIds.contains(email.id());
            repository.markScanned(email.id(), has ? "suggested" : proposals.isEmpty() ? "no_suggestion" : "rejected");
            counts.scanned++;
            if (has) counts.suggested++;
        }
        counts.rejected += rejected;
        return batch.size();
    }

    static MailScanRepository.Candidate validate(JsonNode proposal, MailScanAgentRunner.MailInput source,
                                                  LocalDate today) {
        return validate(proposal, source, today, ZoneId.of("UTC"));
    }

    static MailScanRepository.Candidate validate(JsonNode proposal, MailScanAgentRunner.MailInput source,
                                                  LocalDate today, ZoneId userZone) {
        if (source == null) return null;
        String type = text(proposal, "type");
        if (type == null || !List.of("event", "task", "reminder").contains(type)) return null;
        String title = text(proposal, "title");
        String dayText = text(proposal, "day");
        String evidence = text(proposal, "evidence");
        JsonNode confidenceNode = proposal.get("confidence");
        if (title == null || title.isBlank() || title.length() > 120 || dayText == null || evidence == null
                || evidence.isBlank() || evidence.length() > 200 || confidenceNode == null || !confidenceNode.isNumber()
                || confidenceNode.doubleValue() < 0.6 || confidenceNode.doubleValue() > 1) return null;
        LocalDate day;
        try { day = LocalDate.parse(dayText); } catch (RuntimeException invalid) { return null; }
        String haystack = (source.subject() == null ? "" : source.subject()) + "\n" + source.body();
        if (!haystack.contains(evidence)) return null;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("title", title.strip());
        String time = text(proposal, "time");
        String endTime = text(proposal, "end_time");
        if (!"event".equals(type) && (time != null || endTime != null)) return null;
        try {
            LocalTime parsedTime = time == null ? null : LocalTime.parse(time);
            LocalTime parsedEnd = endTime == null ? null : LocalTime.parse(endTime);
            if (parsedEnd != null && (parsedTime == null || !parsedEnd.isAfter(parsedTime))) return null;
            String sourceZoneText = text(proposal, "time_zone");
            ZoneId sourceZone = sourceZoneText == null ? userZone : ZoneId.of(sourceZoneText);
            if (parsedTime != null && sourceZoneText != null) {
                var localizedStart = LocalDateTime.of(day, parsedTime).atZone(sourceZone).withZoneSameInstant(userZone);
                day = localizedStart.toLocalDate();
                payload.put("time", localizedStart.toLocalTime().toString());
                payload.put("source_time", parsedTime.toString());
                payload.put("source_time_zone", sourceZone.getId());
                if (parsedEnd != null) {
                    var localizedEnd = LocalDateTime.of(LocalDate.parse(dayText), parsedEnd)
                            .atZone(sourceZone).withZoneSameInstant(userZone);
                    payload.put("end_time", localizedEnd.toLocalTime().toString());
                    if (!localizedEnd.toLocalDate().equals(localizedStart.toLocalDate()))
                        payload.put("end_day", localizedEnd.toLocalDate().toString());
                }
            } else {
                if (parsedTime != null) payload.put("time", parsedTime.toString());
                if (parsedEnd != null) payload.put("end_time", parsedEnd.toString());
            }
        } catch (RuntimeException invalid) { return null; }
        if (day.isBefore(today)) return null;
        payload.put("day", day.toString());
        String place = text(proposal, "place");
        if (place != null && !place.isBlank()) payload.put("place", place.strip());
        List<String> people = new ArrayList<>();
        JsonNode peopleNode = proposal.path("people");
        if (peopleNode.isArray()) for (JsonNode person : peopleNode) {
            if (person.isString() && !person.asString().isBlank() && people.size() < 10)
                people.add(person.asString().strip());
        }
        if (!people.isEmpty()) payload.put("people", people);
        return new MailScanRepository.Candidate(type, payload, confidenceNode.doubleValue(), evidence);
    }

    static boolean prefiltered(JsonNode raw, GmailMessageParser.Message message) {
        JsonNode labels = raw.path("labelIds");
        if (labels.isArray()) for (JsonNode label : labels) {
            if (label.isString() && Set.of("SENT", "DRAFT", "SPAM", "TRASH", "CHAT",
                    "CATEGORY_PROMOTIONS", "CATEGORY_SOCIAL").contains(label.asString()))
                return true;
        }
        String unsubscribe = header(raw.path("payload").path("headers"), "List-Unsubscribe");
        if (unsubscribe == null || unsubscribe.isBlank()) return false;
        String sender = message.from() == null ? "" : message.from().toLowerCase(Locale.ROOT);
        java.util.regex.Matcher address = Pattern.compile("<([^>]+)>").matcher(sender);
        if (address.find()) sender = address.group(1);
        return !(BOOKING_SENDER.matcher(sender).find()
                || BOOKING_SUBJECT.matcher(message.subject() == null ? "" : message.subject()).find());
    }

    private static String header(JsonNode rows, String name) {
        if (rows.isArray()) for (JsonNode row : rows)
            if (name.equalsIgnoreCase(text(row, "name"))) return text(row, "value");
        return null;
    }

    private static List<String> ids(JsonNode rows) {
        List<String> result = new ArrayList<>();
        if (rows.isArray()) for (JsonNode row : rows) {
            String id = text(row, "id");
            if (id != null) result.add(id);
        }
        return result;
    }

    private static String text(JsonNode node, String key) {
        JsonNode value = node == null ? null : node.get(key);
        return value == null || value.isNull() || !value.isString() ? null : value.asString();
    }

    private static final class Counters { int scanned; int suggested; int rejected; }
    public record RunResult(int scanned, int suggested, int rejected, String status) { }
}
