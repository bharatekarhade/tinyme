package com.tinyme.domain.places.model;

/** A place result returned by a device search; coordinates are supplied by the device, never the model. */
public record PlaceCandidate(String name, String address, String area, double lat, double lng, String category,
                             String url, String providerPlaceId, Double distanceM) {
    public PlaceCandidate {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("name is required");
        if (name.length() > 200) throw new IllegalArgumentException("name must be at most 200 characters");
        if (!Double.isFinite(lat) || lat < -90 || lat > 90) throw new IllegalArgumentException("lat must be between -90 and 90");
        if (!Double.isFinite(lng) || lng < -180 || lng > 180) throw new IllegalArgumentException("lng must be between -180 and 180");
        if (distanceM != null && (!Double.isFinite(distanceM) || distanceM < 0)) {
            throw new IllegalArgumentException("distanceM must not be negative");
        }
    }
}
