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

    init(authStore: AuthStore = AuthStore(), outbox: OutboxWorker = .onDisk()) {
        self.authStore = authStore
        self.outbox = outbox
        self.session = SessionModel(authStore: authStore, outbox: outbox)
        Task { await outbox.startMonitoring() }
    }
}
