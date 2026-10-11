import AppIntents
import Foundation

/// "Journal something" without opening the app: iOS asks for the text in a small panel,
/// TinyMe files it, and the reply comes back in the same panel.
/// Runs from Shortcuts, Siri, Spotlight, the Action button, or Back Tap.
struct JournalIntent: AppIntent {
    static let title: LocalizedStringResource = "Journal something"
    static let description = IntentDescription("Tell TinyMe something without opening the app.")
    static let openAppWhenRun = false

    @Parameter(title: "What happened?", requestValueDialog: "Journal something?")
    var text: String

    func perform() async throws -> some IntentResult & ProvidesDialog {
        let message = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !message.isEmpty else {
            return .result(dialog: "Nothing to journal.")
        }
        let authStore = AuthStore()
        guard let saved = authStore.savedSession else {
            return .result(dialog: "Open TinyMe and sign in first.")
        }
        let api = APIClient(baseURL: saved.server, token: { await authStore.token() })
        let stream = StreamClient(api: api)

        let reply = await Self.reply(to: message, from: stream)
        return .result(dialog: IntentDialog(stringLiteral: reply))
    }

    /// Sends the message and gathers the reply, giving up on waiting before iOS cuts the shortcut off.
    private static func reply(to message: String, from stream: StreamClient) async -> String {
        await withTaskGroup(of: String?.self) { group in
            group.addTask {
                var text = ""
                var done: [String] = []
                var failure: String?
                do {
                    for try await event in stream.send(OutgoingMessage(clientMsgId: UUID(), text: message)) {
                        switch event {
                        case .text(let chunk): text += chunk
                        case .actionDone(_, let summary, let isError, _) where !isError: done.append(summary)
                        case .error(_, let message, _): failure = message
                        default: break
                        }
                    }
                } catch APIError.unauthorized, APIError.notSignedIn {
                    return "Open TinyMe and sign in again."
                } catch {
                    // The server finishes a turn it has started, even if the connection dropped.
                    return text.isEmpty ? "I couldn't reach your server." : text
                }
                if let failure { return failure }
                if !text.isEmpty { return text }
                return done.last ?? "Done."
            }
            group.addTask {
                try? await Task.sleep(for: .seconds(25))
                return nil
            }
            let first = await group.next() ?? nil
            group.cancelAll()
            return first ?? "Still working on it. Open TinyMe to see the reply."
        }
    }
}

/// Makes the action show up in Shortcuts, Spotlight and Siri without any setup.
struct TinyMeShortcuts: AppShortcutsProvider {
    static var appShortcuts: [AppShortcut] {
        AppShortcut(
            intent: JournalIntent(),
            phrases: [
                "Journal something in \(.applicationName)",
                "Tell \(.applicationName) something",
                "Log something in \(.applicationName)",
            ],
            shortTitle: "Journal something",
            systemImageName: "mic"
        )
    }
}
