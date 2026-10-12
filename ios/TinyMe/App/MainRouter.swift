import Foundation
import Observation

/// Where the signed-in app is: which tab, what is pushed on Home, and what is open on top.
@MainActor
@Observable
final class MainRouter {
    enum Tab {
        case home, history
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

    var tab: Tab = .home
    var homePath: [DrillTarget] = []
    var chat: ChatDraft?
    var showsSettings = false

    /// The entry in the detail panel. Kept while the panel slides away, so it doesn't empty mid-animation.
    private(set) var entry: EntryDTO?
    var showsEntry = false

    /// History reloads the next time it appears (set after a chat turn that changed entries).
    var historyIsStale = true
    /// History should drop its chip and search the next time it appears (the Journal card opens it this way).
    var historyNeedsReset = false

    func openChat(draft: String = "") {
        showsEntry = false
        chat = ChatDraft(text: draft)
    }

    func show(_ entry: EntryDTO) {
        self.entry = entry
        showsEntry = true
    }

    /// A tap on a card. Journal is the whole log, so it opens History; any other card opens its entries.
    func open(_ card: CardDTO, range: HomeRange, selectedDate: LocalDay, tap: CardTap) {
        if card.system == true {
            historyNeedsReset = true
            tab = .history
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
