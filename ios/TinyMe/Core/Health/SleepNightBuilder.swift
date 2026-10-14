import Foundation

/// One sleep sample, without HealthKit, so the night builder is unit-tested.
struct SleepSample: Sendable, Equatable {
    enum Stage: String, Sendable {
        case inBed, awake, core, deep, rem, unspecified

        var isAsleep: Bool { self == .core || self == .deep || self == .rem || self == .unspecified }
    }

    let start: Date
    let end: Date
    let stage: Stage
    /// Which app or device recorded it (the HealthKit source's bundle id).
    let source: String
    /// Recorded by an Apple Watch, which wins over the phone and sleep apps.
    let isWatch: Bool
}

/// A night, ready to upload as a `sleep` entry.
struct SleepNight: Sendable, Equatable {
    let day: LocalDay
    let start: Date
    let hours: Double
    let data: [String: JSONValue]
}

/// Sleep samples → one night per wake-up day (guide §3.5). Pure.
///
/// - A night belongs to the day you woke up, from samples ending between 15:00 the day before and 15:00 that day.
/// - One source per night: the Watch if it recorded anything, else the source with the most time asleep.
///   Sources are never added together.
/// - `hours` is time asleep with overlaps merged. Naps (an asleep stretch under 3 h starting between
///   09:00 and 19:00) aren't "last night" and are left out.
enum SleepNightBuilder {
    static let napLimit: TimeInterval = 3 * 3600
    /// Asleep samples closer than this are one stretch of sleep.
    static let stretchGap: TimeInterval = 30 * 60
    static let maxStages = 120

    /// The day a sample counts towards: ending before 15:00 counts for that day, later for the next.
    static func wakeDay(of end: Date, calendar: Calendar) -> LocalDay? {
        guard let today = LocalDay(date: end, calendar: calendar) else { return nil }
        return calendar.component(.hour, from: end) < 15 ? today : HealthImportPlan.adding(1, to: today, calendar: calendar)
    }

    static func night(for day: LocalDay, samples all: [SleepSample], calendar: Calendar) -> SleepNight? {
        let samples = all.filter { wakeDay(of: $0.end, calendar: calendar) == day && $0.end > $0.start }
        guard let chosen = chooseSource(samples) else { return nil }
        let own = samples.filter { $0.source == chosen }

        // Asleep stretches, then drop naps.
        let stretches = merge(own.filter(\.stage.isAsleep).map { ($0.start, $0.end) }, gap: stretchGap)
        let kept = stretches.filter { !isNap($0, calendar: calendar) }
        guard !kept.isEmpty else { return nil }
        let inNight: (SleepSample) -> Bool = { sample in kept.contains { sample.end > $0.0 && sample.start < $0.1 } }
        let nightSamples = own.filter(inNight)

        let asleep = total(merge(nightSamples.filter(\.stage.isAsleep).map { ($0.start, $0.end) }, gap: 0))
        let hours = round2(asleep / 3600)
        guard hours > 0 else { return nil }
        let bedStart = nightSamples.map(\.start).min()!
        let bedEnd = nightSamples.map(\.end).max()!

        var data: [String: JSONValue] = [
            "hours": .number(hours),
            "bed_start": .string(clock(bedStart, calendar)),
            "bed_end": .string(clock(bedEnd, calendar)),
        ]
        for (field, stage) in [("deep_h", SleepSample.Stage.deep), ("rem_h", .rem), ("core_h", .core), ("awake_h", .awake)] {
            let seconds = total(merge(nightSamples.filter { $0.stage == stage }.map { ($0.start, $0.end) }, gap: 0))
            if seconds > 0 { data[field] = .number(round2(seconds / 3600)) }
        }
        let stages = stageList(nightSamples, calendar: calendar)
        if !stages.isEmpty { data["stages"] = .array(stages) }
        return SleepNight(day: day, start: bedStart, hours: hours, data: data)
    }

    private static func chooseSource(_ samples: [SleepSample]) -> String? {
        let asleep = samples.filter(\.stage.isAsleep)
        if let watch = asleep.first(where: \.isWatch) { return watch.source }
        let bySource = Dictionary(grouping: asleep, by: \.source)
            .mapValues { total(merge($0.map { ($0.start, $0.end) }, gap: 0)) }
        return bySource.max { $0.value == $1.value ? $0.key > $1.key : $0.value < $1.value }?.key
    }

    private static func isNap(_ stretch: (Date, Date), calendar: Calendar) -> Bool {
        let hour = calendar.component(.hour, from: stretch.0)
        return stretch.1.timeIntervalSince(stretch.0) < napLimit && (9..<19).contains(hour)
    }

    /// Sorted, overlapping (or within `gap`) intervals joined.
    private static func merge(_ intervals: [(Date, Date)], gap: TimeInterval) -> [(Date, Date)] {
        var merged: [(Date, Date)] = []
        for interval in intervals.sorted(by: { $0.0 < $1.0 }) {
            if let last = merged.last, interval.0.timeIntervalSince(last.1) <= gap {
                merged[merged.count - 1].1 = max(last.1, interval.1)
            } else {
                merged.append(interval)
            }
        }
        return merged
    }

    private static func total(_ intervals: [(Date, Date)]) -> TimeInterval {
        intervals.reduce(0) { $0 + $1.1.timeIntervalSince($1.0) }
    }

    /// [["core","23:52","01:10"], …]: stages in order, neighbours of the same stage joined, for a future chart.
    private static func stageList(_ samples: [SleepSample], calendar: Calendar) -> [JSONValue] {
        var runs: [(SleepSample.Stage, Date, Date)] = []
        for sample in samples.filter({ $0.stage != .inBed }).sorted(by: { $0.start < $1.start }) {
            if let last = runs.last, last.0 == sample.stage, sample.start.timeIntervalSince(last.2) <= 60 {
                runs[runs.count - 1].2 = max(last.2, sample.end)
            } else {
                runs.append((sample.stage, sample.start, sample.end))
            }
        }
        return runs.prefix(maxStages).map { .array([.string($0.0.rawValue), .string(clock($0.1, calendar)), .string(clock($0.2, calendar))]) }
    }

    private static func clock(_ date: Date, _ calendar: Calendar) -> String {
        let parts = calendar.dateComponents([.hour, .minute], from: date)
        return String(format: "%02d:%02d", parts.hour ?? 0, parts.minute ?? 0)
    }

    private static func round2(_ value: Double) -> Double { (value * 100).rounded() / 100 }
}
