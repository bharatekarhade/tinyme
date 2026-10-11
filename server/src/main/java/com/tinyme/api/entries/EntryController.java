package com.tinyme.api.entries;

import com.tinyme.domain.entries.model.EntrySnapshot;
import com.tinyme.domain.entries.model.query.EntryPage;
import com.tinyme.domain.entries.model.query.EntryFilters;
import com.tinyme.domain.entries.repository.EntryRepository;
import com.tinyme.domain.entries.service.EntryCursorCodec;
import com.tinyme.domain.entries.service.EntryService;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/entries")
public class EntryController {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() { };

    private final EntryService entries;

    public EntryController(EntryService entries) {
        this.entries = entries;
    }

    @GetMapping
    public EntryPageResponse list(@RequestParam(required = false) String kind,
                                  @RequestParam(required = false) LocalDate from,
                                  @RequestParam(required = false) LocalDate to,
                                  @RequestParam(required = false) String q,
                                  @RequestParam(required = false) String where,
                                  @RequestParam(required = false) String cursor,
                                  @RequestParam(required = false, defaultValue = "50") Integer limit) {
        if (limit == null || limit < 1 || limit > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "limit must be between 1 and 100");
        }
        Map<String, Object> whereMap = parseWhere(where);
        final EntryRepository.EntryCursor decoded;
        try {
            decoded = cursor == null || cursor.isBlank() ? null : EntryCursorCodec.decode(cursor);
        } catch (IllegalArgumentException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "cursor is invalid", invalid);
        }
        try {
            EntryFilters filters = new EntryFilters(kind, q, whereMap, from, to);
            EntryPage page = entries.page(filters, limit, decoded);
            return new EntryPageResponse(page.kindKnown(), page.entries().stream()
                    .map(EntryResponse::from).toList(), page.nextCursor());
        } catch (IllegalArgumentException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, invalid.getMessage(), invalid);
        }
    }

    private static Map<String, Object> parseWhere(String where) {
        if (where == null || where.isBlank()) return Map.of();
        try {
            var node = JSON.readTree(where);
            if (node == null || !node.isObject()) throw new IllegalArgumentException("where must be an object");
            return JSON.convertValue(node, MAP);
        } catch (RuntimeException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "where must be a JSON object", invalid);
        }
    }

    public record EntryPageResponse(@JsonProperty("kind_known") boolean kindKnown, List<EntryResponse> entries,
                                    @JsonProperty("next_cursor") String nextCursor) {
        public EntryPageResponse { entries = List.copyOf(entries); }
    }

    public record EntryResponse(String id, String ts, @JsonProperty("local_day") String localDay, String kind,
                                BigDecimal quantity, String text, Map<String, Object> data) {
        static EntryResponse from(EntrySnapshot entry) {
            return new EntryResponse(entry.id().toString(), entry.ts().truncatedTo(ChronoUnit.SECONDS).toString(),
                    entry.localDay().toString(), entry.kind(),
                    entry.quantity(), entry.text(), new LinkedHashMap<>(entry.data()));
        }
    }
}
