import Foundation
import Observation
import CoreLocation

struct PlaceDTO: Codable, Sendable, Hashable, Identifiable {
    let id: String
    let name: String
    let address: String?
    let area: String?
    let lat: Double
    let lng: Double
    let category: String?
    let url: String?
    let note: String?
    let visitedAt: Date?
    let createdAt: Date
    let distanceM: Double?

    var visited: Bool { visitedAt != nil }

    private enum CodingKeys: String, CodingKey {
        case id, name, address, area, lat, lng, category, url, note
        case visitedAt = "visited_at"
        case createdAt = "created_at"
        case distanceM = "distance_m"
    }
}

@MainActor
@Observable
final class PlacesModel {
    enum Phase: Equatable {
        case loading
        case loaded
        case failed(String)
    }

    private let api: APIClient
    private(set) var places: [PlaceDTO] = []
    private(set) var phase: Phase = .loading
    private(set) var isUpdating = false
    /// The last search and position, kept so every reload stays filtered and sorted by distance.
    private var query = ""
    private var near: CLLocationCoordinate2D?

    init(api: APIClient) { self.api = api }

    /// A query or position left out keeps the last one.
    func load(query: String? = nil, visited: Bool? = nil, near: CLLocationCoordinate2D? = nil) async {
        if let query { self.query = query }
        if let near { self.near = near }
        phase = places.isEmpty ? .loading : phase
        do {
            places = try await api.get(.places(query: self.query, visited: visited, near: self.near))
            phase = .loaded
        } catch {
            if places.isEmpty { phase = .failed(Self.message(error)) }
        }
    }

    func place(id: String) async throws -> PlaceDTO {
        try await api.get(.place(id))
    }

    func setVisited(_ place: PlaceDTO, _ visited: Bool) async {
        await patch(place, body: PlacePatch(visited: visited))
    }

    func setNote(_ place: PlaceDTO, _ note: String?) async {
        await patch(place, body: PlacePatch(note: note))
    }

    func remove(_ place: PlaceDTO) async throws {
        try await api.send(.deletePlace(place.id))
        places.removeAll { $0.id == place.id }
    }

    private func patch(_ place: PlaceDTO, body: PlacePatch) async {
        isUpdating = true
        defer { isUpdating = false }
        do {
            let updated: PlaceDTO = try await api.send(.updatePlace(place.id), body: body)
            if let index = places.firstIndex(where: { $0.id == updated.id }) { places[index] = updated }
        } catch { }
    }

    private static func message(_ error: Error) -> String {
        switch error {
        case APIError.server(_, _, let detail?): detail
        case is URLError: "Can't reach your server."
        default: "Something went wrong."
        }
    }
}

private struct PlacePatch: Encodable, Sendable {
    var note: String?
    var visited: Bool?
}
