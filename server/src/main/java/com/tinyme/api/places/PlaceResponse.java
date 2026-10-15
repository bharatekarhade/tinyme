package com.tinyme.api.places;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.tinyme.domain.places.model.PlaceSnapshot;

import java.time.Instant;
import java.util.UUID;

public record PlaceResponse(UUID id, String name, String address, String area, double lat, double lng,
                            String category, String url, String note,
                            @JsonProperty("visited_at") Instant visitedAt,
                            @JsonProperty("created_at") Instant createdAt,
                            @JsonProperty("distance_m") Long distanceM) {
    public static PlaceResponse from(PlaceSnapshot place) {
        return new PlaceResponse(place.id(), place.name(), place.address(), place.area(), place.lat(), place.lng(),
                place.category(), place.url(), place.note(), place.visitedAt(), place.createdAt(),
                place.distanceM() == null ? null : Math.round(place.distanceM()));
    }

}
