package com.tinyme.tools.handlers;

import com.tinyme.domain.places.model.NearbyPlace;
import com.tinyme.domain.places.model.PlaceSnapshot;

import java.util.LinkedHashMap;
import java.util.Map;

final class PlaceToolOutputs {
    private PlaceToolOutputs() { }

    static Map<String, Object> place(PlaceSnapshot place) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", place.id().toString());
        out.put("name", place.name());
        out.put("address", place.address());
        out.put("area", area(place.address()));
        out.put("note", place.note());
        out.put("category", place.category());
        out.put("url", place.url());
        out.put("visited", place.visited());
        return out;
    }

    static Map<String, Object> nearby(NearbyPlace place) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", place.id().toString());
        out.put("name", place.name());
        out.put("distance_m", Math.round(place.distanceM()));
        out.put("note", place.note());
        out.put("visited", place.visitedAt() != null);
        return out;
    }

    static Map<String, Object> candidate(String candidateId, String name, String address, String category,
                                         Double distanceM) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("candidate_id", candidateId);
        out.put("name", name);
        out.put("address", address);
        out.put("category", category);
        if (distanceM != null) out.put("distance_m", Math.round(distanceM));
        return out;
    }

    static Map<String, Object> candidateReference(Map<String, Object> candidate, Double latitude, Double longitude,
                                                  String url) {
        Map<String, Object> out = new LinkedHashMap<>(candidate);
        if (latitude != null) out.put("lat", latitude);
        if (longitude != null) out.put("lng", longitude);
        if (url != null) out.put("url", url);
        return out;
    }

    private static String area(String address) {
        if (address == null || address.isBlank()) return null;
        String[] parts = address.split(",");
        return parts[parts.length - 1].strip();
    }
}
