import Foundation

/// `GET /conversations?day=`
struct ConversationDayDTO: Decodable, Sendable {
    let messages: [MessageDTO]
}

struct MessageDTO: Decodable, Sendable, Identifiable {
    let id: String
    let role: String   // "user" | "assistant"
    let content: String
    let actions: [ActionDTO]
    let attachments: [ChatPhotoDTO]
    let createdAt: Date

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        id = try container.decode(String.self, forKey: .id)
        role = try container.decode(String.self, forKey: .role)
        content = try container.decodeIfPresent(String.self, forKey: .content) ?? ""
        actions = try container.decodeIfPresent([ActionDTO].self, forKey: .actions) ?? []
        attachments = try container.decodeIfPresent([ChatPhotoDTO].self, forKey: .attachments) ?? []
        createdAt = try container.decode(Date.self, forKey: .createdAt)
    }

    private enum CodingKeys: String, CodingKey {
        case id, role, content, actions, attachments
        case createdAt = "created_at"
    }
}

struct ChatPhotoDTO: Decodable, Sendable, Hashable, Identifiable {
    let id: String
    let contentURL: String
    let contentType: String

    private enum CodingKeys: String, CodingKey {
        case contentURL = "content_url"
        case contentType = "content_type"
        case photoId = "photo_id"
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        id = try container.decode(String.self, forKey: .photoId)
        contentURL = try container.decode(String.self, forKey: .contentURL)
        contentType = try container.decode(String.self, forKey: .contentType)
    }
}

/// Something TinyMe did during a reply, shown as a chip under it.
struct ActionDTO: Decodable, Sendable, Hashable {
    let tool: String
    let summary: String
    let isError: Bool
    /// What the tool touched. Keys depend on the tool: read with `ref?["card_id"]?.stringValue`.
    let ref: JSONValue?

    init(tool: String, summary: String, isError: Bool, ref: JSONValue?) {
        self.tool = tool
        self.summary = summary
        self.isError = isError
        self.ref = ref
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        tool = try container.decode(String.self, forKey: .tool)
        summary = try container.decode(String.self, forKey: .summary)
        isError = try container.decodeIfPresent(Bool.self, forKey: .isError) ?? false
        ref = try container.decodeIfPresent(JSONValue.self, forKey: .ref)
    }

    private enum CodingKeys: String, CodingKey {
        case tool, summary, ref
        case isError = "is_error"
    }

    /// The card this action saved or touched, if any.
    var cardId: String? {
        isError ? nil : ref?["card_id"]?.stringValue
    }

    var planId: String? {
        isError ? nil : ref?["plan_id"]?.stringValue
    }

    /// True when a tool changed data that appears in Home, Upcoming, or History.
    var changesHomeData: Bool {
        !isError && (tool.hasPrefix("entries_") || tool.hasPrefix("cards_") || tool.hasPrefix("plans_") || tool == "people_upsert")
            && !Self.readOnlyTools.contains(tool)
    }

    private static let readOnlyTools: Set<String> = [
        "entries_query", "entries_aggregate", "cards_list", "plans_query", "people_get"
    ]
}
