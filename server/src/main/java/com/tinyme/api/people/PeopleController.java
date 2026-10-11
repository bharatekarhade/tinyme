package com.tinyme.api.people;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Objects;

@RestController
@RequestMapping("/people")
public class PeopleController {
    private final PersonDetailService people;

    public PeopleController(PersonDetailService people) {
        this.people = Objects.requireNonNull(people, "people");
    }

    @GetMapping
    public List<PersonResponse> list() {
        return people.list();
    }

    @GetMapping("/{slug}")
    public PersonDetailResponse get(@PathVariable String slug) {
        return people.get(slug)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Person not found"));
    }
}
