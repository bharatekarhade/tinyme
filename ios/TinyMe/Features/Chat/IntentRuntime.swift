import Foundation

/// Shared app-process dependencies used by App Intents.
/// Reuses the signed-in session and its StreamClient so device actions behave the same as in chat.
@MainActor
final class IntentRuntime {
    let outbox: OutboxWorker

    init(outbox: OutboxWorker) {
        self.outbox = outbox
    }

    /// Persist the message locally and let the shared outbox send it without holding Siri open.
    func queue(_ message: String) async -> String {
        await outbox.enqueue(text: message)
        return "Okay, I queued that for TinyMe."
    }
}
