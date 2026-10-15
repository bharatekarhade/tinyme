import CoreLocation
import Foundation
import MapKit
import Testing
@testable import TinyMe

struct PlacesSearchToolTests {
    @Test @MainActor func searchTrimsQueryAndReturnsAtMostFiveCandidates() async throws {
        let hits = (0..<7).map { index in
            PlaceSearchHit(name: "Cafe \(index)", address: nil, latitude: 35.6 + Double(index) / 1000,
                           longitude: 139.7, category: "cafe", url: nil,
                           providerPlaceId: "place-\(index)", distanceMeters: nil)
        }
        let probe = SearchProbe()
        let tool = PlacesSearchTool(locationProvider: LocationProvider(),
                                    searchClient: FakePlacesSearchClient(hits: hits, probe: probe))

        let result = try await tool.run(.object(["query": .string("  coffee  ")]))
        guard case .array(let candidates) = result else {
            Issue.record("Expected candidate array")
            return
        }
        #expect(candidates.count == 5)
        #expect(await probe.query() == "coffee")
        #expect(await probe.addressesOnly() == false)
        #expect(candidates[0]["provider_place_id"]?.stringValue == "place-0")
    }

    @Test func mapKitItemBecomesAStoredCandidateShape() {
        let coordinate = CLLocationCoordinate2D(latitude: 35.6595, longitude: 139.7005)
        let item = MKMapItem(placemark: MKPlacemark(coordinate: coordinate))
        item.name = "Blue Bottle Coffee"
        item.url = URL(string: "https://example.test/blue-bottle")
        let hit = PlaceSearchHit.map(item, from: CLLocationCoordinate2D(latitude: 35.66, longitude: 139.7))

        #expect(hit.name == "Blue Bottle Coffee")
        #expect(hit.latitude == coordinate.latitude)
        #expect(hit.longitude == coordinate.longitude)
        #expect(hit.url == "https://example.test/blue-bottle")
        #expect(hit.distanceMeters != nil)
        #expect(hit.json["lat"]?.numberValue == coordinate.latitude)
        #expect(hit.json["lng"]?.numberValue == coordinate.longitude)
    }

    @Test func clientContextLocationIsNestedAndUsesSnakeCase() throws {
        let location = OutgoingMessage.ClientContext.Location(lat: 35.6595, lng: 139.7005,
                                                              accuracyM: 35, at: "2026-10-15T02:31:10Z",
                                                              area: "Shibuya, Tokyo")
        let message = OutgoingMessage(clientMsgId: UUID(), text: "Hi",
                                      clientContext: .init(location: location))
        let encoded = try JSONSerialization.jsonObject(with: JSONEncoder().encode(message)) as? [String: Any]
        let context = encoded?["client_context"] as? [String: Any]
        let sentLocation = context?["location"] as? [String: Any]

        #expect(sentLocation?["lat"] as? Double == 35.6595)
        #expect(sentLocation?["lng"] as? Double == 139.7005)
        #expect(sentLocation?["accuracy_m"] as? Double == 35)
        #expect(sentLocation?["area"] as? String == "Shibuya, Tokyo")
        #expect(sentLocation?["at"] as? String == "2026-10-15T02:31:10Z")
    }

    @Test func contextLocationRejectsStaleAndInaccurateFixes() {
        let now = Date(timeIntervalSince1970: 1_797_000_000)
        let good = CLLocation(coordinate: CLLocationCoordinate2D(latitude: 35.6, longitude: 139.7),
                              altitude: 0, horizontalAccuracy: 35, verticalAccuracy: 10,
                              timestamp: now.addingTimeInterval(-60))
        let stale = CLLocation(coordinate: good.coordinate, altitude: 0, horizontalAccuracy: 35,
                               verticalAccuracy: 10, timestamp: now.addingTimeInterval(-601))
        let inaccurate = CLLocation(coordinate: good.coordinate, altitude: 0, horizontalAccuracy: 501,
                                    verticalAccuracy: 10, timestamp: now)

        #expect(LocationProvider.isUsable(good, now: now))
        #expect(!LocationProvider.isUsable(stale, now: now))
        #expect(!LocationProvider.isUsable(inaccurate, now: now))
    }
}

private actor SearchProbe {
    private(set) var lastQuery: String?
    private(set) var lastAddressesOnly: Bool?

    func record(query: String, addressesOnly: Bool) {
        lastQuery = query
        lastAddressesOnly = addressesOnly
    }

    func query() -> String? { lastQuery }
    func addressesOnly() -> Bool? { lastAddressesOnly }
}

private struct FakePlacesSearchClient: PlacesSearchClient {
    let hits: [PlaceSearchHit]
    let probe: SearchProbe

    func search(query: String, region: PlacesSearchRegion, addressesOnly: Bool) async throws -> [PlaceSearchHit] {
        await probe.record(query: query, addressesOnly: addressesOnly)
        return addressesOnly ? [] : hits
    }
}
