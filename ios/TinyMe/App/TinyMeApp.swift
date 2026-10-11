import SwiftUI

@main
struct TinyMeApp: App {
    @State private var environment = AppEnvironment()
    @AppStorage(Appearance.storageKey) private var appearance: Appearance = .system

    init() {
        Fonts.register()
    }

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(environment)
                .preferredColorScheme(appearance.colorScheme)
        }
    }
}
