package com.tinyme.api.places;

import com.tinyme.domain.places.service.PlaceService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/places")
public class PlaceController {
    private final PlaceService places;
    private final Clock clock;

    public PlaceController(PlaceService places, Clock clock) {
        this.places = places;
        this.clock = clock;
    }

    @GetMapping
    public List<PlaceResponse> list(@RequestParam(required = false) String q,
                                    @RequestParam(required = false) Boolean visited,
                                    @RequestParam(name = "near_lat", required = false) Double nearLat,
                                    @RequestParam(name = "near_lng", required = false) Double nearLng) {
        try {
            return places.listForApp(q, visited, nearLat, nearLng).stream().map(PlaceResponse::from).toList();
        } catch (IllegalArgumentException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, invalid.getMessage(), invalid);
        }
    }

    @GetMapping("/watch")
    public List<PlaceResponse> watch(@RequestParam double lat, @RequestParam double lng) {
        try {
            return places.watch(lat, lng).stream().map(PlaceResponse::from).toList();
        } catch (IllegalArgumentException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, invalid.getMessage(), invalid);
        }
    }

    @GetMapping("/{id}")
    public PlaceResponse get(@PathVariable UUID id) {
        return places.get(id).map(PlaceResponse::from).orElseThrow(() -> notFound("Place not found"));
    }

    @PatchMapping("/{id}")
    public PlaceResponse update(@PathVariable UUID id, @RequestBody JsonNode body) {
        if (body == null || !body.isObject()) throw badRequest("body must be an object");
        String note = null;
        Boolean visited = null;
        if (body.has("note")) {
            JsonNode value = body.get("note");
            if (!value.isNull() && !value.isString()) throw badRequest("note must be a string or null");
            note = value.isNull() ? null : value.stringValue();
        }
        if (body.has("visited")) {
            JsonNode value = body.get("visited");
            if (!value.isBoolean()) throw badRequest("visited must be a boolean");
            visited = value.booleanValue();
        }
        try {
            return places.update(id, body.has("note"), note, visited, clock.instant())
                    .map(PlaceResponse::from).orElseThrow(() -> notFound("Place not found"));
        } catch (IllegalArgumentException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, invalid.getMessage(), invalid);
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        if (places.remove(id, clock.instant()).isEmpty()) throw notFound("Place not found");
        return ResponseEntity.noContent().build();
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private static ResponseStatusException notFound(String message) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
    }
}
