import Foundation
import Observation

/// The app's shared objects, passed down with `.environment`.
/// The API and stream clients live on `session`, because they exist only while signed in.
@MainActor
@Observable
final class AppEnvironment {
    let authStore: AuthStore
    let session: SessionModel

    init(authStore: AuthStore = AuthStore()) {
        self.authStore = authStore
        self.session = SessionModel(authStore: authStore)
    }
}
