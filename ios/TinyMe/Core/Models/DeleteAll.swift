import Foundation

/// Body of `POST /admin/delete-all`. The password is the real confirmation; the phrase guards against a stray call.
struct DeleteAllRequest: Encodable, Sendable {
    var confirm = "DELETE EVERYTHING"
    let password: String
}

/// What the server removed.
struct DeleteAllCounts: Decodable, Sendable, Equatable {
    let entries: Int
    let people: Int
    let cards: Int
    let messages: Int
    let sessions: Int
    let memoryDocuments: Int

    private enum CodingKeys: String, CodingKey {
        case entries, people, cards, messages, sessions
        case memoryDocuments = "memory_documents"
    }
}
