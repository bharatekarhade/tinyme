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
    static let dataExport = Endpoint(method: "GET", path: "admin/export")
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

    static let kinds = Endpoint(method: "GET", path: "kinds")

    /// The whole log, newest first. `kind` nil means every kind.
    static func entries(kind: String?, query: String, cursor: String?) -> Endpoint {
        var items: [URLQueryItem] = []
        if let kind { items.append(URLQueryItem(name: "kind", value: kind)) }
        if !query.isEmpty { items.append(URLQueryItem(name: "q", value: query)) }
        if let cursor { items.append(URLQueryItem(name: "cursor", value: cursor)) }
        return Endpoint(method: "GET", path: "entries", query: items)
    }

    /// The entries behind one block of a card. The server builds the filter, so the list adds up to the card.
    static func cardEntries(
        _ id: String, range: String, block: Int?, item: Int?, day: LocalDay?, key: String?, cursor: String?
    ) -> Endpoint {
        var items = [URLQueryItem(name: "range", value: range)]
        if let block { items.append(URLQueryItem(name: "block", value: String(block))) }
        if let item { items.append(URLQueryItem(name: "item", value: String(item))) }
        if let day { items.append(URLQueryItem(name: "day", value: day.description)) }
        if let key { items.append(URLQueryItem(name: "key", value: key)) }
        if let cursor { items.append(URLQueryItem(name: "cursor", value: cursor)) }
        return Endpoint(method: "GET", path: "cards/\(id)/entries", query: items)
    }

    static func actionResult(_ actionId: String) -> Endpoint {
        Endpoint(method: "POST", path: "actions/\(actionId)/result")
    }
}
