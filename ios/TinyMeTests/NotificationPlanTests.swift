import Foundation
import Testing
@testable import TinyMe

struct NotificationPlanTests {
    private var tokyo: Calendar {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "Asia/Tokyo")!
        return calendar
    }

    /// 2026-10-18 08:00 in Tokyo.
    private let now = Date(timeIntervalSince1970: 1_792_278_000)

    private func upcoming(items: String, overdue: String = "") throws -> UpcomingDTO {
        let json = """
        {"now":"2026-10-17T23:00:00Z","today":"2026-10-18","from":"2026-10-18","to":"2026-10-24","days":[],
         "next_up":null,"overdue":[\(overdue)],"items":[\(items)]}
        """
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        return try decoder.decode(UpcomingDTO.self, from: Data(json.utf8))
    }

    private func item(_ id: String, kind: String = "event", title: String, day: String = "2026-10-18",
                      startsAt: String? = nil, status: String = "upcoming", remind: Int? = nil,
                      habit: String? = nil, source: String = "chat") -> String {
        let timed = startsAt.map { "\"starts_at\":\"\($0)\",\"ends_at\":\"\($0)\"," } ?? ""
        return """
        {"id":"\(id)","plan_id":"\(id)","source":"\(source)","kind":"\(kind)","title":"\(title)","day":"\(day)",
         \(timed)"all_day":\(startsAt == nil),"people":[],"status":"\(status)","editable":true,
         "remind_minutes":\(remind.map(String.init) ?? "null"),"habit":\(habit.map { "\"\($0)\"" } ?? "null")}
        """
    }

    @Test func remindsBeforeEachTimedPlanUsingItsOwnLeadOrTenMinutes() throws {
        let data = try upcoming(items: [
            item("a", title: "Design review", startsAt: "2026-10-18T05:00:00Z"),                // 14:00
            item("b", title: "Dentist", startsAt: "2026-10-18T06:00:00Z", remind: 60),          // 15:00
            item("c", title: "Breakfast", startsAt: "2026-10-17T22:00:00Z"),                    // already past
            item("d", title: "Done call", startsAt: "2026-10-18T07:00:00Z", status: "done"),
        ].joined(separator: ","))

        let planned = NotificationPlan.plans(data, now: now, calendar: tokyo)

        #expect(planned.map(\.id) == ["tinyme.plan.a", "tinyme.plan.b"])
        #expect(planned[0].fireDate == Date(timeIntervalSince1970: 1_792_299_000))   // 13:50
        #expect(planned[0].body.hasPrefix("In 10 min"))
        #expect(planned[1].fireDate == Date(timeIntervalSince1970: 1_792_299_600))   // 14:00
        #expect(planned[1].body.hasPrefix("In 1 hour"))
        #expect(planned[0].route == .plan("a"))
    }

    @Test func listsEachDaysOpenTasksAtNineWithTheOnesLeftFromEarlierDays() throws {
        let data = try upcoming(
            items: [
                item("t1", kind: "task", title: "Pay rent"),
                item("t2", kind: "task", title: "Apply: iOS Engineer at Mercari"),
                item("t3", kind: "task", title: "Brush teeth", habit: "brush_teeth"),
                item("t4", kind: "task", title: "Call bank", day: "2026-10-19"),
                item("t5", kind: "task", title: "Done thing", status: "done"),
            ].joined(separator: ","),
            overdue: item("o1", kind: "task", title: "Old one", day: "2026-10-16", status: "missed"))

        let planned = NotificationPlan.plans(data, now: now, calendar: tokyo)

        #expect(planned.map(\.id) == ["tinyme.tasks.2026-10-18", "tinyme.tasks.2026-10-19"])
        #expect(planned[0].title == "2 tasks today")
        #expect(planned[0].body == "Pay rent and Apply: iOS Engineer at Mercari\n1 left from an earlier day")
        #expect(planned[1].title == "1 task today")
        #expect(planned[0].route == .upcoming)
    }

    @Test func noTaskListOnceNineHasPassed() throws {
        let data = try upcoming(items: item("t1", kind: "task", title: "Pay rent"))
        let tenAM = now.addingTimeInterval(2 * 3600)
        #expect(NotificationPlan.plans(data, now: tenAM, calendar: tokyo).isEmpty)
    }

    @Test func journalNudgeSkipsADayAlreadyWrittenAndTimesAlreadyGone() {
        let today = LocalDay("2026-10-18")
        let planned = NotificationPlan.journal(minutes: 21 * 60, journaledOn: today, now: now, days: 3, calendar: tokyo)
        #expect(planned.map(\.id) == ["tinyme.journal.2026-10-19", "tinyme.journal.2026-10-20"])

        let lateNight = now.addingTimeInterval(14 * 3600)   // 22:00
        let tonightGone = NotificationPlan.journal(minutes: 21 * 60, journaledOn: nil, now: lateNight, days: 2, calendar: tokyo)
        #expect(tonightGone.map(\.id) == ["tinyme.journal.2026-10-19"])
        #expect(tonightGone.first?.route == .chat)
    }

    @Test func gmailNewsIsOneNotificationCountingEachKind() {
        let found: [NotificationPlan.Found] = [
            .init(key: "payment:1", kind: .payment, line: "¥1,260 at ROCKETNOW JPN"),
            .init(key: "job:1", kind: .job, line: "iOS Engineer at Mercari"),
            .init(key: "job:2", kind: .job, line: "Swift Developer at LINE"),
            .init(key: "job:3", kind: .job, line: "Mobile Lead at Sansan"),
        ]
        let planned = NotificationPlan.mail(found)
        #expect(planned?.title == "1 payment and 3 jobs to review")
        #expect(planned?.body == "¥1,260 at ROCKETNOW JPN\niOS Engineer at Mercari\nSwift Developer at LINE\nand 1 more")
        #expect(planned?.route == .gscan)
        #expect(NotificationPlan.mail([]) == nil)
    }

    @Test func routesSurviveTheTripThroughUserInfo() {
        for route in [NotificationRoute.plan("abc"), .upcoming, .gscan, .chat] {
            #expect(NotificationRoute(userInfo: route.userInfo) == route)
        }
        #expect(NotificationRoute(userInfo: "plan:") == nil)
    }
}
