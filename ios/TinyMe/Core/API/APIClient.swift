import Foundation

enum APIError: Error, Equatable {
    case notSignedIn
    case unauthorized
    /// 409: the server already accepted this `client_msg_id`.
    case duplicate
    case http(status: Int)
    case invalidResponse
    /// The reply stream closed before its `done` event.
    case streamEndedEarly
}

/// Builds authorised requests against the user's server and maps HTTP status codes to `APIError`.
actor APIClient {
    private let baseURL: URL
    private let session: URLSession
    private let token: @Sendable () async -> String?
    private let encoder = JSONEncoder()

    init(baseURL: URL, session: URLSession = .shared, token: @escaping @Sendable () async -> String?) {
        self.baseURL = baseURL
        self.session = session
        self.token = token
    }

    func authorizedRequest(_ endpoint: Endpoint) async throws -> URLRequest {
        guard let token = await token() else { throw APIError.notSignedIn }
        var request = URLRequest(url: baseURL.appending(path: endpoint.path))
        request.httpMethod = endpoint.method
        request.timeoutInterval = 30
        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        return request
    }

    func authorizedRequest(_ endpoint: Endpoint, body: some Encodable & Sendable) async throws -> URLRequest {
        var request = try await authorizedRequest(endpoint)
        request.httpBody = try encoder.encode(body)
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        return request
    }

    nonisolated func check(_ response: URLResponse) throws {
        guard let http = response as? HTTPURLResponse else { throw APIError.invalidResponse }
        switch http.statusCode {
        case 200..<300: return
        case 401: throw APIError.unauthorized
        case 409: throw APIError.duplicate
        default: throw APIError.http(status: http.statusCode)
        }
    }

    func postActionResult(_ actionId: String, _ result: ActionResult) async throws {
        let request = try await authorizedRequest(.actionResult(actionId), body: result)
        let (_, response) = try await session.data(for: request)
        try check(response)
    }
}
