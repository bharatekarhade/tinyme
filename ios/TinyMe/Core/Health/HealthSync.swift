import Foundation
import Observation

/// Starts Health syncs and holds what the screens show: progress of the first import, the last sync, an error.
@MainActor
@Observable
final class HealthSync {
    enum Phase: Equatable {
        case idle
        case syncing(HealthSyncEngine.Progress?)
        case failed(String)
    }

    private(set) var phase: Phase = .idle
    private(set) var lastSyncedAt: Date?
    /// Goes up when a sync changed entries, so Home and History reload.
    private(set) var changes = 0

    private let settings: HealthSettingsStore
    private let engine: HealthSyncEngine

    init(settings: HealthSettingsStore, engine: HealthSyncEngine = HealthSyncEngine()) {
        self.settings = settings
        self.engine = engine
    }

    var isSyncing: Bool { if case .syncing = phase { true } else { false } }

    /// "Importing 90 days… 40%" while the first import runs; nil otherwise.
    var importLine: String? {
        guard case .syncing(let progress?) = phase, progress.total > 0, progress.done < progress.total else { return nil }
        return "Importing \(settings.saved.importDepth.title)… \(Int((progress.fraction * 100).rounded()))%"
    }

    /// Launch, foreground and after Connect. Does nothing while a run is going or nothing is switched on.
    func start(api: APIClient?) {
        let groups = settings.readGroups
        guard let api, !isSyncing, !groups.isEmpty, HealthAccess.isAvailable else { return }
        phase = .syncing(nil)
        let depth = settings.saved.importDepth
        Task {
            do {
                let totals = try await engine.sync(groups: groups, depth: depth, api: api) { progress in
                    await MainActor.run { self.phase = .syncing(progress) }
                }
                if totals.changedSomething { changes += 1 }
                lastSyncedAt = .now
                phase = .idle
            } catch is URLError {
                phase = .failed("Couldn't reach your server. TinyMe will try again when you open it.")
            } catch {
                phase = .failed("Health data couldn't be sent. TinyMe will try again when you open it.")
            }
        }
    }
}
