import Foundation
import Observation

/// How far back the first import reaches.
enum HealthImportDepth: Int, CaseIterable, Codable, Sendable, Identifiable {
    case month = 30
    case quarter = 90
    case year = 365

    var id: Self { self }

    var title: String {
        switch self {
        case .month: "30 days"
        case .quarter: "90 days"
        case .year: "1 year"
        }
    }
}

/// The Apple Health switches, kept on this phone and mirrored to the server (`PUT /health/settings`).
///
/// These switches, not iOS's permissions, decide what is read: iOS keeps a permission after a group is turned
/// off here, so the sync engine reads only `readGroups`.
@MainActor
@Observable
final class HealthSettingsStore {
    struct Settings: Codable, Equatable, Sendable {
        var groups: Set<HealthGroup> = Set(HealthGroup.allCases)
        var nudgesOff: Set<HealthNudgeTopic> = []
        var importDepth: HealthImportDepth = .quarter
        /// Groups iOS has already shown its permission sheet for. Empty means not connected yet.
        var askedGroups: Set<HealthGroup> = []
        /// The server hasn't got these switches yet (the last save failed); sent again on foreground.
        var needsPush = false
    }

    private static let key = "health.settings"
    private let defaults: UserDefaults

    /// What was last saved, and so what is connected.
    private(set) var saved: Settings

    var isConnected: Bool { !saved.askedGroups.isEmpty }

    /// What the sync engine may read: the groups switched on here, once connected.
    var readGroups: Set<HealthGroup> { isConnected ? saved.groups : [] }

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        saved = defaults.data(forKey: Self.key).flatMap { try? JSONDecoder().decode(Settings.self, from: $0) } ?? Settings()
    }

    func save(_ settings: Settings) {
        saved = settings
        if let data = try? JSONEncoder().encode(settings) { defaults.set(data, forKey: Self.key) }
    }

    /// Saves the switches here, then sends them to the server. If that fails they're kept with `needsPush`,
    /// so this phone and the agent don't disagree for long.
    func save(_ settings: Settings, sendingWith api: APIClient) async throws {
        var next = settings
        next.needsPush = true
        save(next)
        try await api.send(.healthSettings, body: HealthSettingsBody(next))
        next.needsPush = false
        if saved == settings.withNeedsPush(true) { save(next) }
    }

    /// Foreground and opening the Apple Health screen: retries a save the server missed.
    func pushIfNeeded(api: APIClient?) async {
        guard saved.needsPush, let api else { return }
        let pending = saved
        guard (try? await api.send(.healthSettings, body: HealthSettingsBody(pending))) != nil else { return }
        if saved == pending { save(pending.withNeedsPush(false)) }
    }

    /// Sign-out and Delete all: the next connect starts from the defaults (iOS keeps its own permissions).
    func reset() {
        saved = Settings()
        defaults.removeObject(forKey: Self.key)
        // The import starts over too, or old history would never be sent again.
        defaults.removeObject(forKey: HealthSyncEngine.cursorKey)
        defaults.removeObject(forKey: HealthSyncEngine.anchorKey)
    }
}

extension HealthSettingsStore.Settings {
    func withNeedsPush(_ value: Bool) -> Self {
        var copy = self
        copy.needsPush = value
        return copy
    }

    /// Everything switched off: connected, but nothing is read.
    var isAllOff: Bool { groups.isEmpty }

    /// What the person chose, without the bookkeeping (what iOS was asked, whether the server has it).
    struct Choices: Equatable {
        let groups: Set<HealthGroup>
        let nudgesOff: Set<HealthNudgeTopic>
        let importDepth: HealthImportDepth
    }

    var choices: Choices { Choices(groups: groups, nudgesOff: nudgesOff, importDepth: importDepth) }
}

struct HealthSettingsBody: Codable, Sendable, Equatable {
    let groups: [String]
    /// Always false: TinyMe only reads Health.
    let writeEnabled: Bool
    let nudgesOff: [String]

    init(_ settings: HealthSettingsStore.Settings) {
        groups = HealthGroup.allCases.filter(settings.groups.contains).map(\.rawValue)
        writeEnabled = false   // TinyMe never writes to Health
        nudgesOff = HealthNudgeTopic.allCases.filter(settings.nudgesOff.contains).map(\.rawValue)
    }

    private enum CodingKeys: String, CodingKey {
        case groups
        case writeEnabled = "write_enabled"
        case nudgesOff = "nudges_off"
    }
}
