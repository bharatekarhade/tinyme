import Foundation

/// One item for `POST /health/sync`: an entry the server creates or replaces by `external_id`.
struct HealthUploadItem: Encodable, Sendable, Equatable {
    let externalId: String
    let kind: String
    let localDay: LocalDay
    /// ISO 8601 instant. A day total sits at local noon, which never crosses midnight in any zone.
    let ts: String
    let quantity: Double
    let data: [String: JSONValue]

    private enum CodingKeys: String, CodingKey {
        case kind, ts, quantity, data
        case externalId = "external_id"
        case localDay = "local_day"
    }

    /// "steps:2026-10-14": one entry per metric per day.
    static func dailyId(kind: String, day: LocalDay) -> String { "\(kind):\(day)" }
}

struct HealthSyncBatch: Encodable, Sendable {
    var items: [HealthUploadItem]
    var deleted: [String]
}

struct HealthSyncReply: Decodable, Sendable, Equatable {
    let upserted: Int
    let unchanged: Int
    let deleted: Int
    let merged: Int
}

/// Turns one metric's day totals into upload items. Pure, so it is unit-tested.
enum HealthDailyItems {
    /// - Parameters:
    ///   - values: Apple's total or average per day; a day without data is missing or 0.
    ///   - days: the days being sent. A day in `days` with no value goes to `deleted` when `deleteEmpty`,
    ///     because a day that drops to 0 (its samples were deleted in Health) is a delete, never a 0.
    static func make(metric: HealthMetric, values: [LocalDay: Double], days: [LocalDay], deleteEmpty: Bool,
                     calendar: Calendar = .current) -> (items: [HealthUploadItem], deleted: [String]) {
        guard let field = metric.field else { return ([], []) }
        var items: [HealthUploadItem] = []
        var deleted: [String] = []
        for day in days {
            let id = HealthUploadItem.dailyId(kind: metric.kind, day: day)
            let value = metric.rounded(values[day] ?? 0)
            guard value > 0, let noon = day.date(in: calendar) else {
                if deleteEmpty { deleted.append(id) }
                continue
            }
            items.append(HealthUploadItem(externalId: id, kind: metric.kind, localDay: day,
                                          ts: noon.formatted(.iso8601), quantity: value,
                                          data: [field: .number(value)]))
        }
        return (items, deleted)
    }
}

/// The days of the first import, newest first in 30-day chunks, so recent cards fill in first.
enum HealthImportPlan {
    static let chunkDays = 30

    /// The chunk ending the day before `end`, never reaching before `oldest`. Nil once `end` is past `oldest`.
    static func chunk(oldest: LocalDay, end: LocalDay, calendar: Calendar = .current) -> [LocalDay]? {
        guard end > oldest, let endDate = end.date(in: calendar) else { return nil }
        var days: [LocalDay] = []
        for back in 1...chunkDays {
            guard let date = calendar.date(byAdding: .day, value: -back, to: endDate),
                  let day = LocalDay(date: date, calendar: calendar), day >= oldest else { break }
            days.append(day)
        }
        return days.isEmpty ? nil : days
    }

    static func adding(_ days: Int, to day: LocalDay, calendar: Calendar = .current) -> LocalDay {
        guard let date = day.date(in: calendar), let moved = calendar.date(byAdding: .day, value: days, to: date),
              let result = LocalDay(date: moved, calendar: calendar) else { return day }
        return result
    }
}
