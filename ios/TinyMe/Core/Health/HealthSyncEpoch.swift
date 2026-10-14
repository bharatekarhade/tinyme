import Foundation

/// Clears local HealthKit cursors when another device has deleted the server's imported data.
enum HealthSyncEpoch {
    static let key = "health.sync_epoch"

    /// Returns true when an already-known epoch changed and the import cursors were reset.
    @discardableResult
    static func apply(_ serverEpoch: Int64, defaults: UserDefaults) -> Bool {
        let previous = (defaults.object(forKey: key) as? NSNumber)?.int64Value
        defaults.set(NSNumber(value: serverEpoch), forKey: key)
        guard let previous, previous != serverEpoch else { return false }
        defaults.removeObject(forKey: HealthSyncEngine.cursorKey)
        defaults.removeObject(forKey: HealthSyncEngine.anchorKey)
        return true
    }
}
