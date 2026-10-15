import Foundation
import Observation

/// One bubble in the chat.
enum ChatItem: Identifiable, Equatable {
    enum UserStatus: Equatable {
        /// In the outbox, held up by the connection.
        case waiting
        case sending
        case sent
        /// The server refused it. Retrying resends it with the same `client_msg_id`.
        case failed(message: String?)
    }

    enum AssistantStatus: Equatable {
        case streaming
        case done
        case failed(message: String, retryable: Bool)
    }

    case user(id: String, text: String, photoData: Data?, photo: ChatPhotoDTO?, status: UserStatus, createdAt: Date)
    case assistant(id: String, text: String, actions: [ActionDTO], status: AssistantStatus, createdAt: Date)

    var id: String {
        switch self {
        case .user(let id, _, _, _, _, _), .assistant(let id, _, _, _, _): id
        }
    }

    var createdAt: Date {
        switch self {
        case .user(_, _, _, _, _, let date), .assistant(_, _, _, _, let date): date
        }
    }
}

/// Today's conversation: the server's history, then whatever is still in the outbox, with the reply as it streams.
/// Sending goes through the outbox, so a message typed offline waits there instead of failing.
@MainActor
@Observable
final class ChatModel {
    var composer: String
    var pendingPhotoData: Data?

    private(set) var isLoadingHistory = false
    private(set) var historyError: String?
    private(set) var locationPermissionPromptVersion = 0

    /// What the server has for today, plus the turns that finished on this screen since.
    private var history: [ChatItem] = []
    private var queue = OutboxSnapshot()
    private var live: LiveReply?
    /// Goes up when a turn ends, so a history load that was already under way isn't shown stale.
    private var historyVersion = 0

    private let api: APIClient
    private let outbox: OutboxWorker
    private let onDataChanged: @MainActor () -> Void
    private let reconnectDelay: Duration
    /// One model per card shown in the chat, kept so scrolling doesn't reload them.
    @ObservationIgnored private var inlineCards: [String: InlineCardModel] = [:]

    /// The reply to the message the outbox is sending.
    private struct LiveReply {
        let messageId: UUID
        var text = ""
        var actions: [ActionDTO] = []
        var status: ChatItem.AssistantStatus = .streaming
        let createdAt = Date.now
        /// False when chat opened mid-reply and missed its start: the server's copy is shown once it is done.
        var isWhole = true

        var item: ChatItem {
            .assistant(id: "reply-" + ChatModel.bubbleId(messageId), text: text, actions: actions, status: status, createdAt: createdAt)
        }
    }

    /// - Parameters:
    ///   - draft: text to start the composer with (a tapped suggestion). It is not sent.
    ///   - onDataChanged: called when a card is added to Home from the chat, so Home can reload.
    init(
        api: APIClient,
        outbox: OutboxWorker,
        draft: String = "",
        reconnectDelay: Duration = .seconds(2),
        onDataChanged: @escaping @MainActor () -> Void = {}
    ) {
        self.api = api
        self.outbox = outbox
        self.composer = draft
        self.reconnectDelay = reconnectDelay
        self.onDataChanged = onDataChanged
    }

    var items: [ChatItem] {
        var result = history
        let waiting = queue.entries.filter { !isInHistory($0) }
        // The history already shows the message being answered: its reply follows straight on.
        if let live, !waiting.contains(where: { $0.id == live.messageId }) {
            result.append(live.item)
        }
        for entry in waiting {
            result.append(.user(id: Self.bubbleId(entry.id), text: entry.text, photoData: entry.photoData,
                                photo: nil, status: status(of: entry), createdAt: entry.createdAt))
            if let live, live.messageId == entry.id {
                result.append(live.item)
            }
        }
        return result
    }

    var isTurnRunning: Bool {
        live?.status == .streaming
    }

    func retryNearbySearch() {
        composer = "Show my saved places nearby"
        send()
    }

    /// Always possible, online or not: a new message queues behind the others.
    var canSend: Bool {
        !composer.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || pendingPhotoData != nil
    }

    // MARK: - Inline cards

    func inlineCard(for cardId: String) -> InlineCardModel {
        if let existing = inlineCards[cardId] {
            return existing
        }
        let model = InlineCardModel(cardId: cardId, api: api, onActivated: onDataChanged)
        inlineCards[cardId] = model
        return model
    }

    /// "Change goal" on an inline card: starts the sentence, the user finishes it.
    func draftGoalChange(for card: CardDTO) {
        composer = "Change the \(card.title) goal to "
    }

    // MARK: - History

    /// Replaces the history with what the server has for today.
    func loadHistory() async {
        guard !isLoadingHistory else { return }
        isLoadingHistory = true
        defer { isLoadingHistory = false }
        var version: Int
        repeat {
            version = historyVersion
            do {
                let day: ConversationDayDTO = try await api.get(.conversation())
                guard version == historyVersion else { continue }   // a turn ended meanwhile: ask again
                history = day.messages.compactMap(Self.item(from:))
                historyError = nil
            } catch {
                historyError = Self.message(for: error)
                return
            }
        } while version != historyVersion
    }

    private static func item(from message: MessageDTO) -> ChatItem? {
        switch message.role {
        case "user":
            .user(id: message.id, text: message.content, photoData: nil,
                  photo: message.attachments.first, status: .sent, createdAt: message.createdAt)
        case "assistant":
            .assistant(id: message.id, text: message.content, actions: message.actions, status: .done, createdAt: message.createdAt)
        default:
            nil
        }
    }

    /// True once the server's history shows this message, so it isn't drawn twice.
    private func isInHistory(_ entry: OutboxEntry) -> Bool {
        guard let serverId = entry.serverMessageId else { return false }
        return history.contains { $0.id == serverId }
    }

    // MARK: - Sending

    func send() {
        guard canSend else { return }
        let text = composer.trimmingCharacters(in: .whitespacesAndNewlines)
        let photo = pendingPhotoData
        let message = text.isEmpty && photo != nil ? "What is in this photo?" : text
        composer = ""
        pendingPhotoData = nil
        enqueue(message, photoData: photo)
    }

    /// Retries a failed bubble: a message the server refused goes again as itself;
    /// a reply that failed on the server is asked for again as a new message.
    func retry(_ itemId: String) {
        if let entry = entry(for: itemId) {
            let photoData = entry.photoData.flatMap(ChatPhotoCompressor.optimizedJPEG) ?? entry.photoData
            Task { await outbox.retry(entry.id, photoData: photoData) }
            return
        }
        let items = items
        guard let index = items.firstIndex(where: { $0.id == itemId }),
              case .assistant(_, _, _, .failed(_, retryable: true), _) = items[index],
              let text = items[..<index].reversed().lazy.compactMap(Self.userText).first else { return }
        enqueue(text)
    }

    /// Drops a message the server refused.
    func delete(_ itemId: String) {
        guard let entry = entry(for: itemId) else { return }
        Task { await outbox.delete(entry.id) }
    }

    private func enqueue(_ text: String, photoData: Data? = nil) {
        // Stamped here, so two quick messages keep their order.
        let id = UUID()
        let date = Date.now
        Task { await outbox.enqueue(id: id, text: text, photoData: photoData, at: date) }
    }

    private func entry(for itemId: String) -> OutboxEntry? {
        queue.entries.first { Self.bubbleId($0.id) == itemId }
    }

    private static func userText(_ item: ChatItem) -> String? {
        if case .user(_, let text, _, _, _, _) = item { text } else { nil }
    }

    nonisolated fileprivate static func bubbleId(_ id: UUID) -> String {
        id.uuidString.lowercased()
    }

    private func status(of entry: OutboxEntry) -> ChatItem.UserStatus {
        switch entry.status {
        case .failed: .failed(message: entry.lastError)
        case .sent: .sent
        case .queued where !queue.isOnline: .waiting
        // A try has already failed: it is waiting, whether or not another try is under way.
        case .queued, .sending: entry.attempts > 0 ? .waiting : .sending
        }
    }

    // MARK: - Following the outbox

    /// Keeps the chat in step with the outbox. Runs until the task is cancelled (the view goes away).
    func follow() async {
        let (snapshot, events) = await outbox.subscribe()
        queue = snapshot
        if let answering = snapshot.entries.first(where: { $0.status == .sent }) {
            live = LiveReply(messageId: answering.id, isWhole: false)
        }
        for await event in events {
            switch event {
            case .queue(let snapshot):
                queue = snapshot
            case .reply(let id, let event):
                apply(event, to: id)
            case .finished(let entry, let outcome, let snapshot):
                finish(entry, outcome, snapshot)
            }
        }
    }

    private func apply(_ event: StreamEvent, to id: UUID) {
        if live?.messageId != id {
            live = LiveReply(messageId: id)
        }
        guard var reply = live, reply.isWhole else { return }
        switch event {
        case .actionDone(let tool, let summary, let isError, let ref):
            let action = ActionDTO(tool: tool, summary: summary, isError: isError, ref: ref)
            // TinyMe saved a card that's already on screen: show its new version.
            if let cardId = action.cardId, let shown = inlineCards[cardId] {
                Task { await shown.load(force: true) }
            }
            if action.needsLocationPermission {
                locationPermissionPromptVersion += 1
            }
            reply.actions.append(action)
        case .text(let chunk):
            reply.text += chunk
        case .error(_, let message, let retryable):
            // `failed` is followed by `done`: the turn stays failed.
            reply.status = .failed(message: message, retryable: retryable)
        case .done:
            if reply.status == .streaming {
                reply.status = .done
            }
        case .turnStarted, .attachments, .clientAction, .needsConfirmation, .unknown:
            break   // not part of this phase
        }
        live = reply
    }

    /// The outbox has let go of a message: keep its bubbles on screen as part of the history.
    private func finish(_ entry: OutboxEntry, _ outcome: OutboxWorker.Outcome, _ snapshot: OutboxSnapshot) {
        var reply = live?.messageId == entry.id ? live : nil
        if reply != nil {
            live = nil
        }
        if !isInHistory(entry) {
            history.append(.user(id: Self.bubbleId(entry.id), text: entry.text, photoData: entry.photoData,
                                 photo: nil, status: .sent, createdAt: entry.createdAt))
        }
        queue = snapshot
        historyVersion += 1

        switch outcome {
        case .completed:
            if let reply, reply.isWhole {
                history.append(reply.item)
            } else {
                Task { await loadHistory() }
            }
        case .lost:
            // Shown until the server's copy loads. The server finishes the turn anyway, so give it a moment.
            reply?.status = .failed(message: "Lost the connection. Pull down to reload.", retryable: false)
            if let reply {
                history.append(reply.item)
            }
            Task {
                try? await Task.sleep(for: reconnectDelay)
                await loadHistory()
            }
        case .duplicate:
            // The server already has this message: its copy of the day is the truth.
            Task { await loadHistory() }
        }
    }

    private static func message(for error: Error) -> String {
        switch error {
        case APIError.server(_, _, let detail?): detail
        case APIError.decoding: "Update TinyMe to see this conversation."
        case is URLError: "Can't reach your server."
        default: "Something went wrong."
        }
    }
}
