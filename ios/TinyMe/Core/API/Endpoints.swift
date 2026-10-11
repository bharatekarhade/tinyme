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

    static let cardSuggestions = Endpoint(method: "GET", path: "cards/suggestions")
    static let cardOrder = Endpoint(method: "PUT", path: "cards/order")

    /// - Parameter range: `day`, `week`, `month` or `year`.
    static func cards(range: String) -> Endpoint {
        Endpoint(method: "GET", path: "cards", query: [URLQueryItem(name: "range", value: range)])
    }

    static func card(_ id: String, range: String = "week") -> Endpoint {
        Endpoint(method: "GET", path: "cards/\(id)", query: [URLQueryItem(name: "range", value: range)])
    }

    static func activateCard(_ id: String) -> Endpoint {
        Endpoint(method: "POST", path: "cards/\(id)/activate")
    }

    static func deleteCard(_ id: String) -> Endpoint {
        Endpoint(method: "DELETE", path: "cards/\(id)")
    }

    static func conversation(day: LocalDay? = nil) -> Endpoint {
        Endpoint(method: "GET", path: "conversations", query: day.map { [URLQueryItem(name: "day", value: $0.description)] } ?? [])
    }

    static func actionResult(_ actionId: String) -> Endpoint {
        Endpoint(method: "POST", path: "actions/\(actionId)/result")
    }
}
