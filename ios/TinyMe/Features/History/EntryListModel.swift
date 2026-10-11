import Foundation
import Observation

/// One list for two jobs: the whole log (History), or the entries behind a card's number (drill-down).
@MainActor
@Observable
final class EntryListModel {
    enum Source: Equatable {
        /// Everything, newest first. `kind` nil is "All".
        case history(kind: String?, query: String)
        /// The entries behind one block of a card. The server decides which ones, so they add up to the card.
        case card(id: String, range: HomeRange, block: Int?, item: Int?, day: LocalDay?, key: String?)
    }

    enum Phase: Equatable {
        case loading
        case loaded
        case failed(String)
    }

    struct DaySection: Identifiable {
        let day: LocalDay
        let title: String
        var entries: [EntryDTO]
        /// The day's total for a drill-down; nil in History, where the header counts rows instead.
        var total: Double?

        var id: LocalDay { day }
    }

    /// What a drill-down shows above its list. Nil for History.
    struct Header: Equatable {
        let title: String
        let total: Double?
        let unit: String?
        /// "This month"
        let period: String
        /// "1–31 Oct"
        let dates: String?
        /// The segment or day the list is narrowed to: "Food", "Thu 8 Oct".
        let filter: String?
    }

    private(set) var source: Source
    private(set) var sections: [DaySection] = []
    private(set) var header: Header?
    private(set) var phase: Phase = .loading
    private(set) var isLoadingMore = false
    /// True when a load has finished at least once, so appearing again doesn't flash a spinner.
    private(set) var hasLoaded = false

    private let api: APIClient
    private var nextCursor: String?
    private var dayTotals: [LocalDay: Double] = [:]
    private var loadTask: Task<Void, Never>?
    private var debounceTask: Task<Void, Never>?

    init(api: APIClient, source: Source) {
        self.api = api
        self.source = source
    }

    var isEmpty: Bool { sections.isEmpty }

    /// The unit day totals are written in (a drill-down's card unit).
    var unit: String? { header?.unit }

    var query: String {
        if case .history(_, let query) = source { query } else { "" }
    }

    var kind: String? {
        if case .history(let kind, _) = source { kind } else { nil }
    }

    // MARK: - Loading

    /// Starts over from page 1, cancelling whatever was loading (fast chip taps, fast typing).
    func reload() {
        debounceTask?.cancel()
        loadTask?.cancel()
        nextCursor = nil
        isLoadingMore = false
        if sections.isEmpty {
            phase = .loading
        }
        loadTask = Task { [weak self] in
            await self?.loadPage(replacing: true)
        }
    }

    /// For pull-to-refresh: returns when page 1 is in.
    func refresh() async {
        reload()
        await loadTask?.value
    }

    /// Loads the next page once one of the last five rows comes on screen.
    func loadMoreIfNeeded(_ entry: EntryDTO) {
        guard nextCursor != nil, !isLoadingMore, phase == .loaded else { return }
        let tail = sections.flatMap(\.entries).suffix(5)
        guard tail.contains(where: { $0.id == entry.id }) else { return }
        isLoadingMore = true
        loadTask = Task { [weak self] in
            await self?.loadPage(replacing: false)
        }
    }

    // MARK: - Filters

    /// Search text: waits 300 ms after the last keystroke, then reloads.
    func setQuery(_ text: String) {
        guard case .history(let kind, let current) = source, text != current else { return }
        source = .history(kind: kind, query: text)
        debounceTask?.cancel()
        debounceTask = Task { [weak self] in
            try? await Task.sleep(for: .milliseconds(300))
            guard !Task.isCancelled else { return }
            self?.reload()
        }
    }

    func setKind(_ kind: String?) {
        guard case .history(let current, let query) = source, kind != current else { return }
        source = .history(kind: kind, query: query)
        sections = []
        reload()
    }

    /// History back to "All" with no search (the Journal card opens it this way).
    func resetFilters() {
        guard case .history(let kind, let query) = source, kind != nil || !query.isEmpty else { return }
        source = .history(kind: nil, query: "")
        sections = []
        reload()
    }

    /// Drill-down: drop the segment or day, back to the whole card block.
    func clearFilter() {
        guard case .card(let id, let range, let block, let item, let day, let key) = source, day != nil || key != nil else { return }
        source = .card(id: id, range: range, block: block, item: item, day: nil, key: nil)
        sections = []
        reload()
    }

    // MARK: - Pages

    private func loadPage(replacing: Bool) async {
        let source = source
        let cursor = replacing ? nil : nextCursor
        do {
            let entries: [EntryDTO]
            let next: String?
            switch source {
            case .history(let kind, let query):
                let page: EntryPageDTO = try await api.get(.entries(kind: kind, query: query, cursor: cursor))
                entries = page.entries
                next = page.nextCursor
                guard !Task.isCancelled, source == self.source else { return }
            case .card(let id, let range, let block, let item, let day, let key):
                let page: CardEntriesDTO = try await api.get(
                    .cardEntries(id, range: range.rawValue, block: block, item: item, day: day, key: key, cursor: cursor)
                )
                entries = page.entries
                next = page.nextCursor
                guard !Task.isCancelled, source == self.source else { return }
                header = Self.header(from: page)
                if let totals = page.dayTotals {
                    dayTotals = Dictionary(totals.compactMap { total in total.value.map { (total.day, $0) } }, uniquingKeysWith: { first, _ in first })
                } else if replacing {
                    dayTotals = [:]
                }
            }
            append(entries, replacing: replacing)
            nextCursor = next
            phase = .loaded
            hasLoaded = true
            isLoadingMore = false
        } catch {
            guard !Task.isCancelled, source == self.source else { return }
            isLoadingMore = false
            // A failed "load more" keeps what's on screen; only a failed first page shows the error.
            if replacing || sections.isEmpty {
                phase = .failed(Self.message(for: error))
            }
        }
    }

    /// Groups by day. A new page continues the last section when the day matches.
    private func append(_ entries: [EntryDTO], replacing: Bool) {
        var result = replacing ? [] : sections
        for entry in entries {
            if let last = result.indices.last, result[last].day == entry.localDay {
                result[last].entries.append(entry)
            } else {
                result.append(DaySection(
                    day: entry.localDay,
                    title: Self.title(for: entry.localDay),
                    entries: [entry],
                    total: dayTotals[entry.localDay]
                ))
            }
        }
        sections = result
    }

    // MARK: - Wording

    /// "Today", "Yesterday", "Mon 12 Oct" within the last week, "12 Oct" this year, "12 Oct 2025" before that.
    static func title(for day: LocalDay, today: Date = .now, calendar: Calendar = .current) -> String {
        guard let date = day.date(in: calendar) else { return day.description }
        let daysAgo = calendar.dateComponents([.day], from: calendar.startOfDay(for: date), to: calendar.startOfDay(for: today)).day ?? 0
        switch daysAgo {
        case 0: return "Today"
        case 1: return "Yesterday"
        case 2..<7: return date.formatted(.dateTime.weekday(.abbreviated).day().month(.abbreviated))
        default:
            let sameYear = calendar.component(.year, from: date) == calendar.component(.year, from: today)
            return sameYear ? date.formatted(.dateTime.day().month(.abbreviated)) : date.formatted(.dateTime.day().month(.abbreviated).year())
        }
    }

    private static func header(from page: CardEntriesDTO) -> Header {
        let period: String = switch page.period {
        case "today": "Today"
        case "this_week": "This week"
        case "this_month": "This month"
        case "this_year": "This year"
        case "all_time": "All time"
        case "day": page.filter?.day.map { title(for: $0) } ?? "One day"
        default: EntryPresentation.humanized(page.period)
        }
        var filter: String?
        if let key = page.filter?.key {
            filter = key.capitalizedFirst
        } else if let day = page.filter?.day, let date = day.date() {
            filter = date.formatted(.dateTime.weekday(.abbreviated).day().month(.abbreviated))
        }
        return Header(
            title: page.card.title,
            total: page.total,
            unit: page.block.unit,
            period: period,
            dates: dates(from: page.from, to: page.to),
            filter: filter
        )
    }

    /// "11 Oct", "5–11 Oct", "28 Sep – 4 Oct"
    private static func dates(from: LocalDay?, to: LocalDay?) -> String? {
        guard let from, let to, let start = from.date(), let end = to.date() else { return nil }
        if from == to { return start.formatted(.dateTime.day().month(.abbreviated)) }
        // Written the way the phone's region writes a span: "1–31 Oct", "Oct 1 – 31".
        return (start..<end).formatted(.interval.day().month(.abbreviated))
    }

    private static func message(for error: Error) -> String {
        switch error {
        case APIError.server(_, _, let detail?): detail
        case APIError.decoding: "Update TinyMe to see this."
        case is URLError: "Can't reach your server."
        default: "Something went wrong."
        }
    }
}
