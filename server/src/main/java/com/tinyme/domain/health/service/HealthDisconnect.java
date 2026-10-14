package com.tinyme.domain.health.service;

import com.tinyme.domain.health.model.HealthSettings;
import com.tinyme.domain.health.repository.HealthEntryRepository;
import com.tinyme.domain.health.repository.HealthSettingsRepository;
import com.tinyme.domain.health.repository.HealthSyncEpochRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Set;

/**
 * Disconnect (guide §4.4): soft-deletes every entry Health made, clears the sync log and switches every group off.
 * What you added stays: notes and chat additions are your entries, and a chat workout a Watch one absorbed is
 * brought back, since its words lived on the Health entry being removed. Follow-up switches are kept.
 */
@Service
public class HealthDisconnect {
    private static final Logger log = LoggerFactory.getLogger(HealthDisconnect.class);

    public record Result(int removed, int restored) { }

    private final HealthEntryRepository entries;
    private final HealthSettingsRepository settings;
    private final HealthSyncEpochRepository syncEpoch;

    public HealthDisconnect(HealthEntryRepository entries, HealthSettingsRepository settings,
                            HealthSyncEpochRepository syncEpoch) {
        this.entries = entries;
        this.settings = settings;
        this.syncEpoch = syncEpoch;
    }

    @Transactional
    public Result removeImportedData(Instant now) {
        entries.lockSync();
        int restored = entries.restoreMergedChatEntries();
        int removed = entries.softDeleteAll(now);
        entries.clearSyncLog();
        syncEpoch.bump();
        var current = settings.load();
        settings.save(new HealthSettings(Set.of(), false, current.nudgesOff()), now);
        log.info("Health disconnected: removed={} restored_chat_workouts={}", removed, restored);
        return new Result(removed, restored);
    }
}
