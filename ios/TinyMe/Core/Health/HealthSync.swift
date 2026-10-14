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
    private let background: HealthBackground
    private let api: () -> APIClient?
    /// Background wake-ups that arrived during another run, by kind, with the completions iOS is waiting on.
    private var waiting: [String: (metric: HealthMetric, completions: [HealthBackground.Completion])] = [:]

    init(settings: HealthSettingsStore, engine: HealthSyncEngine = HealthSyncEngine(),
         background: HealthBackground = HealthBackground(), api: @escaping () -> APIClient?) {
        self.settings = settings
        self.engine = engine
        self.background = background
        self.api = api
        background.onUpdate = { [weak self] metric, done in
            guard let self else { done(); return }
            self.wake(for: metric, done: done)
        }
        // At every launch, including one iOS makes in the background to deliver new data.
        background.watch(groups: settings.readGroups)
    }

    /// A full sync or background wake-ups are running; only one run at a time.
    private(set) var isSyncing = false

    /// "Importing 90 days… 40%" while the first import runs; nil otherwise.
    var importLine: String? {
        guard case .syncing(let progress?) = phase, progress.total > 0, progress.done < progress.total else { return nil }
        return "Importing \(settings.saved.importDepth.title)… \(Int((progress.fraction * 100).rounded()))%"
    }

    /// Launch, foreground and after Connect. Does nothing while a run is going or nothing is switched on.
    func start(api: APIClient?) {
        let groups = settings.readGroups
        background.watch(groups: groups)
        guard let api, !isSyncing, !groups.isEmpty, HealthAccess.isAvailable else { return }
        isSyncing = true
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
            // Wake-ups that came in meanwhile, before anything else may start.
            await runWaiting()
            isSyncing = false
        }
    }

    /// iOS woke TinyMe because this metric has new data. During another run it waits for that run to end.
    private func wake(for metric: HealthMetric, done: @escaping HealthBackground.Completion) {
        guard settings.readGroups.contains(metric.group), api() != nil else { done(); return }
        waiting[metric.kind, default: (metric, [])].completions.append(done)
        guard !isSyncing else { return }
        isSyncing = true
        Task {
            await runWaiting()
            isSyncing = false
        }
    }

    /// One small sync per waiting metric; every completion is called, success or not, so iOS keeps delivering.
    private func runWaiting() async {
        while let (kind, entry) = waiting.first {
            waiting[kind] = nil
            if let api = api(), settings.readGroups.contains(entry.metric.group) {
                if let totals = try? await engine.syncOne(entry.metric, depth: settings.saved.importDepth, api: api),
                   totals.changedSomething {
                    changes += 1
                }
                lastSyncedAt = .now
            }
            entry.completions.forEach { $0() }
        }
    }
}
