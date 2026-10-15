package com.tinyme.domain.places.model;

import java.time.Instant;
import java.util.UUID;

/** Live place row with the computed distance for a nearby lookup. */
public record NearbyPlace(UUID id, String name, String address, double lat, double lng, String category, String url,
                          String note, PlaceSource source, Instant visitedAt, Instant createdAt, double distanceM) { }
