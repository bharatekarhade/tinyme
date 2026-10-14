import Foundation
import HealthKit

/// Anchored reads for sample types (workouts, weight, body fat, sleep): what was added and deleted since the
/// last anchor. With no anchor yet, everything since `since` counts as added.
struct SampleReader: @unchecked Sendable {   // HKHealthStore is thread-safe
    let store: HKHealthStore

    struct Changes<Sample: HKSample>: @unchecked Sendable {
        let added: [Sample]
        let deleted: [String]
        let anchor: HKQueryAnchor
    }

    func workouts(after anchor: HKQueryAnchor?, since: Date) async throws -> Changes<HKWorkout> {
        let descriptor = HKAnchoredObjectQueryDescriptor(predicates: [.workout(start(anchor, since))], anchor: anchor)
        return try await changes(descriptor)
    }

    func quantities(_ type: HKQuantityType, after anchor: HKQueryAnchor?, since: Date) async throws -> Changes<HKQuantitySample> {
        let descriptor = HKAnchoredObjectQueryDescriptor(
            predicates: [.quantitySample(type: type, predicate: start(anchor, since))], anchor: anchor)
        return try await changes(descriptor)
    }

    func sleep(after anchor: HKQueryAnchor?, since: Date) async throws -> Changes<HKCategorySample> {
        let descriptor = HKAnchoredObjectQueryDescriptor(
            predicates: [.categorySample(type: HKCategoryType(.sleepAnalysis), predicate: start(anchor, since))], anchor: anchor)
        return try await changes(descriptor)
    }

    /// Every sleep sample overlapping [from, to), for rebuilding whole nights.
    func sleepSamples(from: Date, to: Date) async throws -> [HKCategorySample] {
        let descriptor = HKSampleQueryDescriptor(
            predicates: [.categorySample(type: HKCategoryType(.sleepAnalysis),
                                         predicate: HKQuery.predicateForSamples(withStart: from, end: to))],
            sortDescriptors: [SortDescriptor(\.startDate)])
        return try await descriptor.result(for: store)
    }

    private func start(_ anchor: HKQueryAnchor?, _ since: Date) -> NSPredicate? {
        anchor == nil ? HKQuery.predicateForSamples(withStart: since, end: nil) : nil
    }

    private func changes<Sample: HKSample>(_ descriptor: HKAnchoredObjectQueryDescriptor<Sample>) async throws -> Changes<Sample> {
        let result = try await descriptor.result(for: store)
        return Changes(added: result.addedSamples, deleted: result.deletedObjects.map(\.uuid.uuidString), anchor: result.newAnchor)
    }

    /// TinyMe's own samples (Save to Health) are already entries; reading them back would count them twice.
    static func isEcho(_ sample: HKSample) -> Bool {
        if let id = sample.metadata?[HKMetadataKeySyncIdentifier] as? String, id.hasPrefix("tinyme:") { return true }
        return sample.sourceRevision.source.bundleIdentifier == Bundle.main.bundleIdentifier
    }

    static func sleepSample(_ sample: HKCategorySample) -> SleepSample? {
        let stage: SleepSample.Stage
        switch HKCategoryValueSleepAnalysis(rawValue: sample.value) {
        case .inBed: stage = .inBed
        case .awake: stage = .awake
        case .asleepCore: stage = .core
        case .asleepDeep: stage = .deep
        case .asleepREM: stage = .rem
        case .asleepUnspecified: stage = .unspecified
        default: return nil
        }
        return SleepSample(start: sample.startDate, end: sample.endDate, stage: stage,
                           source: sample.sourceRevision.source.bundleIdentifier,
                           isWatch: sample.sourceRevision.productType?.hasPrefix("Watch") ?? false)
    }
}
