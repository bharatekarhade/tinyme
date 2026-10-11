import Foundation

struct Endpoint: Sendable {
    let method: String
    let path: String

    static let postMessage = Endpoint(method: "POST", path: "conversations/today/messages")

    static func actionResult(_ actionId: String) -> Endpoint {
        Endpoint(method: "POST", path: "actions/\(actionId)/result")
    }
}
