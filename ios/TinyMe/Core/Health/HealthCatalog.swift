import Foundation
import HealthKit

/// The switches on the Apple Health screen. Raw values match the server's health_settings.groups.
enum HealthGroup: String, CaseIterable, Codable, Sendable, Identifiable {
    case activity
    case workouts
    case body
    case sleepHeart = "sleep_heart"

    var id: Self { self }

    var title: String {
        switch self {
        case .activity: "Activity"
        case .workouts: "Workouts"
        case .body: "Body"
        case .sleepHeart: "Sleep & heart"
        }
    }

    var detail: String {
        switch self {
        case .activity: "Steps, active energy, exercise minutes, distance"
        case .workouts: "From Apple Watch, Fitness and other apps"
        case .body: "Weight, body fat"
        case .sleepHeart: "Sleep, resting heart rate, HRV"
        }
    }

    var symbol: String {
        switch self {
        case .activity: "figure.walk"
        case .workouts: "figure.strengthtraining.traditional"
        case .body: "scalemass"
        case .sleepHeart: "bed.double"
        }
    }

    /// What iOS is asked to let TinyMe read for this group.
    var readTypes: Set<HKObjectType> {
        var types = Set(HealthCatalog.metrics.filter { $0.group == self }.map(\.objectType))
        if self == .workouts {
            // A workout's heart rate, energy and distance are read from these samples.
            types.formUnion([HKQuantityType(.heartRate), HKQuantityType(.activeEnergyBurned),
                             HKQuantityType(.distanceWalkingRunning)])
        }
        return types
    }
}

/// Follow-up question topics. Raw values match the server's health_settings.nudges_off.
enum HealthNudgeTopic: String, CaseIterable, Codable, Sendable, Identifiable {
    case workouts, sleep, weight, steps

    var id: Self { self }

    var title: String {
        switch self {
        case .workouts: "Workouts"
        case .sleep: "Sleep"
        case .weight: "Weight"
        case .steps: "Steps"
        }
    }
}

/// One thing TinyMe reads from Health: which HealthKit type, how, and the entry it becomes.
/// The kinds and fields must match the server's seed/health/kinds.json.
struct HealthMetric: @unchecked Sendable {   // HKObjectType and HKUnit are immutable
    enum Reading: Sendable {
        /// Apple's statistics per day, so iPhone and Watch aren't counted twice.
        case dailySum, dailyAverage
        /// Each sample, followed with an anchor.
        case samples
        case workouts
        /// Sleep samples folded into one night per wake-up day.
        case sleepNights
    }

    let kind: String
    let group: HealthGroup
    let objectType: HKObjectType
    let reading: Reading
    /// The entry's main number and its unit; nil for workouts and sleep, which carry several fields.
    let field: String?
    let unit: HKUnit?
    /// Decimal places kept before upload, so unit-conversion noise doesn't look like a change.
    let decimals: Int?
    /// The data field the entry's quantity equals (the server's quantity_field); nil means a quantity of 1.
    let quantityField: String?

    init(kind: String, group: HealthGroup, objectType: HKObjectType, reading: Reading, field: String?,
         unit: HKUnit?, decimals: Int?, quantityField: String? = nil) {
        self.kind = kind
        self.group = group
        self.objectType = objectType
        self.reading = reading
        self.field = field
        self.unit = unit
        self.decimals = decimals
        self.quantityField = quantityField ?? field
    }
}

enum HealthCatalog {
    static let metrics: [HealthMetric] = [
        HealthMetric(kind: "steps", group: .activity, objectType: HKQuantityType(.stepCount),
                     reading: .dailySum, field: "steps", unit: .count(), decimals: 0),
        HealthMetric(kind: "active_energy", group: .activity, objectType: HKQuantityType(.activeEnergyBurned),
                     reading: .dailySum, field: "kcal", unit: .kilocalorie(), decimals: 0),
        HealthMetric(kind: "exercise", group: .activity, objectType: HKQuantityType(.appleExerciseTime),
                     reading: .dailySum, field: "minutes", unit: .minute(), decimals: 0),
        HealthMetric(kind: "walk_distance", group: .activity, objectType: HKQuantityType(.distanceWalkingRunning),
                     reading: .dailySum, field: "km", unit: .meterUnit(with: .kilo), decimals: 2),
        HealthMetric(kind: "workout", group: .workouts, objectType: HKObjectType.workoutType(),
                     reading: .workouts, field: nil, unit: nil, decimals: nil),
        HealthMetric(kind: "weight", group: .body, objectType: HKQuantityType(.bodyMass),
                     reading: .samples, field: "kg", unit: .gramUnit(with: .kilo), decimals: 1),
        HealthMetric(kind: "body_fat", group: .body, objectType: HKQuantityType(.bodyFatPercentage),
                     reading: .samples, field: "percent", unit: .percent(), decimals: 1),
        HealthMetric(kind: "sleep", group: .sleepHeart, objectType: HKCategoryType(.sleepAnalysis),
                     reading: .sleepNights, field: nil, unit: nil, decimals: nil, quantityField: "hours"),
        HealthMetric(kind: "resting_hr", group: .sleepHeart, objectType: HKQuantityType(.restingHeartRate),
                     reading: .dailyAverage, field: "bpm", unit: .count().unitDivided(by: .minute()), decimals: 0),
        HealthMetric(kind: "hrv", group: .sleepHeart, objectType: HKQuantityType(.heartRateVariabilitySDNN),
                     reading: .dailyAverage, field: "ms", unit: .secondUnit(with: .milli), decimals: 0),
    ]

    /// How Settings names a kind.
    static func title(of kind: String) -> String {
        switch kind {
        case "steps": "Steps"
        case "active_energy": "Active energy"
        case "exercise": "Exercise minutes"
        case "walk_distance": "Distance"
        case "workout": "Workouts"
        case "weight": "Weight"
        case "body_fat": "Body fat"
        case "sleep": "Sleep"
        case "resting_hr": "Resting heart rate"
        case "hrv": "HRV"
        default: kind
        }
    }

    /// The metrics read as one total or average per day.
    static func dailyMetrics(in groups: Set<HealthGroup>) -> [HealthMetric] {
        metrics.filter { groups.contains($0.group) && ($0.reading == .dailySum || $0.reading == .dailyAverage) }
    }

    static func readTypes(for groups: Set<HealthGroup>) -> Set<HKObjectType> {
        groups.reduce(into: Set<HKObjectType>()) { $0.formUnion($1.readTypes) }
    }
}

extension HealthMetric {
    /// Rounds to this metric's decimal places. Dividing by a power of ten gives the nearest Double to the
    /// decimal, so 5.23 is sent as 5.23, not 5.2300000000000004.
    func rounded(_ value: Double) -> Double {
        guard let decimals else { return value }
        let scale = pow(10, Double(decimals))
        return (value * scale).rounded() / scale
    }
}
