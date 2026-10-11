import SwiftUI

@main
struct TinyMeApp: App {
    @State private var environment = AppEnvironment()

    init() {
        Fonts.register()
    }

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(environment)
        }
    }
}
