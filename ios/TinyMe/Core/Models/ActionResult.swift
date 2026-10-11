import Foundation

/// Outcome of a device tool, posted to `/actions/{id}/result` as `{ok, data?, error?}`.
enum ActionResult: Encodable, Sendable, Equatable {
    case ok(JSONValue)
    case error(code: String, message: String)

    func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        switch self {
        case .ok(let data):
            try container.encode(true, forKey: .ok)
            try container.encode(data, forKey: .data)
        case .error(let code, let message):
            try container.encode(false, forKey: .ok)
            try container.encode(["code": code, "message": message], forKey: .error)
        }
    }

    private enum CodingKeys: String, CodingKey {
        case ok, data, error
    }
}
