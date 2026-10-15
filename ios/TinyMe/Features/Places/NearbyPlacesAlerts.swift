import CoreLocation
import Foundation
import Observation
import UserNotifications

/// Opt-in geofence monitoring for the nearest saved places. Place names and coordinates stay on device.
@MainActor
@Observable
final class NearbyPlacesAlerts: NSObject, @preconcurrency CLLocationManagerDelegate, @preconcurrency UNUserNotificationCenterDelegate {
    private static let enabledKey = "places.nearbyAlerts.enabled"
    private static let monitorName = "TinyMeNearbyPlaces"
    private static let cachedPlacesKey = "places.nearbyAlerts.cachedPlaces"

    private let manager = CLLocationManager()
    private let defaults: UserDefaults
    private var monitor: CLMonitor?
    private var eventTask: Task<Void, Never>?
    private var locationWaiter: CheckedContinuation<CLLocation?, Never>?
    private var authorizationWaiter: CheckedContinuation<CLAuthorizationStatus, Never>?
    private var lastRefreshLocation: CLLocation?
    private var placesByID: [String: PlaceDTO] = [:]
    private var isRefreshing = false

    private(set) var isEnabled: Bool
    private(set) var message: String?

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        self.isEnabled = defaults.bool(forKey: Self.enabledKey)
        if let data = defaults.data(forKey: Self.cachedPlacesKey),
           let cached = try? JSONDecoder().decode([PlaceDTO].self, from: data) {
            self.placesByID = Dictionary(uniqueKeysWithValues: cached.map { ($0.id, $0) })
        }
        super.init()
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyHundredMeters
        // Notifications should still be visible when the user is currently in TinyMe.
        UNUserNotificationCenter.current().delegate = self
    }

    func enable(api: APIClient) async -> Bool {
        message = nil
        currentAPI = api
        let notificationSettings = await UNUserNotificationCenter.current().notificationSettings()
        if notificationSettings.authorizationStatus == .notDetermined {
            do {
                guard try await UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound]) else {
                    message = "Allow notifications for TinyMe to turn on nearby place alerts."
                    return false
                }
            } catch {
                message = "TinyMe couldn't request notification permission. Try again in Settings."
                return false
            }
        } else if notificationSettings.authorizationStatus == .denied {
            message = "Allow notifications for TinyMe in Settings to use nearby alerts."
            return false
        }

        guard await requestAlwaysAuthorization() else {
            message = "Nearby alerts need Always location access. Choose Always in iPhone Settings for TinyMe."
            return false
        }

        isEnabled = true
        defaults.set(true, forKey: Self.enabledKey)
        manager.allowsBackgroundLocationUpdates = true
        manager.startMonitoringSignificantLocationChanges()
        await startMonitorIfNeeded()
        await refresh(api: api)
        return true
    }

    func disable() async {
        isEnabled = false
        defaults.set(false, forKey: Self.enabledKey)
        manager.stopMonitoringSignificantLocationChanges()
        eventTask?.cancel()
        eventTask = nil
        if let monitor {
            for identifier in await monitor.identifiers { await monitor.remove(identifier) }
        }
        placesByID.removeAll()
        defaults.removeObject(forKey: Self.cachedPlacesKey)
    }

    func refresh(api: APIClient) async {
        guard isEnabled, !isRefreshing, await requestAlwaysAuthorization() else { return }
        isRefreshing = true
        defer { isRefreshing = false }
        guard let location = await currentLocation(), Self.isUsable(location) else { return }
        do {
            let places: [PlaceDTO] = try await api.get(.placesWatch(lat: location.coordinate.latitude,
                                                                    lng: location.coordinate.longitude))
            await updateMonitoredPlaces(places)
            // A geofence's first state may be unknown when monitoring is added while the phone is
            // already inside it. Check the fresh fix directly so enabling alerts at a saved place
            // (or opening TinyMe while nearby) produces the same nudge as crossing the boundary.
            if location.horizontalAccuracy >= 0, location.horizontalAccuracy <= 100 {
                await notifyForPlacesAlreadyNearby(places, location: location)
            }
            lastRefreshLocation = location
        } catch {
            // Keep the existing monitored places during temporary network failures.
        }
    }

    private func startMonitorIfNeeded() async {
        if monitor == nil {
            monitor = await CLMonitor(Self.monitorName)
        }
        guard let monitor, eventTask == nil else { return }
        let events = await monitor.events
        eventTask = Task { [weak self] in
            do {
                for try await event in events {
                    guard !Task.isCancelled else { break }
                    await self?.handle(event)
                }
            } catch {
                // Core Location restores the persisted conditions when monitoring starts again.
            }
        }
    }

    private func updateMonitoredPlaces(_ places: [PlaceDTO]) async {
        guard let monitor else { return }
        let selected = Array(places.prefix(20).filter { !$0.visited })
        let desired = Set(selected.map(\.id))
        for identifier in await monitor.identifiers where !desired.contains(identifier) {
            await monitor.remove(identifier)
        }
        placesByID = Dictionary(uniqueKeysWithValues: selected.map { ($0.id, $0) })
        if let data = try? JSONEncoder().encode(selected) {
            defaults.set(data, forKey: Self.cachedPlacesKey)
        }
        for place in selected {
            await monitor.add(CLMonitor.CircularGeographicCondition(
                center: CLLocationCoordinate2D(latitude: place.lat, longitude: place.lng), radius: 150
            ), identifier: place.id)
        }
    }

    private func handle(_ event: CLMonitor.Event) async {
        guard isEnabled, event.state == .satisfied, let place = placesByID[event.identifier] else { return }
        await notifyIfAllowed(for: place)
    }

    private func notifyForPlacesAlreadyNearby(_ places: [PlaceDTO], location: CLLocation) async {
        for place in places where !place.visited {
            let placeLocation = CLLocation(latitude: place.lat, longitude: place.lng)
            // Include the reported accuracy to avoid nudging for an uncertain fix that might be
            // outside the radius. An 80 m fix with ±20 m accuracy still qualifies.
            if placeLocation.distance(from: location) + location.horizontalAccuracy <= 150 {
                await notifyIfAllowed(for: place)
            }
        }
    }

    private func notifyIfAllowed(for place: PlaceDTO) async {
        let now = Date()
        let hour = Calendar.current.component(.hour, from: now)
        guard hour >= 8 && hour < 22 else { return }

        let cooldownKey = "places.nearbyAlerts.lastNotified.\(place.id)"
        if let last = defaults.object(forKey: cooldownKey) as? Date,
           now.timeIntervalSince(last) < 7 * 24 * 60 * 60 { return }

        let day = Calendar.current.startOfDay(for: now)
        let storedDay = defaults.object(forKey: "places.nearbyAlerts.dailyDate") as? Date
        let count = storedDay.map { Calendar.current.isDate($0, inSameDayAs: day)
            ? defaults.integer(forKey: "places.nearbyAlerts.dailyCount") : 0 } ?? 0
        guard count < 3 else { return }

        let content = UNMutableNotificationContent()
        content.title = "You're near \(place.name)"
        let note = place.note?.trimmingCharacters(in: .whitespacesAndNewlines)
        if let note, !note.isEmpty { content.body = note }
        else { content.body = place.area ?? "A place on your list" }
        content.sound = .default
        let request = UNNotificationRequest(identifier: "tinyme-place-\(place.id)-\(UUID())",
                                            content: content, trigger: nil)
        do {
            try await UNUserNotificationCenter.current().add(request)
            defaults.set(now, forKey: cooldownKey)
            defaults.set(day, forKey: "places.nearbyAlerts.dailyDate")
            defaults.set(count + 1, forKey: "places.nearbyAlerts.dailyCount")
        } catch { }
    }

    private func requestAlwaysAuthorization() async -> Bool {
        var status = manager.authorizationStatus
        if status == .notDetermined {
            status = await requestAuthorization(always: false)
        }
        if status == .authorizedWhenInUse {
            status = await requestAuthorization(always: true)
        }
        return status == .authorizedAlways
    }

    private func requestAuthorization(always: Bool) async -> CLAuthorizationStatus {
        await withCheckedContinuation { continuation in
            authorizationWaiter = continuation
            if always { manager.requestAlwaysAuthorization() }
            else { manager.requestWhenInUseAuthorization() }
            Task { [weak self] in
                try? await Task.sleep(for: .seconds(20))
                guard let self, let waiter = self.authorizationWaiter else { return }
                self.authorizationWaiter = nil
                waiter.resume(returning: self.manager.authorizationStatus)
            }
        }
    }

    private func currentLocation() async -> CLLocation? {
        if let location = manager.location, Self.isUsable(location) { return location }
        return await withCheckedContinuation { continuation in
            locationWaiter = continuation
            manager.requestLocation()
            Task { [weak self] in
                try? await Task.sleep(for: .seconds(12))
                guard let self, let waiter = self.locationWaiter else { return }
                self.locationWaiter = nil
                waiter.resume(returning: nil)
            }
        }
    }

    private static func isUsable(_ location: CLLocation) -> Bool {
        CLLocationCoordinate2DIsValid(location.coordinate)
            && location.horizontalAccuracy >= 0 && location.horizontalAccuracy < 1000
            && abs(location.timestamp.timeIntervalSinceNow) < 10 * 60
    }

    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        guard let waiter = authorizationWaiter else { return }
        let status = manager.authorizationStatus
        guard status != .notDetermined else { return }
        authorizationWaiter = nil
        waiter.resume(returning: status)
    }

    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let location = locations.last else { return }
        if let waiter = locationWaiter {
            locationWaiter = nil
            waiter.resume(returning: location)
            return
        }
        guard isEnabled, Self.isUsable(location),
              let previous = lastRefreshLocation,
              location.distance(from: previous) >= 1000,
              let api = currentAPI else { return }
        Task { await refresh(api: api) }
    }

    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        guard let waiter = locationWaiter else { return }
        locationWaiter = nil
        waiter.resume(returning: nil)
    }

    // Set only while the signed-in app is active; geofences continue to be monitored by Core Location.
    private var currentAPI: APIClient?

    func appBecameActive(api: APIClient?) async {
        currentAPI = api
        guard isEnabled, let api else { return }
        await startMonitorIfNeeded()
        await refresh(api: api)
    }

    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter,
                                            willPresent notification: UNNotification,
                                            withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) {
        completionHandler([.banner, .sound])
    }
}
