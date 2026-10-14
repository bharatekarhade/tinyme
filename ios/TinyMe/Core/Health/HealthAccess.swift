import Foundation
import HealthKit

/// Asking iOS for Health permissions. Reading is private: iOS never says whether it was allowed,
/// a denied type just returns nothing. Writing can be checked.
struct HealthAccess: Sendable {
    enum WriteStatus: Equatable, Sendable {
        case notAsked, allowed, notAllowed
        /// Some types allowed; the names of the ones that aren't, e.g. ["water"].
        case partly(missing: [String])
    }

    private let store: HKHealthStore

    init(store: HKHealthStore = HKHealthStore()) {
        self.store = store
    }

    /// False on an iPad without Health: the whole feature is hidden then.
    static var isAvailable: Bool { HKHealthStore.isHealthDataAvailable() }

    /// Shows iOS's sheet for exactly these groups (iOS skips types it asked about before).
    func request(groups: Set<HealthGroup>, write: Bool) async throws {
        try await store.requestAuthorization(toShare: write ? HealthCatalog.shareTypes : [],
                                             read: HealthCatalog.readTypes(for: groups))
    }

    var writeStatus: WriteStatus {
        let statuses = HealthCatalog.shareTypes.map { ($0, store.authorizationStatus(for: $0)) }
        if statuses.allSatisfy({ $0.1 == .notDetermined }) { return .notAsked }
        let missing = statuses.filter { $0.1 != .sharingAuthorized }.map(\.0)
        if missing.isEmpty { return .allowed }
        if missing.count == statuses.count { return .notAllowed }
        return .partly(missing: HealthCatalog.shareNames(missing))
    }
}
