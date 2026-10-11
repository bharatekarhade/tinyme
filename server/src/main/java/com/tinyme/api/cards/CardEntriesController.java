package com.tinyme.api.cards;

import com.tinyme.api.RangeParam;
import com.tinyme.domain.entries.model.aggregate.Range;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.UUID;

@RestController
@RequestMapping("/cards/{id}/entries")
public class CardEntriesController {
    private final CardEntriesService entries;

    public CardEntriesController(CardEntriesService entries) {
        this.entries = entries;
    }

    @GetMapping
    public CardEntriesService.CardEntriesResponse get(@PathVariable UUID id,
                                                       @RequestParam(defaultValue = "week") String range,
                                                       @RequestParam(required = false) Integer block,
                                                       @RequestParam(required = false) Integer item,
                                                       @RequestParam(required = false) LocalDate day,
                                                       @RequestParam(required = false) String key,
                                                       @RequestParam(required = false) String cursor,
                                                       @RequestParam(defaultValue = "50") Integer limit) {
        try {
            Range parsedRange = RangeParam.parse(range);
            return entries.get(id, parsedRange, block, item, day, key, cursor, limit)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Card not found"));
        } catch (IllegalArgumentException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, invalid.getMessage(), invalid);
        }
    }
}
