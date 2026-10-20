import AppIntents
import WidgetKit

/// Edit Widget: show one tracker, or leave it empty to take turns through all of them.
struct ChooseTrackerIntent: WidgetConfigurationIntent {
    static let title: LocalizedStringResource = "Choose a tracker"
    static let description = IntentDescription("Show one tracker, or take turns through all of them.")

    @Parameter(title: "Tracker", description: "Leave empty to take turns through all your trackers.")
    var tracker: TrackerEntity?
}

/// A Home card with a single figure, as the app last handed it to the widget.
struct TrackerEntity: AppEntity {
    static let typeDisplayRepresentation: TypeDisplayRepresentation = "Tracker"
    static let defaultQuery = TrackerQuery()

    let id: String
    let title: String

    var displayRepresentation: DisplayRepresentation {
        DisplayRepresentation(title: "\(title)")
    }
}

/// The trackers to pick from: the cards in the app's last snapshot, in Home's order.
struct TrackerQuery: EntityQuery {
    func entities(for identifiers: [TrackerEntity.ID]) async throws -> [TrackerEntity] {
        Self.all().filter { identifiers.contains($0.id) }
    }

    func suggestedEntities() async throws -> [TrackerEntity] {
        Self.all()
    }

    static func all() -> [TrackerEntity] {
        (WidgetSnapshot.load()?.stats ?? []).compactMap { stat in
            stat.id.map { TrackerEntity(id: $0, title: stat.title) }
        }
    }
}
