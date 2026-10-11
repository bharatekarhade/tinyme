import Foundation

/// Something only the phone can do for the agent (reminders, location, …).
protocol DeviceTool: Sendable {
    var name: String { get }   // e.g. "device_create_reminder"
    func run(_ input: JSONValue) async throws -> JSONValue
}
