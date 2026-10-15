import SwiftUI
import MapKit

/// The top of a place screen: the pin on a map that stays put, Apple's street-level photos when it has them,
/// and Apple Maps' place card (photos, ratings, reviews, hours) one tap away.
struct PlaceInfoSection: View {
    let name: String
    let coordinate: CLLocationCoordinate2D

    @State private var mapItem: MKMapItem?
    @State private var lookAround: MKLookAroundScene?
    @State private var lookedUp = false
    @State private var card: MKMapItem?

    var body: some View {
        VStack(spacing: Space.s3) {
            // No panning or zooming: the map only shows where the place is. Tapping it opens the place card.
            Map(initialPosition: .region(MKCoordinateRegion(center: coordinate, span: MKCoordinateSpan(latitudeDelta: 0.008, longitudeDelta: 0.008))),
                interactionModes: []) {
                Marker(name, coordinate: coordinate)
            }
            .frame(height: 180)
            .clipShape(RoundedRectangle(cornerRadius: Radius.lg, style: .continuous))
            .contentShape(Rectangle())
            .onTapGesture { card = mapItem }
            .accessibilityLabel("Map showing \(name)")

            if let lookAround {
                LookAroundPreview(initialScene: lookAround, badgePosition: .bottomTrailing)
                    .frame(height: 170)
                    .clipShape(RoundedRectangle(cornerRadius: Radius.lg, style: .continuous))
            }

            Button { card = mapItem } label: {
                HStack(spacing: Space.s2) {
                    if !lookedUp {
                        ProgressView().tint(Palette.inkMuted)
                    } else {
                        Image(systemName: "photo.on.rectangle.angled")
                    }
                    Text(buttonTitle)
                }
                .textStyle(.label, weight: .semibold)
                .frame(maxWidth: .infinity)
                .frame(height: 48)
                .background(Palette.surfaceRaised, in: RoundedRectangle(cornerRadius: Radius.md, style: .continuous))
            }
            .buttonStyle(.plain)
            .foregroundStyle(mapItem == nil ? Palette.inkMuted : Palette.ink)
            .disabled(mapItem == nil)
        }
        .mapItemDetailSheet(item: $card, displaysMap: false)
        .task(id: "\(name)|\(coordinate.latitude)|\(coordinate.longitude)") { await lookUp() }
    }

    private var buttonTitle: String {
        if !lookedUp { return "Finding photos and reviews…" }
        return mapItem == nil ? "Apple Maps has no details for this place" : "Photos, reviews and hours"
    }

    /// Finds the same place in Apple Maps: a search for its name near the pin, taking the closest hit.
    private func lookUp() async {
        let request = MKLocalSearch.Request()
        request.naturalLanguageQuery = name
        request.region = MKCoordinateRegion(center: coordinate, latitudinalMeters: 2_000, longitudinalMeters: 2_000)
        request.resultTypes = .pointOfInterest
        let pin = CLLocation(latitude: coordinate.latitude, longitude: coordinate.longitude)
        let items = (try? await MKLocalSearch(request: request).start().mapItems) ?? []
        let closest = items
            .map { item in (item, pin.distance(from: CLLocation(latitude: item.placemark.coordinate.latitude,
                                                                  longitude: item.placemark.coordinate.longitude))) }
            .filter { $0.1 < 250 }
            .min { $0.1 < $1.1 }?.0
        mapItem = closest
        if let closest {
            lookAround = try? await MKLookAroundSceneRequest(mapItem: closest).scene
        } else {
            lookAround = try? await MKLookAroundSceneRequest(coordinate: coordinate).scene
        }
        lookedUp = true
    }
}
