import Foundation

#if DEBUG
/// Debug builds only: launch arguments that sign in and open one screen, so README screenshots can be taken from a
/// script (see `scripts/screenshots/capture.sh`). Arguments become UserDefaults, e.g.
/// `-TinyMeScreenshotScreen gscan -TinyMeScreenshotServer http://localhost:8090 -TinyMeScreenshotEmail … -TinyMeScreenshotPassword …`
enum ScreenshotMode {
    enum Screen: String {
        case home, upcoming, gscan, chat, history, settings, people, places
    }

    static var screen: Screen? {
        UserDefaults.standard.string(forKey: "TinyMeScreenshotScreen").flatMap(Screen.init(rawValue:))
    }

    /// Signs in with the launch-argument account when signed out.
    @MainActor
    static func signInIfRequested(_ session: SessionModel) async {
        let defaults = UserDefaults.standard
        guard session.state == .signedOut,
              let server = defaults.string(forKey: "TinyMeScreenshotServer"),
              let email = defaults.string(forKey: "TinyMeScreenshotEmail"),
              let password = defaults.string(forKey: "TinyMeScreenshotPassword") else { return }
        await session.signIn(server: server, email: email, password: password)
    }

    /// Puts the router on the requested screen.
    @MainActor
    static func apply(to router: MainRouter) {
        switch screen {
        case .upcoming: router.tab = .upcoming
        case .gscan: router.tab = .gscan
        case .chat: router.openChat()
        case .history: router.openHistory()
        case .settings: router.showsSettings = true
        case .home, .people, .places, nil: break
        }
    }
}
#endif
