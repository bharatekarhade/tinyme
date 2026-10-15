import Foundation
import Network
import SwiftData
import os

/// Sends queued messages one at a time, oldest first, and keeps trying while the server can't be reached.
/// Chat follows `subscribe()` to show the queue and the reply as it streams.
@ModelActor
actor OutboxWorker {
    enum Outcome: Sendable, Equatable {
        /// The reply finished. `changedData`: it touched entries or cards.
        case completed(changedData: Bool)
        /// The stream dropped mid-reply. The server finishes the turn anyway.
        case lost
        /// The server already had this message from an earlier try.
        case duplicate
    }

    enum Event: Sendable {
        case queue(OutboxSnapshot)
        /// A piece of the reply to the message being sent.
        case reply(UUID, StreamEvent)
        /// The message left the outbox. The snapshot is the queue without it.
        case finished(OutboxEntry, Outcome, OutboxSnapshot)
    }

    /// Seconds to wait after the 1st, 2nd, … failed try; `longestWait` after that.
    static let backoff: [TimeInterval] = [2, 5, 15, 60, 120]
    static let longestWait: TimeInterval = 300

    private static let log = Logger(subsystem: Bundle.main.bundleIdentifier ?? "TinyMe", category: "outbox")

    private var client: StreamClient?
    private var isOnline = true
    private var isPrepared = false
    private var isDraining = false
    private var timer: Task<Void, Never>?
    private var monitor: NWPathMonitor?
    /// Server message ids of accepted messages. Not stored: an accepted message never needs resending.
    private var acceptedAs: [UUID: String] = [:]
    private var subscribers: [UUID: AsyncStream<Event>.Continuation] = [:]

    /// The outbox stored in the app's own folder. Falls back to memory if the store can't be opened.
    static func onDisk() -> OutboxWorker {
        let schema = Schema([OutboxItem.self])
        do {
            let configuration = ModelConfiguration("Outbox", schema: schema, groupContainer: .none, cloudKitDatabase: .none)
            return OutboxWorker(modelContainer: try ModelContainer(for: schema, configurations: configuration))
        } catch {
            log.error("Couldn't open the outbox store, queueing in memory: \(String(describing: error), privacy: .public)")
            let configuration = ModelConfiguration(schema: schema, isStoredInMemoryOnly: true, cloudKitDatabase: .none)
            return OutboxWorker(modelContainer: try! ModelContainer(for: schema, configurations: configuration))
        }
    }

    // MARK: - Queue

    func enqueue(id: UUID = UUID(), text: String, photoData: Data? = nil, at date: Date = .now) {
        prepare()
        modelContext.insert(OutboxItem(clientMsgId: id, text: text, createdAt: date, photoData: photoData))
        save()
        broadcastQueue()
        wake()
    }

    /// Puts a refused message back in the queue.
    func retry(_ id: UUID, photoData replacementPhotoData: Data? = nil) {
        prepare()
        guard let item = item(id), item.status == OutboxStatus.failed.rawValue else { return }
        if let replacementPhotoData { item.photoData = replacementPhotoData }
        item.status = OutboxStatus.queued.rawValue
        item.nextAttemptAt = nil
        item.lastError = nil
        save()
        broadcastQueue()
        wake()
    }

    /// Drops a message that isn't on its way.
    func delete(_ id: UUID) {
        prepare()
        guard let item = item(id), item.status == OutboxStatus.failed.rawValue || item.status == OutboxStatus.queued.rawValue else {
            return
        }
        modelContext.delete(item)
        save()
        broadcastQueue()
    }

    /// Forgets every message, sent or not. For after "Delete all data": nothing queued should land in the fresh log.
    func clear() {
        prepare()
        for item in items() {
            modelContext.delete(item)
        }
        acceptedAs.removeAll()
        save()
        broadcastQueue()
    }

    func snapshot() -> OutboxSnapshot {
        prepare()
        return OutboxSnapshot(entries: items().map(entry), isOnline: isOnline)
    }

    /// The queue as it is now, and every change after it.
    func subscribe() -> (OutboxSnapshot, AsyncStream<Event>) {
        let id = UUID()
        let (stream, continuation) = AsyncStream<Event>.makeStream()
        subscribers[id] = continuation
        continuation.onTermination = { [weak self] _ in
            Task { await self?.unsubscribe(id) }
        }
        return (snapshot(), stream)
    }

    // MARK: - Waking

    /// The server to send to; nil while signed out. Queued messages wait for the next sign-in.
    func connect(_ client: StreamClient?) {
        self.client = client
        if client != nil {
            wake(retryNow: true)
        }
    }

    /// Sends what is due. `retryNow` skips the backoff wait: the connection is back, or the app was just opened.
    func wake(retryNow: Bool = false) {
        prepare()
        if retryNow {
            for item in items() where item.status == OutboxStatus.queued.rawValue && item.nextAttemptAt != nil {
                item.nextAttemptAt = nil
            }
            save()
        }
        guard !isDraining else { return }   // already sending; it looks again after each message
        isDraining = true
        Task { await self.drain() }
    }

    /// Wakes the worker whenever the network comes back.
    func startMonitoring() {
        guard monitor == nil else { return }
        let monitor = NWPathMonitor()
        monitor.pathUpdateHandler = { [weak self] path in
            let online = path.status == .satisfied
            Task { await self?.setOnline(online) }
        }
        monitor.start(queue: .global(qos: .utility))
        self.monitor = monitor
    }

    func setOnline(_ online: Bool) {
        guard online != isOnline else { return }
        isOnline = online
        broadcastQueue()
        if online {
            wake(retryNow: true)
        }
    }

    // MARK: - Sending

    private func drain() async {
        while let client, isOnline, let next = nextDue() {
            await deliver(next, over: client)
        }
        isDraining = false
        scheduleTimer()
    }

    /// The oldest queued message, if it is due. A message waiting out its backoff holds back the ones after it,
    /// so they arrive in the order they were said; a refused one doesn't.
    private func nextDue() -> OutboxEntry? {
        guard let head = items().first(where: { $0.status == OutboxStatus.queued.rawValue }) else { return nil }
        if let due = head.nextAttemptAt, due > .now {
            return nil
        }
        return entry(head)
    }

    private func deliver(_ entry: OutboxEntry, over client: StreamClient) async {
        let id = entry.id
        update(id) { $0.status = OutboxStatus.sending.rawValue }

        var started = false
        var finished = false
        var changedData = false
        do {
            for try await event in client.send(OutgoingMessage(
                clientMsgId: id, text: entry.text, sentAt: entry.createdAt,
                attachments: entry.photoData.map { [OutgoingMessage.Attachment(mediaType: "image/jpeg", imageData: $0)] } ?? [])) {
                switch event {
                case .turnStarted(_, let userMessageId):
                    // The server has it now: from here on it is never sent again.
                    started = true
                    acceptedAs[id] = userMessageId
                    update(id) { $0.status = OutboxStatus.sent.rawValue }
                case .actionDone(let tool, let summary, let isError, let ref):
                    changedData = changedData || ActionDTO(tool: tool, summary: summary, isError: isError, ref: ref).changesHomeData
                case .done:
                    finished = true
                default:
                    break
                }
                broadcast(.reply(id, event))
            }
            if finished {
                finish(id, .completed(changedData: changedData))
            } else if started {
                finish(id, .lost)
            } else {
                requeue(id, countingAttempt: false, error: nil)   // the send was cancelled
            }
        } catch APIError.unauthorized, APIError.notSignedIn {
            // The app is signing out. The message stays for after the next sign-in.
            self.client = nil
            requeue(id, countingAttempt: false, error: nil)
        } catch APIError.duplicate {
            finish(id, .duplicate)
        } catch {
            if started {
                finish(id, .lost)
            } else if Self.isWorthRetrying(error) {
                requeue(id, countingAttempt: true, error: Self.describe(error))
            } else {
                update(id) {
                    $0.status = OutboxStatus.failed.rawValue
                    $0.nextAttemptAt = nil
                    $0.lastError = Self.describe(error)
                }
            }
        }
    }

    private func requeue(_ id: UUID, countingAttempt: Bool, error: String?) {
        update(id) { item in
            item.status = OutboxStatus.queued.rawValue
            item.lastError = error
            if countingAttempt {
                item.attempts += 1
                item.nextAttemptAt = Date.now.addingTimeInterval(Self.delay(afterAttempts: item.attempts))
            }
        }
    }

    private func finish(_ id: UUID, _ outcome: Outcome) {
        guard let item = item(id) else { return }
        let entry = entry(item)
        modelContext.delete(item)
        save()
        acceptedAs[id] = nil
        broadcast(.finished(entry, outcome, snapshot()))
    }

    static func delay(afterAttempts attempts: Int) -> TimeInterval {
        attempts >= 1 && attempts <= backoff.count ? backoff[attempts - 1] : longestWait
    }

    /// No connection, a dropped one, or a server that is down or busy: all pass on their own.
    private static func isWorthRetrying(_ error: Error) -> Bool {
        switch error {
        case is URLError, APIError.streamEndedEarly, APIError.invalidResponse:
            true
        case APIError.server(let status, _, _), APIError.http(let status):
            status >= 500 || status == 408 || status == 429
        default:
            false
        }
    }

    private static func describe(_ error: Error) -> String {
        switch error {
        case APIError.server(_, _, let detail?): detail
        case APIError.server(let status, _, _), APIError.http(let status): "The server answered \(status)."
        case is URLError: "Can't reach your server."
        default: "Something went wrong."
        }
    }

    private func scheduleTimer() {
        timer?.cancel()
        timer = nil
        guard client != nil, isOnline,
              let due = items().first(where: { $0.status == OutboxStatus.queued.rawValue })?.nextAttemptAt else { return }
        let wait = max(0, due.timeIntervalSinceNow)
        timer = Task { [weak self] in
            try? await Task.sleep(for: .seconds(wait))
            guard !Task.isCancelled else { return }
            await self?.wake()
        }
    }

    // MARK: - Store

    /// Tidies up after a run that was killed mid-send. Runs once, before anything else touches the queue.
    private func prepare() {
        guard !isPrepared else { return }
        isPrepared = true
        for item in items() {
            switch OutboxStatus(rawValue: item.status) {
            case .sending:
                // It may or may not have arrived. Sending it again is safe: the server answers 409 if it had.
                item.status = OutboxStatus.queued.rawValue
            case .sent:
                modelContext.delete(item)
            default:
                break
            }
        }
        save()
    }

    private func items() -> [OutboxItem] {
        (try? modelContext.fetch(FetchDescriptor<OutboxItem>(sortBy: [SortDescriptor(\.createdAt)]))) ?? []
    }

    private func item(_ id: UUID) -> OutboxItem? {
        items().first { $0.clientMsgId == id }
    }

    private func entry(_ item: OutboxItem) -> OutboxEntry {
        OutboxEntry(
            id: item.clientMsgId,
            text: item.text,
            photoData: item.photoData,
            createdAt: item.createdAt,
            status: OutboxStatus(rawValue: item.status) ?? .queued,
            attempts: item.attempts,
            nextAttemptAt: item.nextAttemptAt,
            lastError: item.lastError,
            serverMessageId: acceptedAs[item.clientMsgId]
        )
    }

    private func update(_ id: UUID, _ change: (OutboxItem) -> Void) {
        guard let item = item(id) else { return }
        change(item)
        save()
        broadcastQueue()
    }

    private func save() {
        do {
            try modelContext.save()
        } catch {
            Self.log.error("Couldn't save the outbox: \(String(describing: error), privacy: .public)")
        }
    }

    // MARK: - Subscribers

    private func broadcastQueue() {
        broadcast(.queue(snapshot()))
    }

    private func broadcast(_ event: Event) {
        for continuation in subscribers.values {
            continuation.yield(event)
        }
    }

    private func unsubscribe(_ id: UUID) {
        subscribers[id] = nil
    }
}
