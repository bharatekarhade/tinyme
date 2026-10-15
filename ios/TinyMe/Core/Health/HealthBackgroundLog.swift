import Foundation

/// What background delivery did, kept on the phone: whether iOS accepted each type, and each time it woke TinyMe.
/// The Apple Health screen shows the latest line; the whole list can be read off the device when debugging.
enum HealthBackgroundLog {
    static let key = "health.background.log"
    static let limit = 60

    static func add(_ line: String, defaults: UserDefaults = .standard) {
        var lines = defaults.stringArray(forKey: key) ?? []
        lines.append("\(Date.now.formatted(.iso8601)) \(line)")
        defaults.set(Array(lines.suffix(limit)), forKey: key)
    }

    static func lines(defaults: UserDefaults = .standard) -> [String] {
        defaults.stringArray(forKey: key) ?? []
    }

    /// The newest wake-up, as (time, kind), or nil if iOS hasn't woken TinyMe yet.
    static func lastWake(defaults: UserDefaults = .standard) -> (Date, String)? {
        for line in lines(defaults: defaults).reversed() {
            let parts = line.split(separator: " ")
            guard parts.count >= 3, parts[1] == "woke", let date = try? Date(String(parts[0]), strategy: .iso8601) else { continue }
            return (date, String(parts[2]))
        }
        return nil
    }

    /// A refusal from iOS when background delivery was switched on, if the last attempt failed.
    static func lastRefusal(defaults: UserDefaults = .standard) -> String? {
        guard let line = lines(defaults: defaults).last(where: { $0.contains(" enable ") }), line.contains(" failed") else { return nil }
        return line.components(separatedBy: " failed: ").last
    }
}
