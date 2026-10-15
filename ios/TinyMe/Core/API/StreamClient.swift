import Foundation
import os

/// Sends a chat message and yields the server's reply as it streams back (SSE, one `data:` line per event).
actor StreamClient {
    private static let log = Logger(subsystem: Bundle.main.bundleIdentifier ?? "TinyMe", category: "stream")

    private let api: APIClient
    private let session: URLSession
    private let locationProvider: LocationProvider?
    private let deviceTools: DeviceToolRegistry?

    init(api: APIClient, session: URLSession = .shared, locationProvider: LocationProvider? = nil,
         deviceTools: [any DeviceTool] = []) {
        self.api = api
        self.session = session
        self.locationProvider = locationProvider
        self.deviceTools = deviceTools.isEmpty ? nil : DeviceToolRegistry(api: api, tools: deviceTools)
    }

    nonisolated func send(_ message: OutgoingMessage) -> AsyncThrowingStream<StreamEvent, Error> {
        AsyncThrowingStream { continuation in
            let task = Task {
                do {
                    let enrichedMessage = await self.messageWithLocation(message)
                    var request = try await api.authorizedRequest(.postMessage, body: enrichedMessage)
                    request.setValue("text/event-stream", forHTTPHeaderField: "Accept")
                    request.timeoutInterval = 180
                    let (bytes, response) = try await session.bytes(for: request)
                    try await check(response, bytes)

                    let decoder = JSONDecoder()
                    var sawDone = false
                    var deviceActions: [Task<Void, Never>] = []
                    defer { deviceActions.forEach { $0.cancel() } }
                    // `bytes.lines` drops empty lines, so framing relies on one `data:` line per event.
                    lines: for try await line in bytes.lines {
                        guard line.hasPrefix("data:") else { continue }   // skips ": ping" heartbeats
                        let event: StreamEvent
                        do {
                            event = try decoder.decode(StreamEvent.self, from: Data(line.dropFirst(5).utf8))
                        } catch {
                            // One bad line shouldn't lose the rest of the reply.
                            Self.log.error("Skipping undecodable stream event: \(String(describing: error), privacy: .public)")
                            continue
                        }
                        switch event {
                        case .unknown(let type):
                            Self.log.debug("Skipping unknown stream event type \(type, privacy: .public)")
                        case .clientAction(let actionId, let tool, let input):
                            // Runs beside the read loop: the stream keeps flowing and cancelling the send stops it too.
                            let action = Task { await self.executeDeviceAction(actionId: actionId, tool: tool, input: input) }
                            deviceActions.append(action)
                            continuation.yield(event)
                        case .done:
                            sawDone = true
                            continuation.yield(event)
                            break lines
                        default:
                            continuation.yield(event)
                        }
                    }
                    // The connection closing without `done` means the turn was cut off, not completed.
                    guard sawDone else { throw APIError.streamEndedEarly }
                    continuation.finish()
                } catch {
                    // A cancelled send (the caller stopped listening) is not a failure.
                    continuation.finish(throwing: Task.isCancelled ? nil : error)
                }
            }
            continuation.onTermination = { _ in task.cancel() }
        }
    }

    /// Adds where the phone is, but only for a message said in the last 2 minutes: one queued while offline
    /// shouldn't carry where the phone is when it finally uploads.
    private func messageWithLocation(_ message: OutgoingMessage) async -> OutgoingMessage {
        guard let locationProvider, var context = message.clientContext, context.location == nil else {
            return message
        }
        if let sentAt = message.sentAt, Date.now.timeIntervalSince(sentAt) > 120 { return message }
        context.location = await locationProvider.cachedLocationForChatContext()
        var result = message
        result.clientContext = context
        return result
    }

    private func executeDeviceAction(actionId: String, tool: String, input: JSONValue) async {
        guard let deviceTools else { return }
        await deviceTools.execute(actionId: actionId, tool: tool, input: input)
    }

    /// Throws for a non-2xx reply, reading its (small) error body first.
    private nonisolated func check(_ response: URLResponse, _ bytes: URLSession.AsyncBytes) async throws {
        guard let http = response as? HTTPURLResponse, !(200..<300).contains(http.statusCode) else {
            return try await api.validate(response, for: .postMessage)
        }
        var body = Data()
        for try await byte in bytes where body.count < 64 * 1024 {
            body.append(byte)
        }
        if http.statusCode == 409 {
            let duplicate = try? JSONDecoder().decode(DuplicateBody.self, from: body)
            throw APIError.duplicate(messageId: duplicate?.messageId)
        }
        try await api.validate(response, data: body, for: .postMessage)
    }

    private struct DuplicateBody: Decodable {
        let messageId: String?

        private enum CodingKeys: String, CodingKey {
            case messageId = "message_id"
        }
    }
}
