import Foundation
import Observation
@preconcurrency import AuthenticationServices
import UIKit

struct GoogleAuthStart: Decodable, Sendable {
    let authURL: URL
    enum CodingKeys: String, CodingKey { case authURL = "auth_url" }
}

struct GoogleConnectionStatus: Decodable, Sendable {
    let status: String
    let email: String?
    let scopes: [String]

    var isConnected: Bool { status == "connected" }
    var needsReauth: Bool { status == "needs_reauth" }
}

struct GoogleDisconnectBody: Encodable, Sendable {
    let password: String
}

@MainActor
@Observable
final class GoogleIntegrationModel {
    private(set) var status: GoogleConnectionStatus?
    private(set) var isWorking = false
    private(set) var errorMessage: String?
    private var authentication: GoogleWebAuthentication?

    func refresh(api: APIClient) async {
        do {
            status = try await api.get(.googleStatus)
        } catch {
            errorMessage = Self.message(for: error)
        }
    }

    func connect(api: APIClient) async {
        guard !isWorking else { return }
        isWorking = true
        errorMessage = nil
        defer { isWorking = false }
        do {
            let start: GoogleAuthStart = try await api.send(.googleStart)
            let webAuthentication = GoogleWebAuthentication()
            authentication = webAuthentication
            let callback = try await webAuthentication.authenticate(url: start.authURL)
            authentication = nil
            let result = URLComponents(url: callback, resolvingAgainstBaseURL: false)?.queryItems?
                .first(where: { $0.name == "result" })?.value
            guard result == "success" else {
                let reason = URLComponents(url: callback, resolvingAgainstBaseURL: false)?.queryItems?
                    .first(where: { $0.name == "reason" })?.value
                throw GoogleIntegrationError.callback(reason ?? "authorization_failed")
            }
            await refresh(api: api)
        } catch let error as ASWebAuthenticationSessionError where error.code == .canceledLogin {
            // Closing Google's sign-in sheet is a normal cancel, not an error.
            authentication = nil
        } catch {
            authentication = nil
            errorMessage = Self.message(for: error)
        }
    }

    func disconnect(api: APIClient, password: String) async {
        guard !isWorking else { return }
        isWorking = true
        errorMessage = nil
        defer { isWorking = false }
        do {
            try await api.send(.googleDisconnect, body: GoogleDisconnectBody(password: password))
            status = GoogleConnectionStatus(status: "disconnected", email: nil, scopes: [])
        } catch {
            errorMessage = Self.message(for: error)
        }
    }

    private static func message(for error: Error) -> String {
        if case APIError.server(status: 503, _, let detail) = error {
            return detail ?? "Google isn't configured on this server yet."
        }
        if case APIError.server(status: 403, _, let detail) = error {
            return detail ?? "Wrong password."
        }
        if case APIError.unauthorized = error { return "Your TinyMe sign-in expired. Sign in again." }
        if let error = error as? GoogleIntegrationError {
            switch error {
            case .callback(let reason):
                return reason == "access_denied" ? "Google access was cancelled." : "Google couldn't connect. Check the server setup and try again."
            }
        }
        if error is URLError { return "Couldn't reach the server. Check your connection and try again." }
        return "Google couldn't connect. Please try again."
    }
}

private enum GoogleIntegrationError: Error {
    case callback(String)
}

@MainActor
private final class GoogleWebAuthentication: NSObject, ASWebAuthenticationPresentationContextProviding {
    private var session: ASWebAuthenticationSession?

    func authenticate(url: URL) async throws -> URL {
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<URL, Error>) in
            let auth = ASWebAuthenticationSession(url: url, callbackURLScheme: "tinyme") { callback, error in
                if let callback {
                    continuation.resume(returning: callback)
                } else if let error {
                    continuation.resume(throwing: error)
                } else {
                    continuation.resume(throwing: URLError(.unknown))
                }
            }
            auth.presentationContextProvider = self
            auth.prefersEphemeralWebBrowserSession = false
            session = auth
            if !auth.start() {
                session = nil
                continuation.resume(throwing: URLError(.cannotOpenFile))
            }
        }
    }

    func presentationAnchor(for session: ASWebAuthenticationSession) -> ASPresentationAnchor {
        UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .flatMap(\.windows)
            .first(where: \.isKeyWindow) ?? ASPresentationAnchor()
    }
}
