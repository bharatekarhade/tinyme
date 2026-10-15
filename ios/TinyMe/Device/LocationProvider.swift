import CoreLocation
import Foundation

/// One-shot, foreground location access for chat and device tools. Coordinates stay on the phone
/// except for the validated `client_context.location` sent to TinyMe's server.
@MainActor
final class LocationProvider: NSObject, @preconcurrency CLLocationManagerDelegate {
    private let manager = CLLocationManager()
    private let geocoder = CLGeocoder()
    private var locationWaiter: (id: UUID, continuation: CheckedContinuation<CLLocation?, Never>)?
    private var authorizationWaiter: (id: UUID, continuation: CheckedContinuation<CLAuthorizationStatus, Never>)?
    private var cachedReverseArea: (coordinate: CLLocationCoordinate2D, at: Date, name: String)?
    private var cachedGeocode: (coordinate: CLLocationCoordinate2D, at: Date, query: String)?

    override init() {
        super.init()
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyHundredMeters
    }

    /// Adds a location only when permission is already granted. Chat does not trigger a surprise prompt.
    func locationForChatContext() async -> OutgoingMessage.ClientContext.Location? {
        guard Self.isAuthorized(manager.authorizationStatus),
              let location = await currentLocation(requestPermission: false),
              Self.isUsable(location, now: .now),
              let area = await areaName(for: location), !area.isEmpty else {
            return nil
        }
        return OutgoingMessage.ClientContext.Location(
            lat: location.coordinate.latitude,
            lng: location.coordinate.longitude,
            accuracyM: location.horizontalAccuracy,
            at: location.timestamp.formatted(Date.ISO8601FormatStyle(includingFractionalSeconds: true)),
            area: area
        )
    }

    /// Used only when the user asks to search "here". The system prompt is then tied to that request.
    func currentLocation(requestPermission: Bool) async -> CLLocation? {
        var status = manager.authorizationStatus
        if status == .notDetermined, requestPermission {
            status = await requestWhenInUsePermission()
        }
        guard Self.isAuthorized(status) else { return nil }
        if let last = manager.location, Self.isUsable(last, now: .now) { return last }

        let id = UUID()
        return await withCheckedContinuation { (continuation: CheckedContinuation<CLLocation?, Never>) in
            locationWaiter = (id, continuation)
            manager.requestLocation()
            Task { [weak self] in
                try? await Task.sleep(for: .seconds(8))
                self?.finishLocation(id: id, value: nil)
            }
        }
    }

    func coordinate(for area: String) async -> CLLocationCoordinate2D? {
        let area = area.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !area.isEmpty else { return nil }
        if let cachedGeocode, Date.now.timeIntervalSince(cachedGeocode.at) < 15 * 60,
           cachedGeocode.query.localizedCaseInsensitiveCompare(area) == .orderedSame {
            return cachedGeocode.coordinate
        }
        guard let placemark = try? await geocoder.geocodeAddressString(area).first,
              let coordinate = placemark.location?.coordinate else { return nil }
        cachedGeocode = (coordinate, .now, area)
        return coordinate
    }

    nonisolated static func isUsable(_ location: CLLocation, now: Date) -> Bool {
        let age = now.timeIntervalSince(location.timestamp)
        return CLLocationCoordinate2DIsValid(location.coordinate)
            && location.horizontalAccuracy >= 0
            && location.horizontalAccuracy < 500
            && age >= 0
            && age < 10 * 60
    }

    private func requestWhenInUsePermission() async -> CLAuthorizationStatus {
        let id = UUID()
        return await withCheckedContinuation { (continuation: CheckedContinuation<CLAuthorizationStatus, Never>) in
            authorizationWaiter = (id, continuation)
            manager.requestWhenInUseAuthorization()
            Task { [weak self] in
                try? await Task.sleep(for: .seconds(30))
                self?.finishAuthorization(id: id, status: self?.manager.authorizationStatus ?? .denied)
            }
        }
    }

    private func areaName(for location: CLLocation) async -> String? {
        if let cachedReverseArea, Date.now.timeIntervalSince(cachedReverseArea.at) < 15 * 60,
           CLLocation(latitude: cachedReverseArea.coordinate.latitude, longitude: cachedReverseArea.coordinate.longitude)
            .distance(from: location) < 1000 {
            return cachedReverseArea.name
        }
        guard let placemark = try? await geocoder.reverseGeocodeLocation(location).first else { return nil }
        let locality = placemark.locality
        let neighborhood = placemark.subLocality
        let region = placemark.administrativeArea
        let pieces = [neighborhood, locality, region].compactMap { $0?.trimmingCharacters(in: .whitespacesAndNewlines) }
        var unique: [String] = []
        for piece in pieces where !piece.isEmpty && !unique.contains(where: { $0.localizedCaseInsensitiveCompare(piece) == .orderedSame }) {
            unique.append(piece)
        }
        guard !unique.isEmpty else { return nil }
        let name = unique.joined(separator: ", ")
        cachedReverseArea = (location.coordinate, .now, name)
        return name
    }

    private func finishLocation(id: UUID, value: CLLocation?) {
        guard let waiter = locationWaiter, waiter.id == id else { return }
        locationWaiter = nil
        waiter.continuation.resume(returning: value.flatMap { Self.isUsable($0, now: .now) ? $0 : nil })
    }

    private func finishAuthorization(id: UUID, status: CLAuthorizationStatus) {
        guard let waiter = authorizationWaiter, waiter.id == id else { return }
        authorizationWaiter = nil
        waiter.continuation.resume(returning: status)
    }

    private static func isAuthorized(_ status: CLAuthorizationStatus) -> Bool {
        status == .authorizedAlways || status == .authorizedWhenInUse
    }

    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        guard let waiter = authorizationWaiter else { return }
        let status = manager.authorizationStatus
        guard status != .notDetermined else { return }
        finishAuthorization(id: waiter.id, status: status)
    }

    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        finishLocation(id: locationWaiter?.id ?? UUID(), value: locations.last)
    }

    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        finishLocation(id: locationWaiter?.id ?? UUID(), value: nil)
    }
}
