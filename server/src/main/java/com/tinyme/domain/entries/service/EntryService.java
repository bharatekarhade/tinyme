package com.tinyme.domain.entries.service;

import com.tinyme.domain.entries.model.EntryWriteResult;
import com.tinyme.domain.entries.model.EntrySnapshot;
import com.tinyme.domain.entries.model.add.AddCommand;
import com.tinyme.domain.entries.model.add.AddResult;
import com.tinyme.domain.entries.model.aggregate.AggregateQuery;
import com.tinyme.domain.entries.model.aggregate.AggregateResult;
import com.tinyme.domain.entries.model.query.EntryQuery;
import com.tinyme.domain.entries.model.query.EntryQueryResult;
import com.tinyme.domain.entries.model.add.NewEntry;
import com.tinyme.domain.entries.model.update.EntryPatch;
import com.tinyme.domain.entries.repository.EntryKindRepository;
import com.tinyme.domain.entries.repository.EntryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Objects;
import java.util.Optional;
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

    @Transactional(readOnly = true)
    public AggregateResult aggregate(AggregateQuery query) {
        Objects.requireNonNull(query, "query");
        Optional<String> resolved = entryKinds.resolveExisting(query.kind());
        AggregateQuery resolvedQuery = query.withKind(resolved.orElse(query.kind()));
        AggregateResult result = entries.aggregate(resolvedQuery);
        return new AggregateResult(result.kind(), resolved.isPresent(), result.metric(), result.field(),
                result.where(), result.from(), result.to(), result.groupBy(), result.value(),
                result.entries(), result.buckets());
    }

    @Transactional(readOnly = true)
    public EntryQueryResult query(EntryQuery query) {
        Objects.requireNonNull(query, "query");
        if (query.kind() == null) return entries.find(query);

        Optional<String> resolved = entryKinds.resolveExisting(query.kind());
        if (resolved.isEmpty()) return new EntryQueryResult(false, java.util.List.of(), false);
        return entries.find(query.withKind(resolved.get()));
    }

    @Transactional
    public Optional<EntryWriteResult> update(EntryPatch patch, ZoneId zone) {
        Objects.requireNonNull(patch, "patch");
        Objects.requireNonNull(zone, "zone");

        Optional<EntrySnapshot> found = entries.findLive(patch.id());
        if (found.isEmpty()) return Optional.empty();

        var current = found.get();
        String kind = patch.kind() == null
                ? current.kind()
                : entryKinds.upsertAndResolve(patch.kind());
        BigDecimal quantity = patch.quantity() == null ? current.quantity() : patch.quantity();
        String text = patch.text() == null ? current.text()
                : patch.text().isBlank() ? null : patch.text();
        var data = patch.data() == null ? current.data() : patch.data();
        var ts = patch.ts() == null ? current.ts() : patch.ts();
        LocalDate localDay = patch.ts() == null
                ? current.localDay()
                : LocalDate.ofInstant(ts, zone);

        var updated = entries.update(patch.id(), kind, quantity, text, data, ts, localDay);
        String type = data.get("type") instanceof String value ? value : null;
        BigDecimal dayTotal = entries.totalForDay(kind, localDay, type);
        return Optional.of(new EntryWriteResult(updated, dayTotal));
    }

    @Transactional
    public Optional<EntryWriteResult> delete(UUID id, Instant now) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(now, "now");

        Optional<EntrySnapshot> deleted = entries.softDelete(id, now);
        if (deleted.isEmpty()) return Optional.empty();

        EntrySnapshot entry = deleted.get();
        String type = entry.data().get("type") instanceof String value ? value : null;
        BigDecimal dayTotal = entries.totalForDay(entry.kind(), entry.localDay(), type);
        return Optional.of(new EntryWriteResult(entry, dayTotal));
    }
}
