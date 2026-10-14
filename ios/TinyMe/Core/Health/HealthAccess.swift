import Foundation
import HealthKit

/// Asking iOS for Health permissions, to read only: TinyMe never writes to Health. Reading is private:
/// iOS never says whether it was allowed, a denied type just returns nothing.
struct HealthAccess: Sendable {
    private let store: HKHealthStore

    init(store: HKHealthStore = HKHealthStore()) {
        self.store = store
    }

    /// False on an iPad without Health: the whole feature is hidden then.
    static var isAvailable: Bool { HKHealthStore.isHealthDataAvailable() }

    /// Shows iOS's sheet for exactly these groups (iOS skips types it asked about before).
    func request(groups: Set<HealthGroup>) async throws {
        try await store.requestAuthorization(toShare: [], read: HealthCatalog.readTypes(for: groups))
    }
}
