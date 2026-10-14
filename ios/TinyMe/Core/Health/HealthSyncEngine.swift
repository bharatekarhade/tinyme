import Foundation
import HealthKit

/// Where the first import of one kind has got to: days from `oldest` up to the day before `nextEnd` are still to do.
struct HealthImportCursor: Codable, Sendable, Equatable {
    var oldest: LocalDay
    var nextEnd: LocalDay
    var isDone: Bool { nextEnd <= oldest }
}

/// Reads Health and uploads what changed, one run at a time.
///
/// Daily totals: each run resends the last 3 days (late Watch data, deleted samples), then continues the first
/// import, newest first in 30-day chunks. Workouts, weight and body fat: anchored reads, added samples as
/// upserts and deleted ones as deletes. Sleep: the nights touched by new or deleted samples are rebuilt from all
/// of their samples. Cursors and anchors move only after the server accepted the batch, so a failed run just
/// reads the same again next time; HealthKit is the durable copy.
actor HealthSyncEngine {
    static let cursorKey = "health.import"
    static let anchorKey = "health.anchors"
    /// Deleted sleep samples don't say which night they were in, so a deletion rebuilds this many recent nights.
    static let nightsRebuiltOnDelete = 14
    /// Today and the 2 days before are re-read on every run.
    static let recentDays = 3

    struct Progress: Sendable, Equatable {
        let done: Int
        let total: Int
        var fraction: Double { total == 0 ? 1 : Double(done) / Double(total) }
    }

    private let reader: DailyStatsReader
    private let samples: SampleReader
    private nonisolated(unsafe) let defaults: UserDefaults   // UserDefaults is thread-safe

    init(store: HKHealthStore = HKHealthStore(), defaults: UserDefaults = .standard) {
        reader = DailyStatsReader(store: store)
        samples = SampleReader(store: store)
        self.defaults = defaults
    }

    /// - Parameter groups: the groups switched on in TinyMe (`HealthSettingsStore.readGroups`), not what iOS allows.
    func sync(groups: Set<HealthGroup>, depth: HealthImportDepth, api: APIClient, today: LocalDay = .today(),
              progress: @escaping @Sendable (Progress) async -> Void) async throws -> HealthUploader.Totals {
        let uploader = HealthUploader(api: api)
        var totals = HealthUploader.Totals()
        let sampleMetrics = HealthCatalog.metrics.filter { groups.contains($0.group) && $0.reading != .dailySum && $0.reading != .dailyAverage }
        let since = HealthImportPlan.adding(-(depth.rawValue - 1), to: today).date().map { Calendar.current.startOfDay(for: $0) } ?? .distantPast
        // Samples first: a workout or last night matter more than day 89 of steps.
        for metric in sampleMetrics {
            add(try await syncSamples(metric, since: since, today: today, uploader: uploader), to: &totals)
        }

        let metrics = HealthCatalog.dailyMetrics(in: groups)
        guard !metrics.isEmpty else { return totals }

        // Recent days: a day that dropped to 0 is sent as a delete.
        let recentFirst = HealthImportPlan.adding(-(Self.recentDays - 1), to: today)
        let recent = (0..<Self.recentDays).map { HealthImportPlan.adding(-$0, to: today) }
        var items: [HealthUploadItem] = []
        var deleted: [String] = []
        for metric in metrics {
            let values = try await reader.values(for: metric, from: recentFirst, through: today)
            let made = HealthDailyItems.make(metric: metric, values: values, days: recent, deleteEmpty: true)
            items += made.items
            deleted += made.deleted
        }
        add(try await uploader.send(items: items, deleted: deleted), to: &totals)

        // The first import, for kinds that haven't finished (a group switched on later starts its own).
        var cursors = loadCursors()
        for metric in metrics where cursors[metric.kind] == nil {
            cursors[metric.kind] = HealthImportCursor(oldest: HealthImportPlan.adding(-(depth.rawValue - 1), to: today),
                                                      nextEnd: recentFirst)
        }
        saveCursors(cursors)
        let total = metrics.reduce(0) { $0 + Self.chunksLeft(cursors[$1.kind]!) }
        var done = 0
        await progress(Progress(done: done, total: total))
        while true {
            var round: [(metric: HealthMetric, days: [LocalDay])] = []
            for metric in metrics {
                guard let cursor = cursors[metric.kind], !cursor.isDone,
                      let days = HealthImportPlan.chunk(oldest: cursor.oldest, end: cursor.nextEnd) else { continue }
                round.append((metric, days))
            }
            guard !round.isEmpty else { break }
            var items: [HealthUploadItem] = []
            for (metric, days) in round {
                let values = try await reader.values(for: metric, from: days.last!, through: days.first!)
                items += HealthDailyItems.make(metric: metric, values: values, days: days, deleteEmpty: false).items
            }
            add(try await uploader.send(items: items, deleted: []), to: &totals)
            for (metric, days) in round { cursors[metric.kind]?.nextEnd = days.last! }
            saveCursors(cursors)
            done += round.count
            await progress(Progress(done: done, total: total))
        }
        return totals
    }

    /// Workouts, weight and body fat: one entry per HealthKit sample. Sleep: one entry per night.
    private func syncSamples(_ metric: HealthMetric, since: Date, today: LocalDay,
                             uploader: HealthUploader) async throws -> HealthUploader.Totals {
        let anchor = loadAnchor(metric.kind)
        switch metric.reading {
        case .workouts:
            let changes = try await samples.workouts(after: anchor, since: since)
            let items = changes.added.filter { !SampleReader.isEcho($0) }
                .compactMap { WorkoutMapper.item(WorkoutMapper.reading($0)) }
            let totals = try await uploader.send(items: items, deleted: changes.deleted)
            saveAnchor(changes.anchor, for: metric.kind)
            return totals
        case .samples:
            guard let type = metric.objectType as? HKQuantityType else { return HealthUploader.Totals() }
            let changes = try await samples.quantities(type, after: anchor, since: since)
            let items = changes.added.filter { !SampleReader.isEcho($0) }.compactMap { quantityItem($0, metric) }
            let totals = try await uploader.send(items: items, deleted: changes.deleted)
            saveAnchor(changes.anchor, for: metric.kind)
            return totals
        case .sleepNights:
            return try await syncNights(metric, anchor: anchor, since: since, today: today, uploader: uploader)
        case .dailySum, .dailyAverage:
            return HealthUploader.Totals()
        }
    }

    private func syncNights(_ metric: HealthMetric, anchor: HKQueryAnchor?, since: Date, today: LocalDay,
                            uploader: HealthUploader) async throws -> HealthUploader.Totals {
        let calendar = Calendar.current
        let changes = try await samples.sleep(after: anchor, since: since)
        var days = Set(changes.added.compactMap { SleepNightBuilder.wakeDay(of: $0.endDate, calendar: calendar) })
        let isFirstImport = anchor == nil
        if !isFirstImport {
            // Last night and the one before can still change; a deletion could have hit any recent night.
            let recent = changes.deleted.isEmpty ? 2 : Self.nightsRebuiltOnDelete
            days.formUnion((0..<recent).map { HealthImportPlan.adding(-$0, to: today, calendar: calendar) })
        }
        days = days.filter { $0 <= today }
        guard let first = days.min(), let last = days.max(),
              let firstNoon = first.date(in: calendar), let lastNoon = last.date(in: calendar),
              let from = calendar.date(byAdding: .hour, value: -(12 + 24), to: firstNoon),   // 00:00 two days before
              let to = calendar.date(byAdding: .hour, value: 3, to: lastNoon) else {           // 15:00 on the last day
            saveAnchor(changes.anchor, for: metric.kind)
            return HealthUploader.Totals()
        }
        let all = try await samples.sleepSamples(from: from, to: to)
            .filter { !SampleReader.isEcho($0) }.compactMap(SampleReader.sleepSample)
        var items: [HealthUploadItem] = []
        var deleted: [String] = []
        for day in days.sorted(by: >) {
            let id = HealthUploadItem.dailyId(kind: metric.kind, day: day)
            guard let night = SleepNightBuilder.night(for: day, samples: all, calendar: calendar) else {
                // A night with no sleep left (its samples were deleted) is a delete; on the first import it's just empty.
                if !isFirstImport { deleted.append(id) }
                continue
            }
            items.append(HealthUploadItem(externalId: id, kind: metric.kind, localDay: day,
                                          ts: night.start.formatted(.iso8601), quantity: night.hours, data: night.data))
        }
        let totals = try await uploader.send(items: items, deleted: deleted)
        saveAnchor(changes.anchor, for: metric.kind)
        return totals
    }

    private func quantityItem(_ sample: HKQuantitySample, _ metric: HealthMetric) -> HealthUploadItem? {
        guard let unit = metric.unit, let field = metric.field,
              let day = LocalDay(date: sample.startDate) else { return nil }
        var value = sample.quantity.doubleValue(for: unit)
        if metric.kind == "body_fat" { value *= 100 }   // HealthKit's percent is a fraction
        value = metric.rounded(value)
        let plausible = metric.kind == "weight" ? (20...400).contains(value) : (1...80).contains(value)
        guard plausible else { return nil }
        return HealthUploadItem(externalId: sample.uuid.uuidString, kind: metric.kind, localDay: day,
                                ts: sample.startDate.formatted(.iso8601), quantity: value, data: [field: .number(value)])
    }

    private nonisolated func loadAnchor(_ kind: String) -> HKQueryAnchor? {
        guard let stored = defaults.dictionary(forKey: Self.anchorKey)?[kind] as? Data else { return nil }
        return try? NSKeyedUnarchiver.unarchivedObject(ofClass: HKQueryAnchor.self, from: stored)
    }

    /// Only after the server accepted the batch, so a crash or failure never skips samples.
    private func saveAnchor(_ anchor: HKQueryAnchor, for kind: String) {
        guard let data = try? NSKeyedArchiver.archivedData(withRootObject: anchor, requiringSecureCoding: true) else { return }
        var anchors = defaults.dictionary(forKey: Self.anchorKey) ?? [:]
        anchors[kind] = data
        defaults.set(anchors, forKey: Self.anchorKey)
    }

    /// How much of the first import is left for these groups, without reading Health.
    nonisolated func importLeft(groups: Set<HealthGroup>) -> Int {
        let cursors = loadCursors()
        return HealthCatalog.dailyMetrics(in: groups).reduce(0) { $0 + (cursors[$1.kind].map(Self.chunksLeft) ?? 1) }
    }

    private static func chunksLeft(_ cursor: HealthImportCursor) -> Int {
        var count = 0
        var end = cursor.nextEnd
        while let days = HealthImportPlan.chunk(oldest: cursor.oldest, end: end) {
            count += 1
            end = days.last!
        }
        return count
    }

    private func add(_ batch: HealthUploader.Totals, to totals: inout HealthUploader.Totals) {
        totals.upserted += batch.upserted
        totals.unchanged += batch.unchanged
        totals.deleted += batch.deleted
        totals.dropped += batch.dropped
    }

    private nonisolated func loadCursors() -> [String: HealthImportCursor] {
        defaults.data(forKey: Self.cursorKey).flatMap { try? JSONDecoder().decode([String: HealthImportCursor].self, from: $0) } ?? [:]
    }

    private func saveCursors(_ cursors: [String: HealthImportCursor]) {
        if let data = try? JSONEncoder().encode(cursors) { defaults.set(data, forKey: Self.cursorKey) }
    }
}
