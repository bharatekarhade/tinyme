package com.tinyme.domain.entries.repository;

import com.tinyme.domain.entries.entity.EntryKindEntity;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

@Repository
public class EntryKindRepository {
    private static final int MAX_MERGE_HOPS = 5;

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

        return resolve(kind);
    }

    private String resolve(String kind) {
        String current = kind;
        Set<String> visited = new HashSet<>();
        visited.add(current);

        for (int hop = 0; hop <= MAX_MERGE_HOPS; hop++) {
            EntryKindEntity entryKind = kinds.findById(current).orElse(null);
            if (entryKind == null) {
                throw new IllegalStateException(
                        "Entry kind merge target '" + current + "' does not exist");
            }
            String target = entryKind.mergedIntoKind();
            if (target == null) {
                return current;
            }
            if (!visited.add(target)) {
                throw new IllegalStateException("Cycle in entry kind merges at '" + target + "'");
            }
            if (hop == MAX_MERGE_HOPS) {
                break;
            }
            current = target;
        }

        throw new IllegalStateException("Entry kind merge chain for '" + kind
                + "' exceeds " + MAX_MERGE_HOPS + " hops");
    }
}
