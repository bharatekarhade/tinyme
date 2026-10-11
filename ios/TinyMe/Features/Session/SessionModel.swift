import Foundation
import Observation
#if canImport(UIKit)
import UIKit
#endif

/// Who is signed in, and the API clients for their server.
@MainActor
@Observable
final class SessionModel {
    enum State: Equatable {
        case signedOut
        case signingIn
        case signedIn(server: URL, email: String)
    }

    private(set) var state: State = .signedOut
    private(set) var loginError: String?
    private(set) var api: APIClient?
    private(set) var stream: StreamClient?

    private let authStore: AuthStore
    private let urlSession: URLSession

    init(authStore: AuthStore, urlSession: URLSession = .shared) {
        self.authStore = authStore
        self.urlSession = urlSession
        // A stored token means signed in, with no network call; the first 401 signs out.
        if let saved = authStore.savedSession {
            connect(to: saved.server)
            state = .signedIn(server: saved.server, email: saved.email)
        }
    }

    /// What the login form starts with.
    var lastServer: URL? { authStore.lastServer }
    var lastEmail: String? { authStore.lastEmail }

    func signIn(server: String, email: String, password: String) async {
        guard state == .signedOut else { return }
        guard let serverURL = Self.normalizedServerURL(server) else {
            loginError = "That server address doesn't look right."
            return
        }
        let email = email.trimmingCharacters(in: .whitespacesAndNewlines)
        loginError = nil
        state = .signingIn

        let client = APIClient(baseURL: serverURL, session: urlSession)
        do {
            let _: HealthDTO = try await client.get(.health)
        } catch {
            return failSignIn("Can't reach your server. Check the address.")
        }

        let response: LoginResponse
        do {
            response = try await client.send(.login, body: LoginRequest(email: email, password: password, deviceName: Self.deviceName))
        } catch APIError.unauthorized {
            return failSignIn("Email or password is wrong.")
        } catch APIError.server(status: 429, _, _), APIError.http(status: 429) {
            return failSignIn("Too many tries. Wait a minute and try again.")
        } catch is URLError {
            return failSignIn("Can't reach your server. Check the address.")
        } catch {
            return failSignIn("Something went wrong. Try again.")
        }

        await authStore.save(token: response.token, server: serverURL, email: email)
        connect(to: serverURL)
        state = .signedIn(server: serverURL, email: email)
    }

    func signOut() async {
        try? await api?.send(.logout)   // best effort: the token is forgotten either way
        await endSession()
    }

    /// The server rejected the token (wired as `APIClient.onUnauthorized`).
    func handleUnauthorized() async {
        guard case .signedIn = state else { return }
        await endSession()
        loginError = "You were signed out."
    }

    func clearLoginError() {
        loginError = nil
    }

    /// Adds `https://` when there is no scheme and strips trailing slashes.
    static func normalizedServerURL(_ input: String) -> URL? {
        var text = input.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return nil }
        if !text.contains("://") {
            text = "https://" + text
        }
        while text.hasSuffix("/") {
            text.removeLast()
        }
        guard let url = URL(string: text), let scheme = url.scheme?.lowercased(), ["http", "https"].contains(scheme),
              let host = url.host(), !host.isEmpty else {
            return nil
        }
        return url
    }

    private func connect(to server: URL) {
        let client = APIClient(
            baseURL: server,
            session: urlSession,
            token: { [authStore] in await authStore.token() },
            onUnauthorized: { [weak self] in await self?.handleUnauthorized() }
        )
        api = client
        stream = StreamClient(api: client, session: urlSession)
    }

    private func endSession() async {
        await authStore.clear()
        WidgetSnapshot.clearAndReload()   // the widget shouldn't keep showing this account's numbers
        api = nil
        stream = nil
        state = .signedOut
    }

    private func failSignIn(_ message: String) {
        loginError = message
        state = .signedOut
    }

    private static var deviceName: String {
        #if canImport(UIKit)
        UIDevice.current.name
        #else
        Host.current().localizedName ?? "Mac"
        #endif
    }
}
