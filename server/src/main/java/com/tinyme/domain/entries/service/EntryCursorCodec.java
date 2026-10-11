package com.tinyme.domain.entries.service;

import com.tinyme.domain.entries.model.EntrySnapshot;
import com.tinyme.domain.entries.repository.EntryRepository;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

public final class EntryCursorCodec {
    private EntryCursorCodec() { }

    public static String encode(EntrySnapshot entry) {
        String value = entry.ts() + "\n" + entry.id();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    public static EntryRepository.EntryCursor decode(String cursor) {
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            String[] values = decoded.split("\\n", -1);
            if (values.length != 2) throw new IllegalArgumentException();
            Instant ts = Instant.parse(values[0]);
            UUID id = UUID.fromString(values[1]);
            if (!id.toString().equalsIgnoreCase(values[1])) throw new IllegalArgumentException();
            return new EntryRepository.EntryCursor(ts, id);
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("cursor is invalid", invalid);
        }
    }
}
