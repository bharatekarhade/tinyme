package com.tinyme.domain.places.model;

import com.tinyme.domain.places.entity.PlaceEntity;

import java.time.Instant;
import java.util.UUID;

public record PlaceSnapshot(UUID id, String name, String address, String note, String category, String url,
                            boolean visited, Instant createdAt, double lat, double lng, Instant visitedAt,
                            Double distanceM) {
    public static PlaceSnapshot from(PlaceEntity place) {
        return new PlaceSnapshot(place.getId(), place.getName(), place.getAddress(), place.getNote(),
                place.getCategory(), place.getUrl(), place.getVisitedAt() != null, place.getCreatedAt(),
                place.getLat(), place.getLng(), place.getVisitedAt(), null);
    }

    public PlaceSnapshot withDistance(double distanceM) {
        return new PlaceSnapshot(id, name, address, note, category, url, visited, createdAt, lat, lng, visitedAt, distanceM);
    }
}
