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

    var range: HomeRange {
        didSet { UserDefaults.standard.set(range.rawValue, forKey: Self.rangeKey) }
    }

    private(set) var phase: Phase = .loading
    private(set) var cards: [CardDTO] = []
    private(set) var suggestions: [SuggestionDTO] = []

    private let api: APIClient
    /// Switching back to a range shows its last result at once, then refreshes it.
    private var cache: [HomeRange: CardListDTO] = [:]
    private var loadTask: Task<Void, Never>?

    init(api: APIClient) {
        self.api = api
        range = UserDefaults.standard.string(forKey: Self.rangeKey).flatMap(HomeRange.init(rawValue:)) ?? .week
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

    private func performLoad() async {
        let range = range
        if let cached = cache[range] {
            cards = cached.cards
            phase = .loaded
        } else {
            cards = []
            phase = .loading
        }

        do {
            async let suggestionsReply: [SuggestionDTO]? = try? api.get(.cardSuggestions)
            let list: CardListDTO = try await api.get(.cards(range: range.rawValue))
            let newSuggestions = await suggestionsReply
            guard !Task.isCancelled, range == self.range else { return }
            cache[range] = list
            cards = list.cards
            if let newSuggestions {
                suggestions = newSuggestions
            }
            phase = .loaded
        } catch {
            // A failed refresh keeps the cached cards on screen; a 401 has already signed the user out.
            guard !Task.isCancelled, range == self.range, cache[range] == nil else { return }
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
