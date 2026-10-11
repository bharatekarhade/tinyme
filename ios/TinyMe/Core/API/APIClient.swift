import Foundation
import os

enum APIError: Error, Equatable {
    case notSignedIn
    /// 401: wrong credentials on login; anywhere else the token was rejected and the app has signed out.
    case unauthorized
    /// 409 on sending a message: the server already accepted this `client_msg_id`.
    case duplicate(messageId: String?)
    /// A non-2xx reply that carried the server's ProblemDetail body.
    case server(status: Int, title: String?, detail: String?)
    case http(status: Int)
    case decoding(String)
    case invalidResponse
    /// The reply stream closed before its `done` event.
    case streamEndedEarly
}

/// Talks to one server: builds requests, adds the device token, decodes replies and maps failures to `APIError`.
actor APIClient {
    private static let log = Logger(subsystem: Bundle.main.bundleIdentifier ?? "TinyMe", category: "api")

    private let baseURL: URL
    private let session: URLSession
    private let token: @Sendable () async -> String?
    private let onUnauthorized: @Sendable () async -> Void
    private var reportedUnauthorized = false
    private let encoder = JSONEncoder()
    private let decoder: JSONDecoder = {
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .custom { decoder in
            let container = try decoder.singleValueContainer()
            let string = try container.decode(String.self)
            // The server writes instants with or without fractional seconds.
            if let date = try? Date(string, strategy: Date.ISO8601FormatStyle(includingFractionalSeconds: true)) {
                return date
            }
            if let date = try? Date(string, strategy: .iso8601) {
                return date
            }
            throw DecodingError.dataCorruptedError(in: container, debugDescription: "Not an ISO 8601 date: \(string)")
        }
        return decoder
    }()

    /// - Parameters:
    ///   - token: the current device token, or nil when signed out.
    ///   - onUnauthorized: called once, the first time the server rejects the token, so the app can sign out.
    init(
        baseURL: URL,
        session: URLSession = .shared,
        token: @escaping @Sendable () async -> String? = { nil },
        onUnauthorized: @escaping @Sendable () async -> Void = {}
    ) {
        self.baseURL = baseURL
        self.session = session
        self.token = token
        self.onUnauthorized = onUnauthorized
    }

    // MARK: - Calls

    func get<T: Decodable & Sendable>(_ endpoint: Endpoint) async throws -> T {
        try decode(try await data(for: try await authorizedRequest(endpoint), endpoint), endpoint)
    }

    func send<T: Decodable & Sendable>(_ endpoint: Endpoint, body: some Encodable & Sendable) async throws -> T {
        try decode(try await data(for: try await authorizedRequest(endpoint, body: body), endpoint), endpoint)
    }

    /// For calls that answer 204.
    func send(_ endpoint: Endpoint, body: some Encodable & Sendable) async throws {
        _ = try await data(for: try await authorizedRequest(endpoint, body: body), endpoint)
    }

    func send(_ endpoint: Endpoint) async throws {
        _ = try await data(for: try await authorizedRequest(endpoint), endpoint)
    }

    func postActionResult(_ actionId: String, _ result: ActionResult) async throws {
        try await send(.actionResult(actionId), body: result)
    }

    // MARK: - Building blocks (also used by StreamClient)

    func authorizedRequest(_ endpoint: Endpoint) async throws -> URLRequest {
        guard var components = URLComponents(url: baseURL.appending(path: endpoint.path), resolvingAgainstBaseURL: false) else {
            throw APIError.invalidResponse
        }
        if !endpoint.query.isEmpty {
            components.queryItems = endpoint.query
        }
        guard let url = components.url else { throw APIError.invalidResponse }
        var request = URLRequest(url: url)
        request.httpMethod = endpoint.method
        request.timeoutInterval = 30
        if endpoint.requiresAuth {
            guard let token = await token() else { throw APIError.notSignedIn }
            request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        }
        return request
    }

    func authorizedRequest(_ endpoint: Endpoint, body: some Encodable & Sendable) async throws -> URLRequest {
        var request = try await authorizedRequest(endpoint)
        request.httpBody = try encoder.encode(body)
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        return request
    }

    /// Throws unless the response is a 2xx. `data` is the error body, when there is one.
    func validate(_ response: URLResponse, data: Data = Data(), for endpoint: Endpoint) async throws {
        guard let http = response as? HTTPURLResponse else { throw APIError.invalidResponse }
        switch http.statusCode {
        case 200..<300:
            return
        case 401:
            if endpoint.requiresAuth, !reportedUnauthorized {
                reportedUnauthorized = true
                await onUnauthorized()
            }
            throw APIError.unauthorized
        default:
            guard let problem = try? decoder.decode(ProblemDetail.self, from: data) else {
                throw APIError.http(status: http.statusCode)
            }
            throw APIError.server(status: http.statusCode, title: problem.title, detail: problem.detail)
        }
    }

    private func data(for request: URLRequest, _ endpoint: Endpoint) async throws -> Data {
        let (data, response) = try await session.data(for: request)
        try await validate(response, data: data, for: endpoint)
        return data
    }

    private func decode<T: Decodable>(_ data: Data, _ endpoint: Endpoint) throws -> T {
        do {
            return try decoder.decode(T.self, from: data)
        } catch {
            // The path only: a body can hold personal data.
            Self.log.error("Couldn't decode the reply from \(endpoint.path, privacy: .public): \(String(describing: error), privacy: .public)")
            throw APIError.decoding(String(describing: error))
        }
    }
}

/// The server's RFC 9457 error body.
private struct ProblemDetail: Decodable {
    var title: String?
    var detail: String?
}
