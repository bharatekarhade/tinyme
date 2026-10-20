import Foundation

/// One notification to schedule, worked out from the server's data. Kept free of UserNotifications so it can be tested.
struct PlannedNotification: Equatable, Sendable {
    let id: String
    /// Nil means show it now.
    let fireDate: Date?
    let title: String
    let body: String
    let route: NotificationRoute
    /// Notifications with the same thread stack together.
    let thread: String
}

/// Where a tapped notification opens the app.
enum NotificationRoute: Equatable, Sendable {
    case plan(String)
    case upcoming
    case gscan
    case chat

    var userInfo: String {
        switch self {
        case .plan(let id): "plan:\(id)"
        case .upcoming: "upcoming"
        case .gscan: "gscan"
        case .chat: "chat"
        }
    }

    init?(userInfo: String) {
        switch userInfo {
        case "upcoming": self = .upcoming
        case "gscan": self = .gscan
        case "chat": self = .chat
        default:
            guard userInfo.hasPrefix("plan:"), userInfo.count > 5 else { return nil }
            self = .plan(String(userInfo.dropFirst(5)))
        }
    }
}

enum NotificationPlan {
    static let planPrefix = "tinyme.plan."
    static let tasksPrefix = "tinyme.tasks."
    static let journalPrefix = "tinyme.journal."
    static let mailID = "tinyme.mail"
    /// iOS keeps at most 64 pending notifications per app; plans get most, the journal nudges and places the rest.
    static let maxPlanReminders = 40
    /// Minutes before a meeting or task when the plan doesn't say.
    static let defaultLeadMinutes = 10
    /// When the morning list of today's tasks arrives.
    static let tasksHour = 9

    // MARK: Plans

    /// A reminder before each timed plan still to come, and a morning list of each day's open tasks.
    static func plans(_ upcoming: UpcomingDTO, now: Date, calendar: Calendar = .current) -> [PlannedNotification] {
        var planned: [PlannedNotification] = []

        for item in upcoming.items where !item.allDay && item.source != "people" && item.status == "upcoming" {
            guard let startsAt = item.startsAt else { continue }
            let lead = max(0, item.remindMinutes ?? defaultLeadMinutes)
            let fire = startsAt.addingTimeInterval(-Double(lead) * 60)
            guard fire > now else { continue }
            planned.append(PlannedNotification(
                id: planPrefix + item.id, fireDate: fire, title: item.title,
                body: reminderBody(item, lead: lead, startsAt: startsAt, calendar: calendar),
                route: item.planId.map(NotificationRoute.plan) ?? .upcoming, thread: "plans"))
        }

        let openTasks = upcoming.items.filter { $0.isTask && $0.allDay && !$0.isHabit && $0.status == "upcoming" }
        let overdue = upcoming.overdue.filter { $0.isTask && !$0.isHabit }
        let days = Set(openTasks.map(\.day)).union(overdue.isEmpty ? [] : [upcoming.today])
        for day in days {
            guard let fire = calendar.date(from: DateComponents(year: day.year, month: day.month, day: day.day,
                                                                hour: tasksHour)), fire > now else { continue }
            let tasks = openTasks.filter { $0.day == day }
            let earlier = day == upcoming.today ? overdue : []
            guard let body = taskListBody(tasks.map(\.title), earlier: earlier.count) else { continue }
            planned.append(PlannedNotification(
                id: tasksPrefix + day.description, fireDate: fire,
                title: tasks.isEmpty ? "Tasks waiting" : tasks.count == 1 ? "1 task today" : "\(tasks.count) tasks today",
                body: body, route: .upcoming, thread: "plans"))
        }

        return Array(planned.sorted { ($0.fireDate ?? now) < ($1.fireDate ?? now) }.prefix(maxPlanReminders))
    }

    private static func reminderBody(_ item: OccurrenceDTO, lead: Int, startsAt: Date, calendar: Calendar) -> String {
        let when: String
        switch lead {
        case 0: when = "Now"
        case ..<60: when = "In \(lead) min"
        case 60: when = "In 1 hour"
        case ..<1440 where lead % 60 == 0: when = "In \(lead / 60) hours"
        default: when = startsAt.formatted(.dateTime.weekday(.abbreviated).month(.abbreviated).day())
        }
        var parts = [when, startsAt.formatted(.dateTime.hour().minute())]
        if let place = item.place, !place.isEmpty { parts.append(place) }
        return parts.joined(separator: " · ")
    }

    /// "Pay rent, Apply: iOS Engineer at Mercari and 2 more" plus how many are left from earlier days.
    static func taskListBody(_ titles: [String], earlier: Int) -> String? {
        var lines: [String] = []
        if !titles.isEmpty { lines.append(list(titles)) }
        if earlier > 0 { lines.append(earlier == 1 ? "1 left from an earlier day" : "\(earlier) left from earlier days") }
        return lines.isEmpty ? nil : lines.joined(separator: "\n")
    }

    // MARK: Journal

    /// "Journal something?" at the chosen time on each of the next `days` days, skipping today if you already wrote.
    static func journal(minutes: Int, journaledOn last: LocalDay?, now: Date, days: Int = 7,
                        calendar: Calendar = .current) -> [PlannedNotification] {
        (0..<days).compactMap { offset in
            guard let date = calendar.date(byAdding: .day, value: offset, to: now),
                  let day = LocalDay(date: date, calendar: calendar), day != last,
                  let fire = calendar.date(from: DateComponents(year: day.year, month: day.month, day: day.day,
                                                                hour: minutes / 60, minute: minutes % 60)),
                  fire > now else { return nil }
            return PlannedNotification(id: journalPrefix + day.description, fireDate: fire,
                                       title: "Journal something?", body: "A line about your day is enough.",
                                       route: .chat, thread: "journal")
        }
    }

    // MARK: Gmail

    /// One thing G Scan found, with a key that stays the same across checks.
    struct Found: Equatable, Sendable {
        enum Kind: Sendable { case payment, plan, job }
        let key: String
        let kind: Kind
        let line: String
    }

    static func found(payments: [MailPaymentDTO], suggestions: [MailSuggestionDTO], jobs: [JobLeadDTO]) -> [Found] {
        payments.map { Found(key: "payment:\($0.id)", kind: .payment,
                             line: "\($0.amount.money($0.currency)) at \($0.merchant)") }
        + suggestions.map { Found(key: "suggestion:\($0.id)", kind: .plan, line: "\($0.title) · \($0.scheduleLabel)") }
        + jobs.map { Found(key: "job:\($0.id)", kind: .job, line: "\($0.title) at \($0.company)") }
    }

    /// One notification for everything found since the last check, or nil when nothing is new.
    static func mail(_ fresh: [Found]) -> PlannedNotification? {
        guard !fresh.isEmpty else { return nil }
        let payments = fresh.filter { $0.kind == .payment }.count
        let plans = fresh.filter { $0.kind == .plan }.count
        let jobs = fresh.filter { $0.kind == .job }.count
        let counts = [count(payments, "payment"), count(plans, "plan"), count(jobs, "job")].compactMap { $0 }
        let title = (counts.count == 1 ? counts[0] : list(counts, limit: 3)) + " to review"
        var lines = fresh.prefix(3).map(\.line)
        if fresh.count > 3 { lines.append("and \(fresh.count - 3) more") }
        return PlannedNotification(id: mailID, fireDate: nil, title: title, body: lines.joined(separator: "\n"),
                                   route: .gscan, thread: "mail")
    }

    private static func count(_ n: Int, _ noun: String) -> String? {
        n == 0 ? nil : n == 1 ? "1 \(noun)" : "\(n) \(noun)s"
    }

    /// "A", "A and B", "A, B and C", "A, B and 3 more".
    private static func list(_ items: [String], limit: Int = 2) -> String {
        if items.count <= 1 { return items.first ?? "" }
        if items.count <= limit + 1 && items.count <= 3 {
            return items.dropLast().joined(separator: ", ") + " and " + items.last!
        }
        return items.prefix(limit).joined(separator: ", ") + " and \(items.count - limit) more"
    }
}
