import Foundation
import HealthKit

/// What a workout says, read from HealthKit, without HealthKit types, so mapping it is unit-tested.
struct WorkoutReading: Sendable, Equatable {
    let id: String
    let activity: String
    let start: Date
    let end: Date
    let kcal: Double?
    let km: Double?
    let avgHeartRate: Double?
    let sourceApp: String?
}

/// HKWorkout → a `workout` upload item (guide §1.1). Health's own type goes in `type`; a name you give it in
/// TinyMe lives in `label`, which the server keeps.
enum WorkoutMapper {
    static func item(_ workout: WorkoutReading, calendar: Calendar = .current) -> HealthUploadItem? {
        let minutes = (workout.end.timeIntervalSince(workout.start) / 60).rounded()
        guard minutes >= 1, minutes <= 1440, let day = LocalDay(date: workout.start, calendar: calendar) else { return nil }
        var data: [String: JSONValue] = ["type": .string(workout.activity), "minutes": .number(minutes)]
        if let kcal = workout.kcal?.rounded(), kcal > 0, kcal <= 20000 { data["kcal"] = .number(kcal) }
        if let km = workout.km.map({ ($0 * 100).rounded() / 100 }), km > 0, km <= 1000 { data["km"] = .number(km) }
        if let bpm = workout.avgHeartRate?.rounded(), (25...250).contains(bpm) { data["avg_hr"] = .number(bpm) }
        if let app = workout.sourceApp?.trimmingCharacters(in: .whitespaces), !app.isEmpty {
            data["source_app"] = .string(String(app.prefix(200)))
        }
        return HealthUploadItem(externalId: workout.id, kind: "workout", localDay: day,
                                ts: workout.start.formatted(.iso8601), quantity: 1, data: data)
    }

    static func reading(_ workout: HKWorkout) -> WorkoutReading {
        let kcal = workout.statistics(for: HKQuantityType(.activeEnergyBurned))?.sumQuantity()?.doubleValue(for: .kilocalorie())
        let distanceTypes: [HKQuantityTypeIdentifier] = [.distanceWalkingRunning, .distanceCycling, .distanceSwimming,
                                                         .distanceDownhillSnowSports, .distanceWheelchair]
        let km = distanceTypes.lazy.compactMap {
            workout.statistics(for: HKQuantityType($0))?.sumQuantity()?.doubleValue(for: .meterUnit(with: .kilo))
        }.first
        let bpm = workout.statistics(for: HKQuantityType(.heartRate))?.averageQuantity()?
            .doubleValue(for: .count().unitDivided(by: .minute()))
        return WorkoutReading(id: workout.uuid.uuidString, activity: name(of: workout.workoutActivityType),
                              start: workout.startDate, end: workout.endDate, kcal: kcal, km: km,
                              avgHeartRate: bpm, sourceApp: workout.sourceRevision.source.name)
    }

    /// Plain names, as people say them; anything not listed is "workout".
    static func name(of type: HKWorkoutActivityType) -> String {
        switch type {
        case .running: "running"
        case .walking: "walking"
        case .hiking: "hiking"
        case .cycling: "cycling"
        case .swimming: "swimming"
        case .yoga: "yoga"
        case .pilates: "pilates"
        case .traditionalStrengthTraining: "traditional strength training"
        case .functionalStrengthTraining: "functional strength training"
        case .coreTraining: "core training"
        case .highIntensityIntervalTraining: "HIIT"
        case .crossTraining: "cross training"
        case .mixedCardio: "mixed cardio"
        case .elliptical: "elliptical"
        case .rowing: "rowing"
        case .stairClimbing, .stairs, .stepTraining: "stairs"
        case .jumpRope: "jump rope"
        case .boxing: "boxing"
        case .kickboxing: "kickboxing"
        case .martialArts: "martial arts"
        case .socialDance, .cardioDance: "dance"
        case .climbing: "climbing"
        case .soccer: "football"
        case .basketball: "basketball"
        case .tennis: "tennis"
        case .badminton: "badminton"
        case .tableTennis: "table tennis"
        case .golf: "golf"
        case .flexibility: "stretching"
        case .cooldown: "cooldown"
        case .mindAndBody: "mind and body"
        default: "workout"
        }
    }
}
