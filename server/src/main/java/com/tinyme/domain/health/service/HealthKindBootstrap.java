package com.tinyme.domain.health.service;

import com.tinyme.domain.entries.repository.EntryKindRepository;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Puts the Apple Health kinds into entry_kinds at startup and after Delete all. The code owns them. */
@Component
@Order(-2)
@DependsOnDatabaseInitialization
public class HealthKindBootstrap implements ApplicationRunner {
    private final HealthKinds healthKinds;
    private final EntryKindRepository entryKinds;

    public HealthKindBootstrap(HealthKinds healthKinds, EntryKindRepository entryKinds) {
        this.healthKinds = healthKinds;
        this.entryKinds = entryKinds;
    }

    @Override
    public void run(ApplicationArguments args) {
        seed();
    }

    @Transactional
    public void seed() {
        healthKinds.all().forEach(kind -> entryKinds.ensureOwnedKind(kind.kind(), kind.description(), kind.dataHints()));
    }
}
