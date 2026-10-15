import CoreLocation
import Foundation
import MapKit

struct PlacesSearchRegion: Sendable {
    let latitude: Double
    let longitude: Double
    let latitudeDelta: Double
    let longitudeDelta: Double

    static func around(_ coordinate: CLLocationCoordinate2D, radiusMeters: Double) -> Self {
        let latitudeDelta = radiusMeters * 2 / 111_000
        let longitudeScale = max(0.15, abs(cos(coordinate.latitude * .pi / 180)))
        return Self(latitude: coordinate.latitude, longitude: coordinate.longitude,
                    latitudeDelta: latitudeDelta, longitudeDelta: latitudeDelta / longitudeScale)
    }

    var mapKit: MKCoordinateRegion {
        MKCoordinateRegion(center: CLLocationCoordinate2D(latitude: latitude, longitude: longitude),
                           span: MKCoordinateSpan(latitudeDelta: latitudeDelta, longitudeDelta: longitudeDelta))
    }
}

struct PlaceSearchHit: Sendable, Equatable {
    let name: String
    let address: String?
    let latitude: Double
    let longitude: Double
    let category: String?
    let url: String?
    let providerPlaceId: String?
    let distanceMeters: Double?

    var json: JSONValue {
        var object: [String: JSONValue] = [
            "name": .string(name), "lat": .number(latitude), "lng": .number(longitude)
        ]
        object["address"] = address.map(JSONValue.string) ?? .null
        object["category"] = category.map(JSONValue.string) ?? .null
        object["url"] = url.map(JSONValue.string) ?? .null
        object["provider_place_id"] = providerPlaceId.map(JSONValue.string) ?? .null
        object["distance_m"] = distanceMeters.map(JSONValue.number) ?? .null
        return .object(object)
    }

    static func map(_ item: MKMapItem, from origin: CLLocationCoordinate2D?) -> Self {
        let placemark = item.placemark
        let coordinate = placemark.coordinate
        let address = [placemark.subThoroughfare, placemark.thoroughfare, placemark.locality,
                       placemark.administrativeArea, placemark.postalCode]
            .compactMap { $0?.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty }
            .joined(separator: " ")
        let distance = origin.map {
            CLLocation(latitude: $0.latitude, longitude: $0.longitude)
                .distance(from: CLLocation(latitude: coordinate.latitude, longitude: coordinate.longitude))
        }
        return Self(name: item.name ?? placemark.name ?? "Unknown place",
                    address: address.isEmpty ? nil : address,
                    latitude: coordinate.latitude, longitude: coordinate.longitude,
                    category: item.pointOfInterestCategory?.rawValue,
                    url: item.url?.absoluteString,
                    providerPlaceId: item.identifier?.rawValue,
                    distanceMeters: distance)
    }
}

protocol PlacesSearchClient: Sendable {
    func search(query: String, region: PlacesSearchRegion, addressesOnly: Bool) async throws -> [PlaceSearchHit]
}

struct MapKitPlacesSearchClient: PlacesSearchClient {
    func search(query: String, region: PlacesSearchRegion, addressesOnly: Bool) async throws -> [PlaceSearchHit] {
        let request = MKLocalSearch.Request()
        request.naturalLanguageQuery = query
        request.region = region.mapKit
        request.resultTypes = addressesOnly ? .address : .pointOfInterest
        let response = try await MKLocalSearch(request: request).start()
        return response.mapItems.map { PlaceSearchHit.map($0, from: nil) }
    }
}

struct PlacesSearchTool: DeviceTool {
    let name = "places_search"
    private let locationProvider: LocationProvider
    private let searchClient: any PlacesSearchClient

    init(locationProvider: LocationProvider, searchClient: any PlacesSearchClient = MapKitPlacesSearchClient()) {
        self.locationProvider = locationProvider
        self.searchClient = searchClient
    }

    func run(_ input: JSONValue) async throws -> JSONValue {
        guard case let .object(fields) = input,
              case let .string(rawQuery)? = fields["query"] else {
            throw PlacesSearchError.invalidInput
        }
        if let rawNear = fields["near"], rawNear.stringValue == nil {
            throw PlacesSearchError.invalidInput
        }
        let query = rawQuery.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !query.isEmpty, query.count <= 200 else { throw PlacesSearchError.invalidInput }
        let near: String? = fields["near"]?.stringValue?.trimmingCharacters(in: .whitespacesAndNewlines)
        let results = try await withTimeout(.seconds(15)) {
            try await self.search(query: query, near: near)
        }
        return .array(results.map(\.json))
    }

    private func search(query: String, near: String?) async throws -> [PlaceSearchHit] {
        let origin: CLLocationCoordinate2D?
        let region: PlacesSearchRegion
        let searchQuery: String
        if near?.localizedCaseInsensitiveCompare("here") == .orderedSame {
            guard let location = await locationProvider.currentLocation(requestPermission: true) else {
                throw PlacesSearchError.locationUnavailable
            }
            origin = location.coordinate
            region = .around(location.coordinate, radiusMeters: 5_000)
            searchQuery = query
        } else if let near, !near.isEmpty {
            if let coordinate = await locationProvider.coordinate(for: near) {
                origin = coordinate
                region = .around(coordinate, radiusMeters: 10_000)
                searchQuery = query
            } else {
                // Geocoding is brittle for misspelled neighborhoods. Let MapKit interpret the
                // complete phrase in the user's locale instead of surfacing a dead-end error.
                origin = nil
                region = Self.localeRegion
                searchQuery = "\(query) near \(near)"
            }
        } else {
            origin = nil
            region = Self.localeRegion
            searchQuery = query
        }

        let found = try await searchClient.search(query: searchQuery, region: region, addressesOnly: false)
        let points = found.isEmpty
            ? try await searchClient.search(query: searchQuery, region: region, addressesOnly: true)
            : found
        let results = points.prefix(5).map { hit in
            guard let origin, hit.distanceMeters == nil else { return hit }
            let distance = CLLocation(latitude: origin.latitude, longitude: origin.longitude)
                .distance(from: CLLocation(latitude: hit.latitude, longitude: hit.longitude))
            return PlaceSearchHit(name: hit.name, address: hit.address, latitude: hit.latitude,
                                  longitude: hit.longitude, category: hit.category, url: hit.url,
                                  providerPlaceId: hit.providerPlaceId, distanceMeters: distance)
        }
        return Array(results)
    }

    private static var localeRegion: PlacesSearchRegion {
        if Locale.current.region?.identifier == "JP" {
            return PlacesSearchRegion(latitude: 36.2048, longitude: 138.2529,
                                      latitudeDelta: 14, longitudeDelta: 24)
        }
        return PlacesSearchRegion(latitude: 20, longitude: 0, latitudeDelta: 120, longitudeDelta: 300)
    }

    private func withTimeout<T: Sendable>(_ duration: Duration,
                                           operation: @escaping @Sendable () async throws -> T) async throws -> T {
        try await withThrowingTaskGroup(of: T.self) { group in
            group.addTask { try await operation() }
            group.addTask {
                try await Task.sleep(for: duration)
                throw PlacesSearchError.timedOut
            }
            defer { group.cancelAll() }
            guard let first = try await group.next() else { throw PlacesSearchError.timedOut }
            return first
        }
    }
}

enum PlacesSearchError: LocalizedError {
    case invalidInput
    case locationUnavailable
    case timedOut

    var code: String {
        switch self {
        case .locationUnavailable: "no_location"
        case .invalidInput, .timedOut: "device_error"
        }
    }

    var errorDescription: String? {
        switch self {
        case .invalidInput: "Search needs a query of 1–200 characters."
        case .locationUnavailable: "Location is unavailable. Allow TinyMe to use your location, or name an area."
        case .timedOut: "Map search took too long. Try again."
        }
    }
}
