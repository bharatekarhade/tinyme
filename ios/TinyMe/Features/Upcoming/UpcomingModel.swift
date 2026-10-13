import Foundation
import Observation

private struct MoveOccurrenceRequest: Encodable, Sendable {
    let day: LocalDay
    let time: String
}

private struct EditScheduleRequest: Encodable, Sendable {
    let day: LocalDay
    let time: String
    let endTime: String?
    let endDay: LocalDay?

    private enum CodingKeys: String, CodingKey {
        case day, time
        case endTime = "end_time"
        case endDay = "end_day"
    }
}

@MainActor
@Observable
final class UpcomingModel {
    struct DeletedPlan: Identifiable {
        let id: String
        let title: String
        let canUndo: Bool
    }

    enum Phase: Equatable {
        case loading, loaded, failed(String)
    }

    private(set) var phase: Phase = .loading
    private(set) var upcoming: UpcomingDTO?
    private(set) var weekStart: LocalDay
    private(set) var selectedDay: LocalDay
    private(set) var workingIds: Set<String> = []
    private(set) var errorMessage: String?
    private(set) var deletedPlan: DeletedPlan?

    private let api: APIClient
    private var loadTask: Task<Void, Never>?

    init(api: APIClient) {
        self.api = api
        let today = LocalDay.today()
        selectedDay = today
        weekStart = Self.monday(of: today) ?? today
    }

    var weekDays: [UpcomingDTO.Day] {
        guard let upcoming else { return [] }
        return upcoming.days
    }

    var selectedItems: [OccurrenceDTO] {
        (upcoming?.items ?? []).filter { $0.day == selectedDay }
    }

    var allDayItems: [OccurrenceDTO] {
        let current = selectedItems.filter { $0.allDay || ($0.isTask && $0.startsAt == nil) }
        let overdue = selectedDay == upcoming?.today ? (upcoming?.overdue ?? []) : []
        return (current + overdue).sorted(by: Self.occurrenceOrder)
    }

    var timedItems: [OccurrenceDTO] {
        selectedItems.filter { !$0.allDay && $0.startsAt != nil }
            .sorted { ($0.startsAt ?? .distantFuture) < ($1.startsAt ?? .distantFuture) }
    }

    var overdueItems: [OccurrenceDTO] {
        guard selectedDay == upcoming?.today else { return [] }
        return upcoming?.overdue ?? []
    }

    var laterItems: [OccurrenceDTO] {
        (upcoming?.items ?? [])
            .filter { $0.day > selectedDay }
            .sorted(by: Self.occurrenceOrder)
    }

    var isCurrentWeek: Bool {
        Self.monday(of: LocalDay.today()) == weekStart
    }

    var weekCountLabel: String {
        guard let nextWeek = Self.adding(days: 7, to: Self.monday(of: LocalDay.today()) ?? weekStart) else {
            return "things this week"
        }
        if weekStart == nextWeek { return "things next week" }
        if let previousWeek = Self.adding(days: -7, to: Self.monday(of: LocalDay.today()) ?? weekStart), weekStart == previousWeek {
            return "things last week"
        }
        return "things this week"
    }

    func load() {
        loadTask?.cancel()
        phase = upcoming == nil ? .loading : phase
        let start = weekStart
        guard let end = Self.adding(days: 6, to: start) else { return }
        loadTask = Task { [weak self, api] in
            do {
                let result: UpcomingDTO = try await api.get(.upcoming(from: start, to: end))
                guard !Task.isCancelled, let self, self.weekStart == start else { return }
                self.upcoming = result
                self.phase = .loaded
                self.errorMessage = nil
            } catch {
                guard !Task.isCancelled, let self, self.weekStart == start else { return }
                self.phase = .failed("Couldn't load your plans. Check your connection and try again.")
            }
        }
    }

    func refresh() async {
        load()
        await loadTask?.value
    }

    func invalidate() {
        load()
    }

    func select(_ day: LocalDay) {
        selectedDay = day
    }

    func moveDay(by offset: Int) {
        guard let next = Self.adding(days: offset, to: selectedDay) else { return }
        guard let end = Self.adding(days: 6, to: weekStart) else { return }
        if next < weekStart || next > end {
            let start = Self.monday(of: next) ?? next
            weekStart = start
            selectedDay = next
            upcoming = nil
            phase = .loading
            load()
        } else {
            selectedDay = next
        }
    }

    func moveWeek(by offset: Int) {
        guard let next = Self.adding(days: offset * 7, to: weekStart) else { return }
        weekStart = next
        guard let last = Self.adding(days: 6, to: next) else { return }
        let today = LocalDay.today()
        selectedDay = today >= next && today <= last ? today : next
        upcoming = nil
        phase = .loading
        load()
    }

    /// Opens the week containing the next occurrence touched by a plan tool.
    func focus(planId: String) {
        let api = self.api
        Task { [weak self, api] in
            guard let self else { return }
            let item: OccurrenceDTO?
            if let visible = self.upcoming?.items.first(where: { $0.planId == planId }) {
                item = visible
            } else if let end = Self.adding(days: 61, to: LocalDay.today()) {
                let range: UpcomingDTO? = try? await api.get(.upcoming(from: LocalDay.today(), to: end))
                item = range?.items.filter { $0.planId == planId }.sorted(by: Self.occurrenceOrder).first
            } else {
                item = nil
            }
            guard let item, let start = Self.monday(of: item.day) else { return }
            self.weekStart = start
            self.selectedDay = item.day
            self.upcoming = nil
            self.phase = .loading
            self.load()
        }
    }

    func markDone(_ item: OccurrenceDTO) {
        guard let planId = item.planId else { return }
        let api = self.api
        mutate(item) {
            if item.isDone {
                try await api.send(.planUndoDone(planId, day: item.actionDay))
            } else {
                try await api.send(.planDone(planId, day: item.actionDay))
            }
        }
    }

    func skip(_ item: OccurrenceDTO) {
        guard let planId = item.planId else { return }
        let api = self.api
        mutate(item) { try await api.send(.planSkip(planId, day: item.actionDay)) }
    }

    func delete(_ item: OccurrenceDTO) {
        guard let planId = item.planId else { return }
        let api = self.api
        mutate(item) {
            try await api.send(.deletePlan(planId))
            guard !item.isRepeating else { return }
            let toast = DeletedPlan(id: planId, title: item.title, canUndo: true)
            self.deletedPlan = toast
            self.dismissToastAfterDelay(id: toast.id)
        }
    }

    func undoDelete() {
        guard let deletedPlan else { return }
        let api = self.api
        errorMessage = nil
        Task {
            do {
                try await api.send(.restorePlan(deletedPlan.id))
                self.deletedPlan = nil
                load()
            } catch {
                errorMessage = "Couldn't restore this plan. Please try again."
            }
        }
    }

    func dismissDeleteToast() {
        deletedPlan = nil
    }

    private func dismissToastAfterDelay(id: String) {
        Task { [weak self] in
            try? await Task.sleep(for: .seconds(5))
            guard let self, self.deletedPlan?.id == id else { return }
            self.deletedPlan = nil
        }
    }

    func announceError(_ message: String) {
        errorMessage = message
    }

    func move(_ item: OccurrenceDTO, to day: LocalDay, time: String, allDays: Bool,
              completion: (@MainActor @Sendable (Bool) -> Void)? = nil) {
        guard let planId = item.planId else { completion?(false); return }
        let api = self.api
        let duration = item.startsAt.flatMap { start in item.endsAt.map { Int($0.timeIntervalSince(start) / 60) } }
        let endTotal = duration.map { Self.minutes(time) + $0 }
        let endTime = endTotal.map { Self.time($0 % 1440) }
        let endDay: LocalDay? = endTotal.flatMap { $0 >= 1440 ? Self.adding(days: 1, to: day) : nil }
        mutate(item, completion: completion) {
            if allDays {
                try await api.send(.movePlanSeries(planId), body: EditScheduleRequest(
                    day: day, time: time, endTime: endTime, endDay: endDay))
            } else {
                let _: OccurrenceDTO = try await api.send(.movePlanOccurrence(planId, occurrenceDay: item.actionDay),
                    body: MoveOccurrenceRequest(day: day, time: time))
            }
        }
    }

    private func mutate(_ item: OccurrenceDTO, completion: (@MainActor @Sendable (Bool) -> Void)? = nil,
                        operation: @escaping @MainActor () async throws -> Void) {
        guard !workingIds.contains(item.id) else { completion?(false); return }
        workingIds.insert(item.id)
        errorMessage = nil
        Task {
            defer { workingIds.remove(item.id) }
            do {
                try await operation()
                load()
                await loadTask?.value
                completion?(true)
            } catch {
                errorMessage = "Couldn't update this plan. Please try again."
                completion?(false)
            }
        }
    }

    private static func occurrenceOrder(_ lhs: OccurrenceDTO, _ rhs: OccurrenceDTO) -> Bool {
        if lhs.day != rhs.day { return lhs.day < rhs.day }
        return (lhs.startTime ?? "") < (rhs.startTime ?? "")
    }

    private static func minutes(_ value: String) -> Int {
        let parts = value.split(separator: ":").compactMap { Int($0) }
        guard parts.count == 2 else { return 0 }
        return parts[0] * 60 + parts[1]
    }

    private static func time(_ minutes: Int) -> String {
        String(format: "%02d:%02d", minutes / 60, minutes % 60)
    }

    private static func monday(of day: LocalDay) -> LocalDay? {
        var calendar = Calendar.current
        calendar.firstWeekday = 2
        guard let date = day.date(in: calendar),
              let interval = calendar.dateInterval(of: .weekOfYear, for: date) else { return nil }
        return LocalDay(date: interval.start, calendar: calendar)
    }

    private static func adding(days: Int, to day: LocalDay) -> LocalDay? {
        var calendar = Calendar.current
        calendar.timeZone = .current
        guard let date = day.date(in: calendar),
              let shifted = calendar.date(byAdding: .day, value: days, to: date) else { return nil }
        return LocalDay(date: shifted, calendar: calendar)
    }
}
