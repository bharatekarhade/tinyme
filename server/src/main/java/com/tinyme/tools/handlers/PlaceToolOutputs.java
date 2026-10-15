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
        out.put("area", place.area());
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

    /** At most this many saved places come back as cards under a reply; the model still gets them all. */
    static final int MAX_CARDS = 10;

    /** A saved place as a card under the reply, for the phone only (it goes in ref, never to the model). */
    static Map<String, Object> savedCard(String id, String name, String detail, Double distanceM, boolean visited) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("place_id", id);
        out.put("name", name);
        if (detail != null && !detail.isBlank()) out.put("detail", detail);
        if (distanceM != null) out.put("distance_m", Math.round(distanceM));
        out.put("visited", visited);
        return out;
    }

    static Map<String, Object> candidate(String candidateId, String name, String address, String area,
                                         String category, Double distanceM) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("candidate_id", candidateId);
        out.put("name", name);
        out.put("address", address);
        if (area != null) out.put("area", area);
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

}
