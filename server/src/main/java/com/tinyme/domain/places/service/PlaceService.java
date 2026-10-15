package com.tinyme.domain.places.service;

import com.tinyme.domain.places.entity.PlaceEntity;
import com.tinyme.domain.places.model.NearbyPlace;
import com.tinyme.domain.places.model.PlaceCandidate;
import com.tinyme.domain.places.model.PlaceSaveResult;
import com.tinyme.domain.places.model.PlaceSnapshot;
import com.tinyme.domain.places.model.PlaceSource;
import com.tinyme.domain.places.repository.PlaceCandidateRepository;
import com.tinyme.domain.places.repository.PlaceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
public class PlaceService {
    public static final int MAX_NOTE_LENGTH = 300;
    public static final int MAX_LIST_RESULTS = 30;
    public static final int MAX_NEAR_RESULTS = 10;

    private final PlaceRepository places;
    private final PlaceCandidateRepository candidates;

    public PlaceService(PlaceRepository places, PlaceCandidateRepository candidates) {
        this.places = Objects.requireNonNull(places, "places");
        this.candidates = Objects.requireNonNull(candidates, "candidates");
    }

    @Transactional
    public PlaceSaveResult saveCandidate(String candidateId, String note, Instant now) {
        CandidateRef ref = parseCandidateId(candidateId);
        String cleanNote = cleanNote(note);
        JsonNode json = candidates.find(ref.actionId(), ref.index(), now)
                .orElseThrow(() -> new CandidateNotFoundException(candidateId));
        PlaceCandidate candidate = candidate(json);

        Optional<PlaceEntity> duplicate = places.findByProviderId(candidate.providerPlaceId())
                .or(() -> places.findSameNameWithin(candidate.name(), candidate.lat(), candidate.lng(), 50));
        if (duplicate.isPresent()) {
            PlaceEntity existing = duplicate.get();
            if (cleanNote != null) {
                String previous = existing.getNote();
                existing.updateNote(previous == null || previous.isBlank()
                        ? cleanNote : previous + " · also: " + cleanNote);
            }
            return new PlaceSaveResult(PlaceSnapshot.from(existing), true);
        }

        PlaceEntity created = new PlaceEntity(candidate.name(), candidate.address(), candidate.lat(), candidate.lng(),
                cleanNote, candidate.providerPlaceId(), candidate.category(), candidate.url(), PlaceSource.APP);
        return new PlaceSaveResult(PlaceSnapshot.from(places.save(created)), false);
    }

    @Transactional(readOnly = true)
    public List<PlaceSnapshot> list(String query, boolean includeVisited) {
        String q = query == null || query.isBlank() ? null : query.strip().toLowerCase(Locale.ROOT);
        if (q != null && q.length() > 120) throw new IllegalArgumentException("q must be at most 120 characters");
        return places.listLive(includeVisited).stream()
                .filter(place -> q == null || contains(place.getName(), q) || contains(place.getAddress(), q)
                        || contains(place.getNote(), q) || contains(place.getCategory(), q))
                .limit(MAX_LIST_RESULTS)
                .map(PlaceSnapshot::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<NearbyPlace> near(double lat, double lng, int radiusM) {
        if (radiusM < 50 || radiusM > 20_000) {
            throw new IllegalArgumentException("radius_m must be between 50 and 20000");
        }
        return places.near(lat, lng, radiusM, MAX_NEAR_RESULTS);
    }

    @Transactional
    public Optional<PlaceSnapshot> update(UUID id, boolean noteProvided, String note, Boolean visited, Instant now) {
        if (!noteProvided && visited == null) throw new IllegalArgumentException("provide note or visited");
        String cleanNote = noteProvided ? cleanNote(note) : null;
        return places.findLive(id).map(place -> {
            if (noteProvided) place.updateNote(cleanNote);
            if (visited != null) {
                if (visited) place.markVisited(now);
                else place.unmarkVisited();
            }
            return PlaceSnapshot.from(place);
        });
    }

    @Transactional
    public Optional<PlaceSnapshot> remove(UUID id, Instant now) {
        return places.findLive(id).map(place -> {
            place.softDelete(now);
            return PlaceSnapshot.from(place);
        });
    }

    private static boolean contains(String value, String q) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(q);
    }

    private static String cleanNote(String note) {
        if (note == null || note.isBlank()) return null;
        String clean = note.strip();
        if (clean.length() > MAX_NOTE_LENGTH) {
            throw new IllegalArgumentException("note must be at most %d characters".formatted(MAX_NOTE_LENGTH));
        }
        return clean;
    }

    private static CandidateRef parseCandidateId(String candidateId) {
        if (candidateId == null) throw new CandidateNotFoundException("null");
        int separator = candidateId.lastIndexOf(':');
        if (separator < 1 || separator == candidateId.length() - 1) throw new CandidateNotFoundException(candidateId);
        try {
            UUID actionId = UUID.fromString(candidateId.substring(0, separator));
            int index = Integer.parseInt(candidateId.substring(separator + 1));
            if (index < 0 || index > 4) throw new NumberFormatException();
            return new CandidateRef(actionId, index);
        } catch (IllegalArgumentException invalid) {
            throw new CandidateNotFoundException(candidateId);
        }
    }

    private static PlaceCandidate candidate(JsonNode json) {
        if (json == null || !json.isObject()) throw new CandidateNotFoundException("stored candidate");
        return new PlaceCandidate(requiredText(json, "name"), optionalText(json, "address"),
                requiredNumber(json, "lat"), requiredNumber(json, "lng"), optionalText(json, "category"),
                optionalText(json, "url"), optionalText(json, "provider_place_id"), optionalNumber(json, "distance_m"));
    }

    private static String requiredText(JsonNode object, String key) {
        String value = optionalText(object, key);
        if (value == null || value.isBlank()) throw new CandidateNotFoundException("stored candidate");
        return value.strip();
    }

    private static String optionalText(JsonNode object, String key) {
        JsonNode value = object.get(key);
        return value == null || value.isNull() || !value.isString() ? null : value.stringValue();
    }

    private static double requiredNumber(JsonNode object, String key) {
        JsonNode value = object.get(key);
        if (value == null || !value.isNumber()) throw new CandidateNotFoundException("stored candidate");
        return value.doubleValue();
    }

    private static Double optionalNumber(JsonNode object, String key) {
        JsonNode value = object.get(key);
        return value == null || value.isNull() || !value.isNumber() ? null : value.doubleValue();
    }

    private record CandidateRef(UUID actionId, int index) { }
    public static final class CandidateNotFoundException extends RuntimeException {
        public CandidateNotFoundException(String candidateId) { super(candidateId); }
    }

}
