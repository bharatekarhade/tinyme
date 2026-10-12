import Foundation
import SwiftData

/// A message waiting to reach the server. Kept on disk, so it survives the app being closed.
@Model
final class OutboxItem {
    @Attribute(.unique) var clientMsgId: UUID
    var text: String
    var createdAt: Date
    /// An `OutboxStatus` raw value.
    var status: String
    var attempts: Int
    var nextAttemptAt: Date?
    var lastError: String?

    init(clientMsgId: UUID, text: String, createdAt: Date) {
        self.clientMsgId = clientMsgId
        self.text = text
        self.createdAt = createdAt
        self.status = OutboxStatus.queued.rawValue
        self.attempts = 0
    }
}

enum OutboxStatus: String, Sendable {
    /// Waiting for its turn, or for the next try.
    case queued
    /// On its way; the server hasn't confirmed it yet.
    case sending
    /// The server has it and is answering.
    case sent
    /// The server refused it. It stays until the user retries or deletes it.
    case failed
}

/// A copy of an `OutboxItem` that can leave the worker.
struct OutboxEntry: Sendable, Equatable, Identifiable {
    /// The `client_msg_id`. Every retry reuses it, so the server never logs a message twice.
    let id: UUID
    let text: String
    let createdAt: Date
    var status: OutboxStatus
    var attempts: Int
    var nextAttemptAt: Date?
    var lastError: String?
    /// The server's id for this message, once it has accepted it.
    var serverMessageId: String?
}

/// The whole queue, oldest first.
struct OutboxSnapshot: Sendable, Equatable {
    var entries: [OutboxEntry] = []
    var isOnline = true
}
