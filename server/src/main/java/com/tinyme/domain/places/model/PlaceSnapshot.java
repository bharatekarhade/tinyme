package com.tinyme.domain.places.model;

import com.tinyme.domain.places.entity.PlaceEntity;

import java.time.Instant;
import java.util.UUID;

public record PlaceSnapshot(UUID id, String name, String address, String note, String category, String url,
                            boolean visited, Instant createdAt) {
    public static PlaceSnapshot from(PlaceEntity place) {
        return new PlaceSnapshot(place.getId(), place.getName(), place.getAddress(), place.getNote(),
                place.getCategory(), place.getUrl(), place.getVisitedAt() != null, place.getCreatedAt());
    }
}
