import Foundation

/// Build-time settings from Config/TinyMe.xcconfig, surfaced through Info.plist.
enum AppConfig {
    static let defaultServerURL: URL? = string("TINYME_DEFAULT_SERVER_URL").flatMap(URL.init(string:))
    static let pushEnabled: Bool = string("TINYME_PUSH_ENABLED") == "YES"

    private static func string(_ key: String) -> String? {
        guard let value = Bundle.main.object(forInfoDictionaryKey: key) as? String, !value.isEmpty else {
            return nil
        }
        return value
    }
}
