package com.tinyme.domain.health.service;

import com.tinyme.domain.entries.repository.EntryKindRepository;
import com.tinyme.domain.health.model.HealthItem;
import com.tinyme.domain.health.model.HealthSyncResult;
import com.tinyme.domain.health.model.InvalidHealthItemException;
import com.tinyme.domain.health.repository.HealthEntryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * Turns a batch from the phone into entries: insert, replace when a value changed, leave alone when it didn't,
 * and soft-delete what Health deleted. Sending the same batch twice changes nothing.
 */
@Service
public class HealthSyncService {
    public static final int MAX_ITEMS = 500;
    /** How far back a chat workout is looked for to merge with a Watch one, whichever arrived first. */
    static final int MERGE_DAYS = 30;
    /**
     * Fields a person adds to a Health entry in TinyMe; a newer value from Health keeps them. A workout's own name
     * ("push day") is data.label, so data.type stays whatever Health sends.
     */
    static final Set<String> KEPT_FIELDS = Set.of("note", "label", "merged_from");
    private static final Logger log = LoggerFactory.getLogger(HealthSyncService.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final HealthItemReader reader;
    private final HealthKinds healthKinds;
    private final HealthEntryRepository entries;
    private final EntryKindRepository entryKinds;

    public HealthSyncService(HealthItemReader reader, HealthKinds healthKinds, HealthEntryRepository entries,
                             EntryKindRepository entryKinds) {
        this.reader = reader;
        this.healthKinds = healthKinds;
        this.entries = entries;
        this.entryKinds = entryKinds;
    }

    @Transactional
    public HealthSyncResult sync(JsonNode rawItems, JsonNode rawDeleted, ZoneId zone, Instant now) {
        Objects.requireNonNull(zone, "zone");
        Objects.requireNonNull(now, "now");
        List<HealthItem> items = readItems(rawItems, zone, now);
        Set<String> deleted = readDeleted(rawDeleted, items);

        entries.lockSync();
        Map<String, HealthEntryRepository.Stored> stored = entries.findLive(items.stream().map(HealthItem::externalId).toList());
        Map<String, String> canonical = new HashMap<>();
        Map<String, Integer> inserted = new TreeMap<>();
        Set<String> touchedKinds = new LinkedHashSet<>();
        int upserted = 0;
        int unchanged = 0;
        for (HealthItem item : items) {
            String kind = canonical.computeIfAbsent(item.kind(), this::canonicalKind);
            touchedKinds.add(kind);
            var existing = stored.get(item.externalId());
            if (existing == null) {
                entries.insert(item, kind, item.data());
                inserted.merge(kind, 1, Integer::sum);
                upserted++;
                continue;
            }
            Map<String, Object> data = withKeptFields(item.data(), existing.data());
            if (same(existing, item, kind, data)) {
                unchanged++;
            } else {
                entries.update(existing.id(), item, kind, data);
                upserted++;
            }
        }
        List<String> deletedKinds = entries.softDelete(deleted, now);
        touchedKinds.addAll(deletedKinds);

        int merged = mergeChatWorkouts(zone, now);

        inserted.forEach(entries::addUses);
        touchedKinds.forEach(kind -> entries.refreshSyncLog(kind, now));
        Map<String, Integer> perKind = new TreeMap<>();
        items.forEach(item -> perKind.merge(item.kind(), 1, Integer::sum));
        log.info("Health sync: items={} upserted={} unchanged={} deleted={} merged={} per_kind={}",
                items.size(), upserted, unchanged, deletedKinds.size(), merged, perKind);
        return new HealthSyncResult(upserted, unchanged, deletedKinds.size(), merged);
    }

    /**
     * "Did push day" in chat and the Watch's strength workout are one session (guide §7.2). Runs on every sync over
     * the last 30 days, so it works whichever arrived first, and for pairs from before this existed.
     */
    private int mergeChatWorkouts(ZoneId zone, Instant now) {
        String workout = entryKinds.resolveExisting("workout").orElse("workout");
        var pairs = entries.workoutMergePairs(workout, LocalDate.ofInstant(now, zone).minusDays(MERGE_DAYS));
        pairs.forEach(pair -> entries.merge(pair, now));
        return pairs.size();
    }

    private List<HealthItem> readItems(JsonNode raw, ZoneId zone, Instant now) {
        if (raw == null || raw.isMissingNode() || raw.isNull()) return List.of();
        if (!raw.isArray()) throw new InvalidHealthItemException(null, "items must be an array");
        if (raw.size() > MAX_ITEMS) throw new InvalidHealthItemException(null, "send at most " + MAX_ITEMS + " items at a time");
        List<HealthItem> items = new ArrayList<>(raw.size());
        Set<String> ids = new LinkedHashSet<>();
        for (int index = 0; index < raw.size(); index++) {
            HealthItem item = reader.read(index, raw.get(index), zone, now);
            if (!ids.add(item.externalId())) {
                throw new InvalidHealthItemException(index, "external_id " + item.externalId() + " is sent twice");
            }
            items.add(item);
        }
        return items;
    }

    private Set<String> readDeleted(JsonNode raw, List<HealthItem> items) {
        if (raw == null || raw.isMissingNode() || raw.isNull()) return Set.of();
        if (!raw.isArray()) throw new InvalidHealthItemException(null, "deleted must be an array");
        if (raw.size() > MAX_ITEMS) throw new InvalidHealthItemException(null, "delete at most " + MAX_ITEMS + " at a time");
        Set<String> upserting = new LinkedHashSet<>();
        items.forEach(item -> upserting.add(item.externalId()));
        Set<String> deleted = new LinkedHashSet<>();
        for (int index = 0; index < raw.size(); index++) {
            String externalId = reader.deletedId(index, raw.get(index));
            if (upserting.contains(externalId)) {
                throw new InvalidHealthItemException(InvalidHealthItemException.Part.DELETED, index,
                        "external_id " + externalId + " is both sent and deleted");
            }
            deleted.add(externalId);
        }
        return deleted;
    }

    /** The kind entries are stored under: the Health kind, or where it was merged to. */
    private String canonicalKind(String kind) {
        return entryKinds.resolveExisting(kind).orElseGet(() -> {
            var health = healthKinds.find(kind).orElseThrow();
            entryKinds.ensureOwnedKind(health.kind(), health.description(), health.dataHints());
            return kind;
        });
    }

    private static Map<String, Object> withKeptFields(Map<String, Object> fromHealth, Map<String, Object> stored) {
        Map<String, Object> data = new LinkedHashMap<>(fromHealth);
        for (String field : KEPT_FIELDS) {
            if (stored.containsKey(field) && !fromHealth.containsKey(field)) data.put(field, stored.get(field));
        }
        return data;
    }

    private static boolean same(HealthEntryRepository.Stored existing, HealthItem item, String kind,
                                Map<String, Object> data) {
        return existing.kind().equals(kind)
                && existing.localDay().equals(item.localDay())
                && existing.ts().equals(item.ts())
                && existing.quantity().compareTo(item.quantity()) == 0
                && JSON.valueToTree(existing.data()).equals(JSON.valueToTree(data));
    }
}
