import Foundation

struct Endpoint: Sendable {
    let method: String
    let path: String
    var query: [URLQueryItem] = []
    /// False for the routes that work before sign-in.
    var requiresAuth = true

    static let health = Endpoint(method: "GET", path: "actuator/health", requiresAuth: false)
    static let login = Endpoint(method: "POST", path: "auth/login", requiresAuth: false)
    static let logout = Endpoint(method: "POST", path: "auth/logout")
    static let postMessage = Endpoint(method: "POST", path: "conversations/today/messages")

    static func actionResult(_ actionId: String) -> Endpoint {
        Endpoint(method: "POST", path: "actions/\(actionId)/result")
    }
}
