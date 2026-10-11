import Foundation

/// Sends a chat message and yields the server's reply as it streams back (SSE, one `data:` line per event).
actor StreamClient {
    private let api: APIClient
    private let session: URLSession

    init(api: APIClient, session: URLSession = .shared) {
        self.api = api
        self.session = session
    }

    nonisolated func send(_ message: OutgoingMessage) -> AsyncThrowingStream<StreamEvent, Error> {
        AsyncThrowingStream { continuation in
            let task = Task {
                do {
                    var request = try await api.authorizedRequest(.postMessage, body: message)
                    request.setValue("text/event-stream", forHTTPHeaderField: "Accept")
                    request.timeoutInterval = 180
                    let (bytes, response) = try await session.bytes(for: request)
                    try api.check(response)

                    let decoder = JSONDecoder()
                    var sawDone = false
                    // `bytes.lines` drops empty lines, so framing relies on one `data:` line per event.
                    lines: for try await line in bytes.lines {
                        guard line.hasPrefix("data:") else { continue }   // skips ": ping" heartbeats
                        let event = try decoder.decode(StreamEvent.self, from: Data(line.dropFirst(5).utf8))
                        switch event {
                        case .unknown:
                            continue
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
}
