package com.tinyme.domain.entries.repository;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "tinyme.agent.setup-enabled=false",
        "TINYME_OWNER_EMAIL=owner@test.tinyme.local",
        "TINYME_OWNER_PASSWORD=test-only-owner-password"
})
@Import(EntryRepositoryTests.DatabaseConfiguration.class)
class EntryKindRepositoryTests {
    private final String prefix = "kind_" + UUID.randomUUID().toString().substring(0, 8) + "_";

    @Autowired
    EntryKindRepository kinds;

    @Autowired
    JdbcTemplate jdbc;

    @AfterEach
    void cleanup() {
        jdbc.update("UPDATE entry_kinds SET merged_into = NULL WHERE starts_with(kind, ?)", prefix);
        jdbc.update("DELETE FROM entry_kinds WHERE starts_with(kind, ?)", prefix);
    }

    @Test
    void startsAtOneAndIncrementsExistingKind() {
        String kind = prefix + "drink";
        assertThat(kinds.upsertAndResolve(kind)).isEqualTo(kind);
        assertThat(useCount(kind)).isEqualTo(1);
        assertThat(kinds.upsertAndResolve(kind)).isEqualTo(kind);
        assertThat(useCount(kind)).isEqualTo(2);
    }

    @Test
    void topKindsLimitsAndRanksCanonicalKindsWithStableTies() {
        var expected = new ArrayList<String>();
        for (int i = 0; i < 35; i++) {
            String kind = prefix + String.format(java.util.Locale.ROOT, "%02d", i);
            jdbc.update("INSERT INTO entry_kinds (kind, use_count) VALUES (?, ?)",
                    kind, 10000 - i / 2);
            if (i < 30) expected.add(kind);
        }
        jdbc.update("INSERT INTO entry_kinds (kind, use_count, merged_into) VALUES (?, ?, ?)",
                prefix + "alias", 20000, expected.getFirst());

        assertThat(kinds.topKinds(30)).containsExactlyElementsOf(expected);
    }

    @Test
    void simultaneousFirstUsesCreateOneKindAndCountEveryUse() throws Exception {
        String kind = prefix + "drink";
        int callers = 6;
        CountDownLatch ready = new CountDownLatch(callers);
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(callers);
        try {
            var results = new ArrayList<Future<String>>();
            for (int i = 0; i < callers; i++) {
                results.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Timed out waiting for concurrent callers");
                    }
                    return kinds.upsertAndResolve(kind);
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (Future<String> result : results) {
                assertThat(result.get(20, TimeUnit.SECONDS)).isEqualTo(kind);
            }
            assertThat(useCount(kind)).isEqualTo(callers);
            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM entry_kinds WHERE kind = ?", Integer.class, kind)).isEqualTo(1);
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void resolvesTerminalKindExactlyFiveHopsAway() {
        createChain(5);
        assertThat(kinds.upsertAndResolve(prefix + "0")).isEqualTo(prefix + "5");
        assertThat(useCount(prefix + "0")).isEqualTo(1);
        assertThat(useCount(prefix + "5")).isZero();
    }

    @Test
    void rejectsLongerChainAndRollsBackUsageIncrement() {
        createChain(6);
        assertThatThrownBy(() -> kinds.upsertAndResolve(prefix + "0"))
                .isInstanceOf(InvalidDataAccessApiUsageException.class)
                .hasCauseInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exceeds 5 hops");
        assertThat(useCount(prefix + "0")).isZero();
    }

    @Test
    void rejectsCycleAndRollsBackUsageIncrement() {
        createChain(2);
        jdbc.update("UPDATE entry_kinds SET merged_into = ? WHERE kind = ?", prefix + "0", prefix + "2");
        assertThatThrownBy(() -> kinds.upsertAndResolve(prefix + "0"))
                .isInstanceOf(InvalidDataAccessApiUsageException.class)
                .hasCauseInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cycle in entry kind merges");
        assertThat(useCount(prefix + "0")).isZero();
    }

    private int useCount(String kind) {
        return jdbc.queryForObject("SELECT use_count FROM entry_kinds WHERE kind = ?", Integer.class, kind);
    }

    private void createChain(int hops) {
        for (int i = 0; i <= hops; i++) {
            jdbc.update("INSERT INTO entry_kinds (kind) VALUES (?)", prefix + i);
        }
        for (int i = 0; i < hops; i++) {
            jdbc.update("UPDATE entry_kinds SET merged_into = ? WHERE kind = ?", prefix + (i + 1), prefix + i);
        }
    }
}
