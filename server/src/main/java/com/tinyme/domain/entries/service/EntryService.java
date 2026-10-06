package com.tinyme.domain.entries.service;

import com.tinyme.domain.entries.model.AddCommand;
import com.tinyme.domain.entries.model.AddResult;
import com.tinyme.domain.entries.model.NewEntry;
import com.tinyme.domain.entries.repository.EntryKindRepository;
import com.tinyme.domain.entries.repository.EntryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

@Service
public class EntryService {
    private final EntryKindRepository entryKinds;
    private final EntryRepository entries;

    public EntryService(EntryKindRepository entryKinds, EntryRepository entries) {
        this.entryKinds = Objects.requireNonNull(entryKinds, "entryKinds");
        this.entries = Objects.requireNonNull(entries, "entries");
    }

    @Transactional
    public AddResult add(AddCommand cmd) {
        Objects.requireNonNull(cmd, "cmd");
        String kind = entryKinds.upsertAndResolve(cmd.kind());
        BigDecimal quantity = cmd.quantity() == null ? BigDecimal.ONE : cmd.quantity();
        LocalDate localDay = LocalDate.ofInstant(cmd.ts(), cmd.zone());
        UUID id = entries.insert(new NewEntry(kind, quantity, cmd.text(), cmd.data(),
                cmd.tags(), cmd.ts(), localDay, cmd.source()));
        String type = cmd.data().get("type") instanceof String value ? value : null;
        BigDecimal total = entries.totalForDay(kind, localDay, type);
        return new AddResult(id, kind, localDay, quantity, total);
    }
}
