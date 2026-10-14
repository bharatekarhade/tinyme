import Foundation
import Testing
@testable import TinyMe

@MainActor
struct HealthSettingsStoreTests {
    private func store() -> HealthSettingsStore {
        let name = "tests.health.\(UUID().uuidString)"
        return HealthSettingsStore(defaults: UserDefaults(suiteName: name)!)
    }

    @Test func nothingIsReadBeforeConnecting() {
        let store = store()
        #expect(!store.isConnected)
        #expect(store.readGroups.isEmpty)
    }

    @Test func onlyTheSwitchesDecideWhatIsRead() {
        let store = store()
        var settings = store.saved
        settings.askedGroups = Set(HealthGroup.allCases)
        settings.groups = [.sleepHeart]
        store.save(settings)
        // iOS still allows all four; TinyMe reads only what's switched on.
        #expect(store.readGroups == [.sleepHeart])
    }

    @Test func allOffStaysConnected() {
        let store = store()
        var settings = store.saved
        settings.askedGroups = [.activity]
        settings.groups = []
        store.save(settings)
        #expect(store.isConnected)
        #expect(store.saved.isAllOff)
        #expect(store.readGroups.isEmpty)
    }

    @Test func theServerGetsKeysInAFixedOrder() throws {
        var settings = HealthSettingsStore.Settings()
        settings.groups = [.sleepHeart, .activity]
        settings.nudgesOff = [.steps, .sleep]
        let json = String(decoding: try JSONEncoder().encode(HealthSettingsBody(settings)), as: UTF8.self)
        #expect(json.contains(#""groups":["activity","sleep_heart"]"#))
        #expect(json.contains(#""nudges_off":["sleep","steps"]"#))
        #expect(json.contains(#""write_enabled":false"#))   // TinyMe never writes to Health
    }

    @Test func savedSwitchesSurviveARestartAndResetClearsThem() {
        let defaults = UserDefaults(suiteName: "tests.health.\(UUID().uuidString)")!
        let first = HealthSettingsStore(defaults: defaults)
        var settings = first.saved
        settings.askedGroups = [.body]
        settings.needsPush = true
        first.save(settings)

        let second = HealthSettingsStore(defaults: defaults)
        #expect(second.isConnected)
        #expect(second.saved.needsPush)

        second.reset()
        #expect(!HealthSettingsStore(defaults: defaults).isConnected)
    }
}
