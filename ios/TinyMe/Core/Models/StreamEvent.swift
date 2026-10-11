import Foundation

/// One `data:` line from `POST /conversations/today/messages`, picked by its `type` field.
/// Keys are spelled out in snake_case, so decode with a plain `JSONDecoder` (no key strategy).
enum StreamEvent: Decodable, Sendable, Equatable {
    case turnStarted(turnId: String?, userMessageId: String)
    case text(String)
    /// `ref` points at what the tool touched; its keys depend on the tool (e.g. `card_id`).
    case actionDone(tool: String, summary: String, isError: Bool, ref: JSONValue?)
    case attachments(photos: [PhotoDTO])
    case clientAction(actionId: String, tool: String, input: JSONValue)
    case needsConfirmation(confirmationId: String, summary: String)   // Phase 3
    case error(code: String, message: String, retryable: Bool)
    case done(assistantMessageId: String?)
    /// A type this build doesn't know. Callers skip it, so newer servers don't break older apps.
    case unknown(type: String)

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        let type = try container.decode(String.self, forKey: .type)
        switch type {
        case "turn_started":
            self = .turnStarted(
                turnId: try container.decodeIfPresent(String.self, forKey: .turnId),
                userMessageId: try container.decode(String.self, forKey: .userMessageId)
            )
        case "text":
            self = .text(try container.decode(String.self, forKey: .text))
        case "action_done":
            self = .actionDone(
                tool: try container.decode(String.self, forKey: .tool),
                summary: try container.decode(String.self, forKey: .summary),
                isError: try container.decodeIfPresent(Bool.self, forKey: .isError) ?? false,
                ref: try container.decodeIfPresent(JSONValue.self, forKey: .ref)
            )
        case "attachments":
            self = .attachments(photos: try container.decode([PhotoDTO].self, forKey: .photos))
        case "client_action":
            self = .clientAction(
                actionId: try container.decode(String.self, forKey: .actionId),
                tool: try container.decode(String.self, forKey: .tool),
                input: try container.decodeIfPresent(JSONValue.self, forKey: .input) ?? .object([:])
            )
        case "needs_confirmation":
            self = .needsConfirmation(
                confirmationId: try container.decode(String.self, forKey: .confirmationId),
                summary: try container.decode(String.self, forKey: .summary)
            )
        case "error", "failed":   // the server sends "failed"; the design doc says "error"
            self = .error(
                code: try container.decode(String.self, forKey: .code),
                message: try container.decode(String.self, forKey: .message),
                retryable: try container.decodeIfPresent(Bool.self, forKey: .retryable) ?? false
            )
        case "done":
            self = .done(assistantMessageId: try container.decodeIfPresent(String.self, forKey: .assistantMessageId))
        default:
            self = .unknown(type: type)
        }
    }

    private enum CodingKeys: String, CodingKey {
        case type, text, tool, summary, ref, photos, input, code, message, retryable
        case turnId = "turn_id"
        case userMessageId = "user_message_id"
        case isError = "is_error"
        case actionId = "action_id"
        case confirmationId = "confirmation_id"
        case assistantMessageId = "assistant_message_id"
    }
}
