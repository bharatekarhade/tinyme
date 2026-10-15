import AppIntents
import Foundation

/// "Journal something" without opening the app: iOS asks for the text, then TinyMe queues it
/// locally and responds immediately while the shared outbox sends it in the background.
/// Runs from Shortcuts, Siri, Spotlight, the Action button, or Back Tap.
struct JournalIntent: AppIntent {
    static let title: LocalizedStringResource = "Journal something"
    static let description = IntentDescription("Tell TinyMe something without opening the app.")
    static let openAppWhenRun = false

    @Parameter(title: "What happened?", requestValueDialog: "Journal something?")
    var text: String

    @Dependency private var runtime: IntentRuntime

    func perform() async throws -> some IntentResult & ReturnsValue<String> & ProvidesDialog {
        let message = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !message.isEmpty else {
            return .result(value: "Nothing to journal.", dialog: "Nothing to journal.")
        }
        let acknowledgement = await runtime.queue(message)
        return .result(value: acknowledgement, dialog: IntentDialog(stringLiteral: acknowledgement))
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
