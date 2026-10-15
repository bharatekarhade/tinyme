import Foundation
import HealthKit

/// Background delivery: one HKObserverQuery per switched-on type, so iOS wakes TinyMe when Health has new data.
///
/// The queries must be started at every launch (iOS stops waking an app that doesn't), and each update must
/// call its completion handler when the sync is done, or iOS stops delivering.
@MainActor
final class HealthBackground {
    typealias Completion = @Sendable () -> Void

    private let store: HKHealthStore
    private var queries: [String: HKObserverQuery] = [:]
    /// Called with the metric that changed; it must call the completion when done.
    var onUpdate: (HealthMetric, @escaping Completion) -> Void = { _, done in done() }

    init(store: HKHealthStore = HKHealthStore()) {
        self.store = store
    }

    /// Watches exactly the metrics of these groups; anything switched off stops waking the app.
    func watch(groups: Set<HealthGroup>) {
        guard HealthAccess.isAvailable else { return }
        let wanted = HealthCatalog.metrics.filter { groups.contains($0.group) }
        let wantedKinds = Set(wanted.map(\.kind))
        for (kind, query) in queries where !wantedKinds.contains(kind) {
            store.stop(query)
            queries[kind] = nil
            if let type = HealthCatalog.metrics.first(where: { $0.kind == kind })?.objectType {
                store.disableBackgroundDelivery(for: type) { _, _ in }
            }
        }
        for metric in wanted where queries[metric.kind] == nil {
            guard let type = metric.objectType as? HKSampleType else { continue }
            let query = HKObserverQuery(sampleType: type, predicate: nil) { [weak self] _, completion, error in
                // HealthKit's handler may be called from any thread; it just isn't marked Sendable.
                nonisolated(unsafe) let handler = completion
                let done: Completion = { handler() }
                if let error {
                    HealthBackgroundLog.add("observer \(metric.kind) failed: \(error.localizedDescription)")
                    done()
                    return
                }
                Task { @MainActor in
                    guard let self else { done(); return }
                    self.onUpdate(metric, done)
                }
            }
            store.execute(query)
            queries[metric.kind] = query
            let kind = metric.kind
            store.enableBackgroundDelivery(for: type, frequency: Self.frequency(metric)) { ok, error in
                HealthBackgroundLog.add(ok ? "enable \(kind) ok" : "enable \(kind) failed: \(error?.localizedDescription ?? "unknown")")
            }
        }
    }

    /// Daily totals change with every step, and iOS caps them at hourly anyway; a workout, a night or a
    /// weigh-in is worth waking for straight away.
    static func frequency(_ metric: HealthMetric) -> HKUpdateFrequency {
        switch metric.reading {
        case .dailySum, .dailyAverage: .hourly
        case .samples, .workouts, .sleepNights: .immediate
        }
    }
}
