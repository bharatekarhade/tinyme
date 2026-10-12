import Foundation
import Observation

enum HomeRange: String, CaseIterable, Identifiable, Sendable {
    case day, week, month, year

    var id: Self { self }

    var title: String {
        switch self {
        case .day: "Day"
        case .week: "Week"
        case .month: "Month"
        case .year: "Year"
        }
    }

    var subtitle: String {
        switch self {
        case .day: "Here's how your day is going."
        case .week: "Here's how your week is going."
        case .month: "Here's how your month is going."
        case .year: "Here's how your year is going."
        }
    }

    var step: Calendar.Component {
        switch self {
        case .day: .day
        case .week: .weekOfYear
        case .month: .month
        case .year: .year
        }
    }
}

struct HomePeriodOption: Identifiable, Hashable, Sendable {
    let start: LocalDay
    let end: LocalDay
    let title: String
    let dates: String

    var id: LocalDay { start }
}

/// Loads the Home cards for the chosen range from the server.
@MainActor
@Observable
final class HomeModel {
    enum Phase: Equatable {
        /// Nothing to show yet for this range.
        case loading
        case loaded
        case failed(String)
    }

    private static let rangeKey = "home.range"
    private static let dateKey = "home.selectedDate"

    private struct Selection: Hashable {
        let range: HomeRange
        let date: LocalDay
    }

    var range: HomeRange {
        didSet { UserDefaults.standard.set(range.rawValue, forKey: Self.rangeKey) }
    }

    var selectedDate: LocalDay {
        didSet { UserDefaults.standard.set(selectedDate.description, forKey: Self.dateKey) }
    }

    private(set) var phase: Phase = .loading
    private(set) var cards: [CardDTO] = []
    private(set) var suggestions: [SuggestionDTO] = []
    /// A short-lived message when a change couldn't be saved.
    private(set) var banner: String?

    private let api: APIClient
    /// The last result per range/date anchor. Trusted until invalidated or the app comes back.
    private var cache: [Selection: CardListDTO] = [:]
    private var loadTask: Task<Void, Never>?
    private var bannerTask: Task<Void, Never>?

    init(api: APIClient) {
        self.api = api
        range = UserDefaults.standard.string(forKey: Self.rangeKey).flatMap(HomeRange.init(rawValue:)) ?? .week
        let savedDate = UserDefaults.standard.string(forKey: Self.dateKey).flatMap(LocalDay.init(_:)) ?? .today()
        selectedDate = min(savedDate, .today(calendar: Self.calendar))
    }

    var selectedPeriodLabel: String {
        guard let date = selectedDate.date(in: Self.calendar),
              let start = Self.periodStart(date, range: range),
              let option = Self.option(start: start, range: range, offset: 0) else {
            return selectedDate.description
        }
        return option.dates
    }

    var selectedPeriodStart: LocalDay? {
        guard let date = selectedDate.date(in: Self.calendar),
              let start = Self.periodStart(date, range: range) else { return nil }
        return LocalDay(date: start, calendar: Self.calendar)
    }

    /// Calendar-aligned choices, newest first. The selected historical period is always included.
    var periodOptions: [HomePeriodOption] {
        guard let today = LocalDay.today(calendar: Self.calendar).date(in: Self.calendar),
              let todayStart = Self.periodStart(today, range: range),
              let selected = selectedDate.date(in: Self.calendar),
              let selectedStart = Self.periodStart(selected, range: range) else { return [] }

        let distance = Self.periodDistance(from: selectedStart, to: todayStart, range: range)
        let defaults: (minimum: Int, maximum: Int) = switch range {
        case .day: (90, 3_650)
        case .week: (52, 520)
        case .month: (24, 240)
        case .year: (10, 100)
        }
        let count = min(defaults.maximum, max(defaults.minimum, distance + 1))
        return (0..<count).compactMap { offset in
            guard let start = Self.calendar.date(byAdding: range.step, value: -offset, to: todayStart) else { return nil }
            return Self.option(start: start, range: range, offset: offset)
        }
    }

    func select(_ option: HomePeriodOption) {
        selectedDate = option.start
    }

    private static func periodStart(_ date: Date, range: HomeRange) -> Date? {
        switch range {
        case .day: calendar.startOfDay(for: date)
        case .week: calendar.dateInterval(of: .weekOfYear, for: date)?.start
        case .month: calendar.dateInterval(of: .month, for: date)?.start
        case .year: calendar.dateInterval(of: .year, for: date)?.start
        }
    }

    private static func periodDistance(from start: Date, to end: Date, range: HomeRange) -> Int {
        let component: Calendar.Component = switch range {
        case .day: .day
        case .week: .weekOfYear
        case .month: .month
        case .year: .year
        }
        return max(0, calendar.dateComponents([component], from: start, to: end).value(for: component) ?? 0)
    }

    private static func option(start: Date, range: HomeRange, offset: Int) -> HomePeriodOption? {
        guard let nextStart = calendar.date(byAdding: range.step, value: 1, to: start),
              let end = calendar.date(byAdding: .day, value: -1, to: nextStart),
              let firstDay = LocalDay(date: start, calendar: calendar),
              let lastDay = LocalDay(date: end, calendar: calendar) else { return nil }

        let title: String
        title = switch (range, offset) {
        case (.day, 0): "Today"
        case (.day, 1): "Yesterday"
        case (.week, 0): "This week"
        case (.week, 1): "Last week"
        case (.month, 0): "This month"
        case (.month, 1): "Last month"
        case (.year, 0): "This year"
        case (.year, 1): "Last year"
        default: range.title
        }
        let dates: String
        if range == .day {
            dates = start.formatted(.dateTime.weekday(.wide).month(.abbreviated).day().year())
        } else {
            let endAtNoon = lastDay.date(in: calendar) ?? end
            dates = (start..<endAtNoon).formatted(.interval.day().month(.abbreviated).year())
        }
        return HomePeriodOption(start: firstDay, end: lastDay, title: title, dates: dates)
    }

    private static var calendar: Calendar {
        var calendar = Calendar.autoupdatingCurrent
        calendar.firstWeekday = 2 // Cards use ISO weeks, which start on Monday.
        calendar.minimumDaysInFirstWeek = 4
        return calendar
    }

    /// Shows the current range: from the cache when it has it, without asking the server again.
    func show() {
        guard let cached = cache[Selection(range: range, date: selectedDate)] else { return load() }
        cards = cached.cards
        phase = .loaded
    }

    /// The app came back to the front: entries may have changed elsewhere, or the day may have rolled over.
    /// Reloads this range and forgets the others, so they load fresh when next opened.
    func refresh() {
        let selection = Selection(range: range, date: selectedDate)
        cache = cache.filter { $0.key == selection }
        load()
    }

    /// Starts a load for the current range, cancelling any load still running,
    /// so a quick Day → Week → Month never ends on Week's result.
    func load() {
        loadTask?.cancel()
        loadTask = Task { [weak self] in
            await self?.performLoad()
        }
    }

    /// For pull-to-refresh: returns when the load has finished.
    func reload() async {
        load()
        await loadTask?.value
    }

    /// Something changed on the server (a new entry, a new card): drop what's cached and load again.
    func invalidate() {
        cache.removeAll()
        load()
    }

    // MARK: - Remove and reorder

    /// Takes a card off Home at once, and puts it back if the server refuses.
    func remove(_ card: CardDTO) async {
        guard card.system != true, let index = cards.firstIndex(where: { $0.id == card.id }) else { return }
        cards.remove(at: index)
        cache.removeAll()
        do {
            try await api.send(.deleteCard(card.id))
        } catch APIError.server(status: 404, _, _), APIError.http(status: 404) {
            // Already gone on the server: nothing to undo.
        } catch {
            cards.insert(card, at: min(index, cards.count))
            show(banner: "Couldn't remove \(card.title).")
        }
    }

    /// Moves a card to where another one is, and saves the new order.
    func move(_ cardId: String, onto targetId: String) async {
        guard cardId != targetId,
              let from = cards.firstIndex(where: { $0.id == cardId }),
              let to = cards.firstIndex(where: { $0.id == targetId }),
              cards[from].system != true, cards[to].system != true else { return }
        let card = cards.remove(at: from)
        cards.insert(card, at: to)
        cache.removeAll()
        do {
            try await api.send(.cardOrder, body: CardOrderBody(ids: cards.filter { $0.system != true && $0.status == "active" }.map(\.id)))
        } catch {
            show(banner: "Couldn't save the new order.")
            load()
        }
    }

    private struct CardOrderBody: Encodable, Sendable {
        let ids: [String]
    }

    private func show(banner message: String) {
        banner = message
        bannerTask?.cancel()
        bannerTask = Task { [weak self] in
            try? await Task.sleep(for: .seconds(4))
            if !Task.isCancelled {
                self?.banner = nil
            }
        }
    }

    private func performLoad() async {
        let selection = Selection(range: range, date: selectedDate)
        if let cached = cache[selection] {
            cards = cached.cards
            phase = .loaded
        } else {
            cards = []
            phase = .loading
        }

        do {
            async let suggestionsReply: [SuggestionDTO]? = try? api.get(.cardSuggestions)
            let list: CardListDTO = try await api.get(.cards(range: selection.range.rawValue, date: selection.date))
            let newSuggestions = await suggestionsReply
            guard !Task.isCancelled else { return }
            // Kept even if the user has moved to another range meanwhile: it is fresh for when they come back.
            cache[selection] = list
            guard selection == Selection(range: self.range, date: self.selectedDate) else { return }
            cards = list.cards
            if selection.date == .today(calendar: Self.calendar) {
                WidgetSnapshot.publish(cards: list.cards, range: selection.range)
            }
            if let newSuggestions {
                suggestions = newSuggestions
            }
            phase = .loaded
        } catch {
            // A failed refresh keeps the cached cards on screen; a 401 has already signed the user out.
            guard !Task.isCancelled, selection == Selection(range: self.range, date: self.selectedDate), cache[selection] == nil else { return }
            phase = .failed(Self.message(for: error))
        }
    }

    private static func message(for error: Error) -> String {
        switch error {
        case APIError.server(_, _, let detail?): detail
        case APIError.decoding: "Update TinyMe to see your cards."
        case is URLError: "Can't reach your server."
        default: "Something went wrong."
        }
    }
}
