import Foundation
import Observation

/// The app's shared objects, passed down with `.environment`.
/// The API client lives on `session`, because it exists only while signed in.
@MainActor
@Observable
final class AppEnvironment {
    let authStore: AuthStore
    let session: SessionModel
    let intentRuntime: IntentRuntime
    /// Messages waiting to be sent. Outlives a sign-out, so nothing queued is lost.
    let outbox: OutboxWorker
    /// The Apple Health switches; cleared on sign-out and Delete all.
    let health: HealthSettingsStore
    let healthSync: HealthSync
    let nearbyPlaceAlerts: NearbyPlacesAlerts

    init(authStore: AuthStore = AuthStore(), outbox: OutboxWorker = .onDisk(), health: HealthSettingsStore = HealthSettingsStore()) {
        self.authStore = authStore
        self.outbox = outbox
        self.health = health
        let session = SessionModel(authStore: authStore, outbox: outbox, health: health)
        self.session = session
        self.intentRuntime = IntentRuntime(outbox: outbox)
        self.nearbyPlaceAlerts = NearbyPlacesAlerts()
        // Created at launch, so background delivery is set up before anything else (guide §3.2).
        self.healthSync = HealthSync(settings: health, api: { session.api })
        Task { await outbox.startMonitoring() }
    }
}
