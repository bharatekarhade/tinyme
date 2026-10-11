import Foundation

/// Body of `POST /conversations/today/messages`.
struct OutgoingMessage: Encodable, Sendable {
    struct Attachment: Encodable, Sendable {
        let uploadId: String

        private enum CodingKeys: String, CodingKey {
            case uploadId = "upload_id"
        }
    }

    struct ClientContext: Encodable, Sendable {
        var tz: String = TimeZone.current.identifier
        var lat: Double?
        var lng: Double?
        var place: String?
    }

    /// From the app outbox; the server uses it to reject duplicates.
    let clientMsgId: UUID
    var text: String
    var attachments: [Attachment] = []
    var clientContext: ClientContext? = ClientContext()

    func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(clientMsgId.uuidString.lowercased(), forKey: .clientMsgId)
        try container.encode(text, forKey: .text)
        if !attachments.isEmpty {
            try container.encode(attachments, forKey: .attachments)
        }
        try container.encodeIfPresent(clientContext, forKey: .clientContext)
    }

    private enum CodingKeys: String, CodingKey {
        case text, attachments
        case clientMsgId = "client_msg_id"
        case clientContext = "client_context"
    }
}
