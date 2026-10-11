import Foundation
import Observation

/// The card shown under a reply that created or changed it. One per `card_id`.
@MainActor
@Observable
final class InlineCardModel {
    enum State: Equatable {
        case idle
        case loading
        case loaded(CardDTO)
        /// The card no longer exists (deleted later), so nothing is shown.
        case hidden
    }

    private(set) var state: State = .idle
    private(set) var isActivating = false
    /// True once Add to Home succeeded in this session ("Added to Home" rather than "On Home").
    private(set) var justAdded = false
    private(set) var error: String?

    private let cardId: String
    private let api: APIClient
    private let onActivated: @MainActor () -> Void

    init(cardId: String, api: APIClient, onActivated: @escaping @MainActor () -> Void) {
        self.cardId = cardId
        self.api = api
        self.onActivated = onActivated
    }

    /// Loads the card once. Pass `force` after TinyMe saved it again, to pick up the new version.
    func load(force: Bool = false) async {
        guard state == .idle || (force && state != .loading) else { return }
        let previous = state
        if state == .idle {
            state = .loading
        }
        do {
            let card: CardDTO = try await api.get(.card(cardId))
            state = .loaded(card)
        } catch APIError.server(status: 404, _, _), APIError.http(status: 404) {
            state = .hidden
        } catch {
            // Not conclusive (offline, server busy): keep what was showing and allow another try.
            state = previous == .loading ? .idle : previous
        }
    }

    func activate() async {
        guard case .loaded = state, !isActivating else { return }
        isActivating = true
        error = nil
        defer { isActivating = false }
        do {
            let card: CardDTO = try await api.send(.activateCard(cardId))
            state = .loaded(card)
            justAdded = true
            onActivated()
        } catch APIError.server(status: 404, _, _), APIError.http(status: 404) {
            state = .hidden
        } catch {
            self.error = "Couldn't add it to Home. Try again."
        }
    }
}
