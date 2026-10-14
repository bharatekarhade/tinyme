package com.tinyme.domain.entries.repository;

import com.tinyme.domain.entries.entity.EntryKindEntity;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.util.HashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

@Repository
public class EntryKindRepository {
    private static final int MAX_MERGE_HOPS = 5;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final EntryKindJpaRepository kinds;

    EntryKindRepository(EntryKindJpaRepository kinds) {
        this.kinds = Objects.requireNonNull(kinds, "kinds");
    }

    /** Returns canonical kinds by usage, with alphabetical ordering for ties. */
    @Transactional(readOnly = true)
    public List<String> topKinds(int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
        return kinds.findByMergedIntoIsNullOrderByUseCountDescKindAsc(PageRequest.of(0, limit))
                .stream().map(EntryKindEntity::getKind).toList();
    }

    /**
     * Creates a new kind with use_count 1, or increments the existing kind,
     * then returns its canonical kind after following merge links.
     */
    @Transactional
    public String upsertAndResolve(String kind) {
        Objects.requireNonNull(kind, "kind");
        if (kind.isBlank()) {
            throw new IllegalArgumentException("kind must not be blank");
        }

        kinds.upsertAndIncrement(kind);

        return resolveExisting(kind).orElseThrow(() -> new IllegalStateException(
                "Entry kind '" + kind + "' disappeared while resolving it"));
    }

    /** Adds a card-defined kind without counting a card save as an entry use. */
    @Transactional
    public void ensureCardKind(String kind, String description, Map<String, ?> dataHints) {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(dataHints, "dataHints");
        kinds.insertCardKindIfMissing(kind, description, JSON.writeValueAsString(dataHints));
    }

    /**
     * Creates or refreshes a kind the code owns (the Apple Health kinds). Its hints are merged into what is
     * there, so fields an existing chat kind already uses stay listed; usage and merges are untouched.
     */
    @Transactional
    public void ensureOwnedKind(String kind, String description, Map<String, ?> dataHints) {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(dataHints, "dataHints");
        kinds.upsertOwnedKind(kind, description, JSON.writeValueAsString(dataHints));
    }

    /** Resolves a kind without creating it or changing its usage count. */
    @Transactional(readOnly = true)
    public Optional<String> resolveExisting(String kind) {
        Objects.requireNonNull(kind, "kind");
        return resolve(kind, loadMergeTargets());
    }

    /** Loads and resolves the whole vocabulary in one database round trip. */
    @Transactional(readOnly = true)
    public Map<String, String> resolveAll() {
        Map<String, String> targets = loadMergeTargets();
        Map<String, String> resolved = new LinkedHashMap<>();
        for (String start : targets.keySet()) {
            resolved.put(start, resolve(start, targets).orElseThrow());
        }
        return Map.copyOf(resolved);
    }

    private Map<String, String> loadMergeTargets() {
        Map<String, String> targets = new LinkedHashMap<>();
        for (EntryKindEntity entryKind : kinds.findAll()) {
            targets.put(entryKind.getKind(), entryKind.mergedIntoKind());
        }
        return targets;
    }

    private static Optional<String> resolve(String start, Map<String, String> targets) {
        String current = start;
        Set<String> visited = new HashSet<>();
        for (int hop = 0; hop <= MAX_MERGE_HOPS; hop++) {
            if (!visited.add(current)) {
                throw new IllegalStateException("Cycle in entry kind merges at '" + current + "'");
            }
            if (!targets.containsKey(current)) {
                if (hop == 0) return Optional.empty();
                throw new IllegalStateException("Entry kind merge target '" + current + "' does not exist");
            }
            String target = targets.get(current);
            if (target == null) return Optional.of(current);
            if (hop == MAX_MERGE_HOPS) {
                throw new IllegalStateException("Entry kind merge chain for '" + start + "' exceeds "
                        + MAX_MERGE_HOPS + " hops");
            }
            current = target;
        }
        throw new IllegalStateException("Unreachable entry kind merge resolution state");
    }
}
