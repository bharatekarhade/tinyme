import Foundation
import Testing
@testable import TinyMe

/// The phone's Health catalog and the server's seed/health/kinds.json must agree, or /health/sync answers 400.
/// Reads the server's file straight from the repo, so there's no copy to keep in step.
struct HealthCatalogTests {
    private struct ServerKind: Decodable {
        let kind: String
        let group: String
        let quantity_field: String?
        let data_hints: [String: String]
    }

    private struct ServerFile: Decodable {
        let kinds: [ServerKind]
    }

    private static func serverKinds() throws -> [String: ServerKind] {
        let file = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
            .appending(path: "server/src/main/resources/seed/health/kinds.json")
        let decoded = try JSONDecoder().decode(ServerFile.self, from: Data(contentsOf: file))
        return Dictionary(uniqueKeysWithValues: decoded.kinds.map { ($0.kind, $0) })
    }

    @Test func bothSidesListTheSameKinds() throws {
        let server = try Self.serverKinds()
        #expect(Set(HealthCatalog.metrics.map(\.kind)) == Set(server.keys))
    }

    @Test func eachKindIsInTheSameGroupWithTheSameMainField() throws {
        let server = try Self.serverKinds()
        for metric in HealthCatalog.metrics {
            let kind = try #require(server[metric.kind], "\(metric.kind) is missing on the server")
            #expect(kind.group == metric.group.rawValue, "\(metric.kind): group")
            #expect(kind.quantity_field == metric.quantityField, "\(metric.kind): quantity field")
            if let field = metric.field {
                #expect(kind.data_hints[field] == "number", "\(metric.kind).\(field) must be a number on the server")
            }
        }
    }

    @Test func groupKeysMatchTheServersSettingsValues() {
        #expect(HealthGroup.allCases.map(\.rawValue) == ["activity", "workouts", "body", "sleep_heart"])
        #expect(HealthNudgeTopic.allCases.map(\.rawValue) == ["workouts", "sleep", "weight", "steps"])
    }
}
