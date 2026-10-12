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
    /// A short-lived message when a change couldn't be saved.
    private(set) var banner: String?

    private let api: APIClient
    /// The last result per range. Trusted until something changes it (`invalidate`) or the app comes back (`refresh`).
    private var cache: [HomeRange: CardListDTO] = [:]
    private var loadTask: Task<Void, Never>?
    private var bannerTask: Task<Void, Never>?

    init(api: APIClient) {
        self.api = api
        range = UserDefaults.standard.string(forKey: Self.rangeKey).flatMap(HomeRange.init(rawValue:)) ?? .week
    }

    /// Shows the current range: from the cache when it has it, without asking the server again.
    func show() {
        guard let cached = cache[range] else { return load() }
        cards = cached.cards
        phase = .loaded
    }

    /// The app came back to the front: entries may have changed elsewhere, or the day may have rolled over.
    /// Reloads this range and forgets the others, so they load fresh when next opened.
    func refresh() {
        cache = cache.filter { $0.key == range }
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
            guard !Task.isCancelled else { return }
            // Kept even if the user has moved to another range meanwhile: it is fresh for when they come back.
            cache[range] = list
            guard range == self.range else { return }
            cards = list.cards
            WidgetSnapshot.publish(cards: list.cards, range: range)
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
