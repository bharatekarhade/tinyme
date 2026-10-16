import Foundation
import Observation

/// Where the signed-in app is: which tab, what is pushed on Home, and what is open on top.
@MainActor
@Observable
final class MainRouter {
    enum Tab {
        case home, upcoming, gscan
    }

    /// What chat opens with: empty from the + button, or text to start from (a suggestion, "Change my 12:40 lunch…").
    struct ChatDraft: Identifiable {
        let id = UUID()
        var text = ""
    }

    /// The entries behind a card, or behind one piece of it.
    struct DrillTarget: Hashable {
        var cardId: String
        var range: HomeRange
        var selectedDate: LocalDay
        var block: Int?
        var item: Int?
        var day: LocalDay?
        var key: String?
    }

    struct PlaceTarget: Identifiable, Hashable {
        let id: String
    }

    var tab: Tab = .home
    var homePath: [DrillTarget] = []
    var chat: ChatDraft?
    var placeTarget: PlaceTarget?
    private(set) var pendingPlaceID: String?
    var showsSettings = false
    /// History, your whole log, opened over the tabs from Profile or the Journal card.
    var showsHistory = false

    /// The entry in the detail panel. Kept while the panel slides away, so it doesn't empty mid-animation.
    private(set) var entry: EntryDTO?
    var showsEntry = false

    /// History reloads the next time it appears (set after a chat turn that changed entries).
    var historyIsStale = true
    /// History should drop its chip and search the next time it appears (the Journal card opens it this way).
    var historyNeedsReset = false

    func openHistory() {
        showsEntry = false
        showsHistory = true
    }

    func openChat(draft: String = "") {
        showsEntry = false
        chat = ChatDraft(text: draft)
    }

    func show(_ entry: EntryDTO) {
        self.entry = entry
        showsEntry = true
    }

    func openPlace(_ id: String) {
        placeTarget = PlaceTarget(id: id)
    }

    /// A chat full-screen cover must finish dismissing before the place sheet is presented.
    func openPlaceFromChat(_ id: String) {
        pendingPlaceID = id
        chat = nil
    }

    func presentPendingPlace() {
        guard let id = pendingPlaceID else { return }
        pendingPlaceID = nil
        openPlace(id)
    }

    /// A tap on a card. Journal is the whole log, so it opens History; any other card opens its entries.
    func open(_ card: CardDTO, range: HomeRange, selectedDate: LocalDay, tap: CardTap) {
        if card.system == true {
            historyNeedsReset = true
            showsHistory = true
        } else {
            homePath.append(DrillTarget(cardId: card.id, range: range, selectedDate: selectedDate,
                                        block: tap.block, item: tap.item, day: tap.day, key: tap.key))
        }
    }
}

/// Which part of a card was tapped. All nil is the card itself: the server picks its headline block.
struct CardTap {
    var block: Int?
    var item: Int?
    var day: LocalDay?
    var key: String?
}
