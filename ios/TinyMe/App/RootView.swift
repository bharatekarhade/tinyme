import SwiftUI

/// Login when signed out, the app when signed in.
struct RootView: View {
    @Environment(AppEnvironment.self) private var environment

    var body: some View {
        Group {
            switch environment.session.state {
            case .signedOut, .signingIn:
                LoginView(session: environment.session)
                    .transition(.opacity)
            case .signedIn:
                if let api = environment.session.api {
                    MainView(api: api)
                        .transition(.opacity)
                }
            }
        }
        .animation(.easeInOut(duration: 0.3), value: isSignedIn)
        #if DEBUG
        .task { await ScreenshotMode.signInIfRequested(environment.session) }
        #endif
    }

    private var isSignedIn: Bool {
        if case .signedIn = environment.session.state { true } else { false }
    }
}
