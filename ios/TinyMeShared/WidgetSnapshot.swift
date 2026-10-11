import Foundation

/// What the app hands to the home-screen widget: one line per card, as it last looked on Home.
struct WidgetSnapshot: Codable, Sendable {
    struct Stat: Codable, Sendable, Hashable {
        let title: String
        /// SF Symbol name.
        let symbol: String
        /// "mint", "coral", "sunny" or "ink".
        let accent: String
        /// The big figure: "15", "¥18,420", "4 of 5".
        let value: String
        /// What the figure is: "entries this week".
        let caption: String
        /// "↓ 12% vs last month", when there is something to compare with.
        let change: String?
    }

    static let kind = "TinyMeStats"
    /// Opens chat with the composer ready.
    static let chatURL = URL(string: "tinyme://chat")!

    var updatedAt: Date
    var stats: [Stat]

    // MARK: - Shared storage

    private static let key = "widget.snapshot"

    /// The app group both the app and the widget can read, named in each target's Info.plist.
    private static var defaults: UserDefaults? {
        guard let group = Bundle.main.object(forInfoDictionaryKey: "TINYME_APP_GROUP") as? String, !group.isEmpty else {
            return nil
        }
        return UserDefaults(suiteName: group)
    }

    static func load() -> WidgetSnapshot? {
        guard let data = defaults?.data(forKey: key) else { return nil }
        return try? JSONDecoder().decode(WidgetSnapshot.self, from: data)
    }

    func save() {
        guard let data = try? JSONEncoder().encode(self) else { return }
        Self.defaults?.set(data, forKey: Self.key)
    }

    /// On sign-out, so the widget stops showing the last account's numbers.
    static func clear() {
        defaults?.removeObject(forKey: key)
    }
}
