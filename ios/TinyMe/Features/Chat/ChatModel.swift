import Foundation
import Observation

/// One bubble in the chat.
enum ChatItem: Identifiable, Equatable {
    enum UserStatus: Equatable {
        case sending
        case sent
        /// Never reached the server. Retrying resends it with the same `client_msg_id`.
        case failed
    }

    enum AssistantStatus: Equatable {
        case streaming
        case done
        case failed(message: String, retryable: Bool)
    }

    case user(id: String, text: String, status: UserStatus, createdAt: Date)
    case assistant(id: String, text: String, actions: [ActionDTO], status: AssistantStatus, createdAt: Date)

    var id: String {
        switch self {
        case .user(let id, _, _, _), .assistant(let id, _, _, _, _): id
        }
    }

    var createdAt: Date {
        switch self {
        case .user(_, _, _, let date), .assistant(_, _, _, _, let date): date
        }
    }
}

/// Today's conversation: loads the history, sends messages and follows the streamed reply.
@MainActor
@Observable
final class ChatModel {
    var composer: String

    private(set) var items: [ChatItem] = []
    /// One turn at a time: the server answers `busy` to a second message mid-turn.
    private(set) var isTurnRunning = false
    private(set) var isLoadingHistory = false
    private(set) var historyError: String?

    private let api: APIClient
    private let stream: StreamClient
    private let onDataChanged: @MainActor () -> Void
    private let reconnectDelay: Duration
    /// One model per card shown in the chat, kept so scrolling doesn't reload them.
    @ObservationIgnored private var inlineCards: [String: InlineCardModel] = [:]
    /// What each pending message was sent with, so a retry reuses the same `client_msg_id`.
    private var pending: [String: (clientMsgId: UUID, text: String)] = [:]

    /// - Parameters:
    ///   - draft: text to start the composer with (a tapped suggestion). It is not sent.
    ///   - onDataChanged: called after a turn that changed entries or cards, so Home can reload.
    init(
        api: APIClient,
        stream: StreamClient,
        draft: String = "",
        reconnectDelay: Duration = .seconds(2),
        onDataChanged: @escaping @MainActor () -> Void = {}
    ) {
        self.api = api
        self.stream = stream
        self.composer = draft
        self.reconnectDelay = reconnectDelay
        self.onDataChanged = onDataChanged
    }

    var canSend: Bool {
        !isTurnRunning && !composer.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
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

    /// Replaces the items with what the server has for today. Skipped mid-turn, so a live reply isn't dropped.
    func loadHistory() async {
        guard !isTurnRunning, !isLoadingHistory else { return }
        isLoadingHistory = true
        defer { isLoadingHistory = false }
        do {
            let day: ConversationDayDTO = try await api.get(.conversation())
            items = day.messages.compactMap(Self.item(from:))
            pending.removeAll()
            historyError = nil
        } catch {
            historyError = Self.message(for: error)
        }
    }

    private static func item(from message: MessageDTO) -> ChatItem? {
        switch message.role {
        case "user":
            .user(id: message.id, text: message.content, status: .sent, createdAt: message.createdAt)
        case "assistant":
            .assistant(id: message.id, text: message.content, actions: message.actions, status: .done, createdAt: message.createdAt)
        default:
            nil
        }
    }

    // MARK: - Sending

    func send() {
        guard canSend else { return }
        let text = composer.trimmingCharacters(in: .whitespacesAndNewlines)
        composer = ""
        let clientMsgId = UUID()
        let userId = clientMsgId.uuidString.lowercased()
        pending[userId] = (clientMsgId, text)
        items.append(.user(id: userId, text: text, status: .sending, createdAt: .now))
        start(userId: userId)
    }

    /// Retries a failed bubble: a message that never arrived goes again as itself;
    /// a reply that failed on the server is asked for again as a new message.
    func retry(_ itemId: String) {
        guard !isTurnRunning, let index = items.firstIndex(where: { $0.id == itemId }) else { return }
        switch items[index] {
        case .user(let id, let text, .failed, let date) where pending[id] != nil:
            items[index] = .user(id: id, text: text, status: .sending, createdAt: date)
            start(userId: id)
        case .assistant(_, _, _, .failed(_, retryable: true), _):
            guard let text = items[..<index].reversed().lazy.compactMap(Self.userText).first else { return }
            composer = text
            send()
        default:
            break
        }
    }

    private static func userText(_ item: ChatItem) -> String? {
        if case .user(_, let text, _, _) = item { text } else { nil }
    }

    private func start(userId: String) {
        guard let message = pending[userId] else { return }
        isTurnRunning = true
        items.append(.assistant(id: Self.replyId(for: userId), text: "", actions: [], status: .streaming, createdAt: .now))
        Task { [weak self] in
            await self?.runTurn(userId: userId, clientMsgId: message.clientMsgId, text: message.text)
        }
    }

    private static func replyId(for userId: String) -> String {
        "reply-" + userId
    }

    private func runTurn(userId: String, clientMsgId: UUID, text: String) async {
        let replyId = Self.replyId(for: userId)
        var started = false
        var failed = false
        var dataChanged = false

        do {
            for try await event in stream.send(OutgoingMessage(clientMsgId: clientMsgId, text: text)) {
                switch event {
                case .turnStarted:
                    started = true
                    pending[userId] = nil
                    updateUser(userId, status: .sent)
                case .actionDone(let tool, let summary, let isError, let ref):
                    let action = ActionDTO(tool: tool, summary: summary, isError: isError, ref: ref)
                    dataChanged = dataChanged || action.changesHomeData
                    // TinyMe saved a card that's already on screen: show its new version.
                    if let cardId = action.cardId, let shown = inlineCards[cardId] {
                        Task { await shown.load(force: true) }
                    }
                    updateReply(replyId) { _, actions, _ in actions.append(action) }
                case .text(let chunk):
                    updateReply(replyId) { text, _, _ in text += chunk }
                case .error(_, let message, let retryable):
                    // `failed` is followed by `done`: keep reading, but the turn stays failed.
                    failed = true
                    updateReply(replyId) { _, _, status in status = .failed(message: message, retryable: retryable) }
                case .done:
                    if !failed {
                        updateReply(replyId) { _, _, status in status = .done }
                    }
                case .attachments, .clientAction, .needsConfirmation, .unknown:
                    break   // not part of this phase
                }
            }
            isTurnRunning = false
        } catch {
            await recover(from: error, started: started, userId: userId, replyId: replyId)
            // The server finishes a turn it has started even when we lose the stream.
            dataChanged = dataChanged || started
        }

        if dataChanged {
            onDataChanged()
        }
    }

    private func recover(from error: Error, started: Bool, userId: String, replyId: String) async {
        switch error {
        case APIError.unauthorized, APIError.notSignedIn:
            // The session is ending; RootView is about to replace this screen.
            isTurnRunning = false
        case APIError.duplicate:
            // The server already has this message: its copy of the day is the truth.
            isTurnRunning = false
            await loadHistory()
        default:
            if started {
                // Lost mid-reply. Give the server a moment to finish, then show what it saved.
                try? await Task.sleep(for: reconnectDelay)
                isTurnRunning = false
                await loadHistory()
                if historyError != nil {
                    updateReply(replyId) { _, _, status in
                        status = .failed(message: "Lost the connection. Pull down to reload.", retryable: false)
                    }
                }
            } else {
                // Never reached the server: drop the empty reply and let the user resend.
                items.removeAll { $0.id == replyId }
                updateUser(userId, status: .failed)
                isTurnRunning = false
            }
        }
    }

    // MARK: - Item updates

    private func updateUser(_ id: String, status: ChatItem.UserStatus) {
        guard let index = items.firstIndex(where: { $0.id == id }),
              case .user(_, let text, _, let date) = items[index] else { return }
        items[index] = .user(id: id, text: text, status: status, createdAt: date)
    }

    private func updateReply(_ id: String, _ change: (inout String, inout [ActionDTO], inout ChatItem.AssistantStatus) -> Void) {
        guard let index = items.firstIndex(where: { $0.id == id }),
              case .assistant(_, var text, var actions, var status, let date) = items[index] else { return }
        change(&text, &actions, &status)
        items[index] = .assistant(id: id, text: text, actions: actions, status: status, createdAt: date)
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
