import Foundation

struct UpcomingDTO: Decodable, Sendable, Hashable {
    struct Day: Decodable, Sendable, Hashable, Identifiable {
        let day: LocalDay
        let count: Int
        let hasTimed: Bool

        var id: LocalDay { day }

        private enum CodingKeys: String, CodingKey {
            case day, count
            case hasTimed = "has_timed"
        }
    }

    let now: Date
    let today: LocalDay
    let from: LocalDay
    let to: LocalDay
    let days: [Day]
    let nextUp: OccurrenceDTO?
    let overdue: [OccurrenceDTO]
    let items: [OccurrenceDTO]

    private enum CodingKeys: String, CodingKey {
        case now, today, from, to, days, overdue, items
        case nextUp = "next_up"
    }
}

struct MailSuggestionDTO: Decodable, Sendable, Hashable, Identifiable {
    let id: String
    let type: String
    let title: String
    let day: LocalDay
    let startTime: String?
    let endTime: String?
    let endDay: LocalDay?
    let sourceTime: String?
    let sourceTimeZone: String?
    let place: String?
    let people: [String]
    let confidence: Double
    let evidence: String
    let gmailMessageId: String

    private enum CodingKeys: String, CodingKey {
        case id, type, title, day, place, people, confidence, evidence
        case startTime = "start_time"
        case endTime = "end_time"
        case endDay = "end_day"
        case sourceTime = "source_time"
        case sourceTimeZone = "source_time_zone"
        case gmailMessageId = "gmail_message_id"
    }

    var scheduleLabel: String {
        guard let startTime else { return day.date()?.formatted(.dateTime.weekday(.abbreviated).month(.abbreviated).day()) ?? day.description }
        let time = String(startTime.prefix(5))
        let dateLabel = day.date()?.formatted(.dateTime.weekday(.abbreviated).month(.abbreviated).day()) ?? day.description
        if let sourceTime, let sourceTimeZone,
           let zone = TimeZone(identifier: sourceTimeZone),
           let date = day.date() {
            let sourceAbbreviation = zone.abbreviation(for: date) ?? sourceTimeZone
            let localAbbreviation = TimeZone.current.abbreviation(for: date) ?? TimeZone.current.identifier
            return "\(dateLabel) · \(sourceTime) \(sourceAbbreviation) · \(time) \(localAbbreviation)"
        }
        return "\(dateLabel) · \(time)"
    }

    var emailURL: URL? {
        URL(string: "https://mail.google.com/mail/u/0/#all/\(gmailMessageId)")
    }
}

struct AcceptedMailSuggestionDTO: Decodable, Sendable {
    let suggestionId: String
    let planId: String
    let title: String
    enum CodingKeys: String, CodingKey {
        case title
        case suggestionId = "suggestion_id"
        case planId = "plan_id"
    }
}

struct OccurrenceDTO: Decodable, Sendable, Hashable, Identifiable {
    struct Person: Decodable, Sendable, Hashable, Identifiable {
        let slug: String
        let name: String
        var id: String { slug }
    }

    let id: String
    let planId: String?
    let source: String
    let kind: String
    let title: String
    let day: LocalDay
    let startTime: String?
    let endTime: String?
    let startsAt: Date?
    let endsAt: Date?
    let allDay: Bool
    let notes: String?
    let place: String?
    let people: [Person]
    let status: String
    let repeatLabel: String?
    let habit: String?
    let editable: Bool
    let gmailMessageId: String?

    var isTask: Bool { kind == "task" }
    var isDone: Bool { status == "done" }
    var isRepeating: Bool { repeatLabel != nil }
    var isHabit: Bool { habit != nil }
    var emailURL: URL? {
        guard let gmailMessageId else { return nil }
        return URL(string: "https://mail.google.com/mail/u/0/#all/\(gmailMessageId)")
    }
    /// Quick-action URLs identify the original repeat day, even when this occurrence was moved.
    var actionDay: LocalDay {
        guard let separator = id.lastIndex(of: ":"),
              let originalDay = LocalDay(String(id[id.index(after: separator)...])) else { return day }
        return originalDay
    }
    /// The glyph for this plan on the timeline and in its sheet.
    var symbol: String {
        if source == "people" { return "birthday.cake" }
        if title.localizedCaseInsensitiveContains("run") { return "figure.run" }
        if title.localizedCaseInsensitiveContains("walk") { return "figure.walk" }
        if isHabit {
            return switch habit?.lowercased() {
            case "workout", "gym", "strength_training": "dumbbell.fill"
            case "run", "running": "figure.run"
            case "walk", "walking": "figure.walk"
            case "brush_teeth", "teeth": "sparkles"
            default: "checkmark.circle"
            }
        }
        if isTask { return "checklist" }
        return allDay ? "sun.max" : "calendar"
    }
    var clockLabel: String? {
        guard let startTime else { return nil }
        let parts = startTime.split(separator: ":").compactMap { Int($0) }
        guard parts.count == 2 else { return startTime }
        var components = DateComponents()
        components.hour = parts[0]
        components.minute = parts[1]
        guard let date = Calendar.current.date(from: components) else { return startTime }
        return date.formatted(.dateTime.hour().minute())
    }

    private enum CodingKeys: String, CodingKey {
        case id, source, kind, title, day, notes, place, people, status, habit, editable
        case planId = "plan_id"
        case startTime = "start_time"
        case endTime = "end_time"
        case startsAt = "starts_at"
        case endsAt = "ends_at"
        case allDay = "all_day"
        case repeatLabel = "repeat_label"
        case gmailMessageId = "gmail_message_id"
    }
}
