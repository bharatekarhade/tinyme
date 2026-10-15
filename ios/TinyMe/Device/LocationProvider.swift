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
    /// The fix and area `warmUp` found, ready for the next chat message.
    private var chatFix: (location: CLLocation, area: String)?
    private var isWarming = false

    override init() {
        super.init()
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyHundredMeters
    }

    /// Gets a fix and its area ready before anything is sent: when chat opens and when the app comes forward.
    /// Only with permission already granted; this never shows the prompt.
    func warmUp() {
        guard Self.isAuthorized(manager.authorizationStatus), !isWarming else { return }
        isWarming = true
        Task {
            defer { isWarming = false }
            guard let location = await currentLocation(requestPermission: false) else { return }
            // Coordinates remain useful even if Apple's reverse geocoder has no placemark or is offline.
            chatFix = (location, await areaName(for: location) ?? "Current location")
        }
    }

    /// The fix `warmUp` found, if it is still fresh. Never waits for GPS or the network, so sending isn't held up;
    /// a stale one starts a new warm-up for the next message.
    func cachedLocationForChatContext() -> OutgoingMessage.ClientContext.Location? {
        guard Self.isAuthorized(manager.authorizationStatus) else { return nil }
        guard let fix = chatFix, Self.isUsable(fix.location, now: .now) else {
            warmUp()
            return nil
        }
        return OutgoingMessage.ClientContext.Location(
            lat: fix.location.coordinate.latitude,
            lng: fix.location.coordinate.longitude,
            accuracyM: fix.location.horizontalAccuracy,
            at: fix.location.timestamp.formatted(Date.ISO8601FormatStyle(includingFractionalSeconds: true)),
            area: fix.area
        )
    }

    /// Requests the system prompt on first use. If access was denied earlier, the caller can open Settings.
    func requestAuthorization() async -> Bool {
        var status = manager.authorizationStatus
        if status == .notDetermined {
            status = await requestWhenInUsePermission()
        }
        return Self.isAuthorized(status)
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
        guard let placemark = try? await geocoder.reverseGeocodeLocation(location).first,
              let name = Self.areaName(of: placemark) else { return nil }
        cachedReverseArea = (location.coordinate, .now, name)
        return name
    }

    /// "Jingumae, Shibuya, Tokyo": neighbourhood, city and region, each once. Also names each place search hit.
    nonisolated static func areaName(of placemark: CLPlacemark) -> String? {
        let pieces = [placemark.subLocality, placemark.locality, placemark.administrativeArea]
            .compactMap { $0?.trimmingCharacters(in: .whitespacesAndNewlines) }
        var unique: [String] = []
        for piece in pieces where !piece.isEmpty && !unique.contains(where: { $0.localizedCaseInsensitiveCompare(piece) == .orderedSame }) {
            unique.append(piece)
        }
        return unique.isEmpty ? nil : unique.joined(separator: ", ")
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
