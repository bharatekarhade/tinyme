import Foundation

struct Endpoint: Sendable {
    let method: String
    let path: String
    var query: [URLQueryItem] = []
    /// False for the routes that work before sign-in.
    var requiresAuth = true
    /// False where a 401 means "wrong password", not "this phone's sign-in was rejected".
    var signsOutOnUnauthorized = true

    static let health = Endpoint(method: "GET", path: "actuator/health", requiresAuth: false)
    static let login = Endpoint(method: "POST", path: "auth/login", requiresAuth: false)
    static let logout = Endpoint(method: "POST", path: "auth/logout")
    static let dataExport = Endpoint(method: "GET", path: "admin/export")
    static let deleteAll = Endpoint(method: "POST", path: "admin/delete-all", signsOutOnUnauthorized: false)
    static let postMessage = Endpoint(method: "POST", path: "conversations/today/messages")

    static let healthSettings = Endpoint(method: "PUT", path: "health/settings")
    static let healthSync = Endpoint(method: "POST", path: "health/sync")
    static let healthStatus = Endpoint(method: "GET", path: "health/status")
    static let deleteHealthData = Endpoint(method: "DELETE", path: "health/data")

    static let cardSuggestions = Endpoint(method: "GET", path: "cards/suggestions")
    static let cardOrder = Endpoint(method: "PUT", path: "cards/order")

    /// - Parameter range: `day`, `week`, `month` or `year`.
    static func cards(range: String, date: LocalDay? = nil) -> Endpoint {
        var query = [URLQueryItem(name: "range", value: range)]
        if let date { query.append(URLQueryItem(name: "date", value: date.description)) }
        return Endpoint(method: "GET", path: "cards", query: query)
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

    static func upcoming(from: LocalDay, to: LocalDay) -> Endpoint {
        Endpoint(method: "GET", path: "upcoming", query: [
            URLQueryItem(name: "from", value: from.description),
            URLQueryItem(name: "to", value: to.description)
        ])
    }

    static func planDone(_ id: String, day: LocalDay) -> Endpoint {
        Endpoint(method: "POST", path: "plans/\(id)/occurrences/\(day.description)/done")
    }

    static func planUndoDone(_ id: String, day: LocalDay) -> Endpoint {
        Endpoint(method: "DELETE", path: "plans/\(id)/occurrences/\(day.description)/done")
    }

    static func planSkip(_ id: String, day: LocalDay) -> Endpoint {
        Endpoint(method: "POST", path: "plans/\(id)/occurrences/\(day.description)/skip")
    }

    static func movePlanOccurrence(_ id: String, occurrenceDay: LocalDay) -> Endpoint {
        Endpoint(method: "POST", path: "plans/\(id)/occurrences/\(occurrenceDay.description)/move")
    }

    static func movePlanSeries(_ id: String) -> Endpoint {
        Endpoint(method: "PATCH", path: "plans/\(id)")
    }

    static func restorePlan(_ id: String) -> Endpoint {
        Endpoint(method: "POST", path: "plans/\(id)/restore")
    }

    static func deletePlan(_ id: String) -> Endpoint {
        Endpoint(method: "DELETE", path: "plans/\(id)")
    }

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
        _ id: String, range: String, date: LocalDay? = nil, block: Int?, item: Int?, day: LocalDay?, key: String?, cursor: String?
    ) -> Endpoint {
        var items = [URLQueryItem(name: "range", value: range)]
        if let date { items.append(URLQueryItem(name: "date", value: date.description)) }
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
