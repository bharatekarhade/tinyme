import Foundation

/// Runs device tools and reports the outcome to the server.
/// Used for live `.clientAction` stream events and for pending actions from `GET /sync`.
actor DeviceToolRegistry {
    private let api: APIClient
    private let tools: [String: any DeviceTool]

    init(api: APIClient, tools: [any DeviceTool]) {
        self.api = api
        self.tools = Dictionary(tools.map { ($0.name, $0) }, uniquingKeysWith: { first, _ in first })
    }

    func execute(actionId: String, tool: String, input: JSONValue) async {
        let result: ActionResult
        if let handler = tools[tool] {
            do {
                result = .ok(try await handler.run(input))
            } catch {
                result = .error(code: "device_error", message: error.localizedDescription)
            }
        } else {
            result = .error(code: "unknown_tool", message: "This version of the app can't run \(tool)")
        }
        try? await api.postActionResult(actionId, result)
    }
}
