import Foundation
import Observation

/// The app's shared objects, passed down with `.environment`.
/// The API client lives on `session`, because it exists only while signed in.
@MainActor
@Observable
final class AppEnvironment {
    let authStore: AuthStore
    let session: SessionModel
    /// Messages waiting to be sent. Outlives a sign-out, so nothing queued is lost.
    let outbox: OutboxWorker
    /// The Apple Health switches; cleared on sign-out and Delete all.
    let health: HealthSettingsStore
    let healthSync: HealthSync

    init(authStore: AuthStore = AuthStore(), outbox: OutboxWorker = .onDisk(), health: HealthSettingsStore = HealthSettingsStore()) {
        self.authStore = authStore
        self.outbox = outbox
        self.health = health
        self.healthSync = HealthSync(settings: health)
        self.session = SessionModel(authStore: authStore, outbox: outbox, health: health)
        Task { await outbox.startMonitoring() }
    }
}
