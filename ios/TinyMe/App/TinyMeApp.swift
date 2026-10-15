import SwiftUI
import AppIntents

@main
struct TinyMeApp: App {
    @State private var environment: AppEnvironment
    @AppStorage(Appearance.storageKey) private var appearance: Appearance = .system

    init() {
        let environment = AppEnvironment()
        _environment = State(initialValue: environment)
        AppDependencyManager.shared.add(dependency: environment.intentRuntime)
        TinyMeShortcuts.updateAppShortcutParameters()
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
