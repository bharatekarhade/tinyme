package com.tinyme.api;

import com.tinyme.domain.entries.model.aggregate.Range;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Locale;

/** Parses the shared Home range query parameter into the domain enum. */
public final class RangeParam {
    private RangeParam() { }

    public static Range parse(String value) {
        try {
            return Range.valueOf(value.strip().toUpperCase(Locale.ROOT));
        } catch (RuntimeException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "range must be day, week, month, or year", invalid);
        }
    }
}
