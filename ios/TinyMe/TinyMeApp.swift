import SwiftUI

@main
struct TinyMeApp: App {
    init() {
        Fonts.register()
    }

    var body: some Scene {
        WindowGroup {
            HomeView()
        }
    }
}
