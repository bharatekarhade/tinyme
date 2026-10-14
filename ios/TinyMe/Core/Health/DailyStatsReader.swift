import Foundation
import HealthKit

/// Apple's statistics per day for a metric. Statistics, not added-up samples: iPhone and Watch both count
/// steps, and the statistics query keeps one source per time slot.
struct DailyStatsReader: @unchecked Sendable {   // HKHealthStore is thread-safe
    let store: HKHealthStore

    /// Values for each day from `first` to `last`, both included; days without data are left out.
    func values(for metric: HealthMetric, from first: LocalDay, through last: LocalDay,
                calendar: Calendar = .current) async throws -> [LocalDay: Double] {
        guard let type = metric.objectType as? HKQuantityType, let unit = metric.unit,
              let firstNoon = first.date(in: calendar), let lastNoon = last.date(in: calendar),
              let end = calendar.date(byAdding: .day, value: 1, to: calendar.startOfDay(for: lastNoon)) else { return [:] }
        let start = calendar.startOfDay(for: firstNoon)
        let options: HKStatisticsOptions = metric.reading == .dailySum ? .cumulativeSum : .discreteAverage
        let descriptor = HKStatisticsCollectionQueryDescriptor(
            predicate: .quantitySample(type: type, predicate: HKQuery.predicateForSamples(withStart: start, end: end)),
            options: options,
            anchorDate: start,
            intervalComponents: DateComponents(day: 1))
        let collection = try await descriptor.result(for: store)
        var values: [LocalDay: Double] = [:]
        collection.enumerateStatistics(from: start, to: end) { statistics, _ in
            let quantity = metric.reading == .dailySum ? statistics.sumQuantity() : statistics.averageQuantity()
            guard let quantity, let day = LocalDay(date: statistics.startDate, calendar: calendar) else { return }
            values[day] = quantity.doubleValue(for: unit)
        }
        return values
    }
}
