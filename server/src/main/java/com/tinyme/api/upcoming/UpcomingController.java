package com.tinyme.api.upcoming;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;

@RestController
@RequestMapping("/upcoming")
public class UpcomingController {
    private final UpcomingService upcoming;

    public UpcomingController(UpcomingService upcoming) {
        this.upcoming = upcoming;
    }

    @GetMapping
    public UpcomingResponse get(@RequestParam(required = false) LocalDate from,
                                @RequestParam(required = false) LocalDate to) {
        try {
            return upcoming.get(from, to);
        } catch (IllegalArgumentException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, invalid.getMessage(), invalid);
        }
    }
}
