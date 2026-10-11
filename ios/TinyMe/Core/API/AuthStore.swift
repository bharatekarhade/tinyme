import Foundation

/// Where the sign-in lives: the device token in the Keychain, the server URL and email in UserDefaults.
actor AuthStore {
    struct SavedSession: Sendable, Equatable {
        let server: URL
        let email: String
    }

    private let service: String
    private let account = "device-token"
    private let serverKey = "auth.server"
    private let emailKey = "auth.email"
    private nonisolated(unsafe) let defaults: UserDefaults   // UserDefaults is thread-safe

    init(service: String = "app.tinyme", defaults: UserDefaults = .standard) {
        self.service = service
        self.defaults = defaults
    }

    func token() -> String? {
        Keychain.read(service: service, account: account)
    }

    func save(token: String, server: URL, email: String) {
        Keychain.save(token, service: service, account: account)
        defaults.set(server.absoluteString, forKey: serverKey)
        defaults.set(email, forKey: emailKey)
    }

    /// Forgets the token. The server and email stay, to pre-fill the next login.
    func clear() {
        Keychain.delete(service: service, account: account)
    }

    /// The signed-in server and email, if a token is stored. Readable at launch without waiting.
    nonisolated var savedSession: SavedSession? {
        guard Keychain.read(service: service, account: account) != nil, let server = lastServer, let email = lastEmail else {
            return nil
        }
        return SavedSession(server: server, email: email)
    }

    nonisolated var lastServer: URL? {
        defaults.string(forKey: serverKey).flatMap(URL.init(string:))
    }

    nonisolated var lastEmail: String? {
        defaults.string(forKey: emailKey)
    }
}
