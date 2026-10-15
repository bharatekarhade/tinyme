package com.tinyme.domain.places.model;

import java.util.Objects;

public record PlaceSaveResult(PlaceSnapshot place, boolean alreadySaved) {
    public PlaceSaveResult { Objects.requireNonNull(place, "place"); }
}
