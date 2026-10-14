import Foundation

/// Sends batches to `POST /health/sync`. When the server names one bad item (400 with "items[3]: …"),
/// that item is dropped and the rest are sent again, so one odd sample can't block syncing for good.
struct HealthUploader: Sendable {
    static let maxItems = 500
    let api: APIClient

    struct Totals: Sendable, Equatable {
        var upserted = 0, unchanged = 0, deleted = 0, dropped = 0
        var changedSomething: Bool { upserted > 0 || deleted > 0 }
    }

    func send(items: [HealthUploadItem], deleted: [String]) async throws -> Totals {
        var totals = Totals()
        var start = 0
        var deletesLeft = deleted
        repeat {
            var batch = HealthSyncBatch(items: Array(items[start..<min(start + Self.maxItems, items.count)]),
                                        deleted: Array(deletesLeft.prefix(Self.maxItems)))
            let reply = try await sendDroppingBad(&batch, totals: &totals)
            totals.upserted += reply.upserted
            totals.unchanged += reply.unchanged
            totals.deleted += reply.deleted
            start += Self.maxItems
            deletesLeft = Array(deletesLeft.dropFirst(Self.maxItems))
        } while start < items.count || !deletesLeft.isEmpty
        return totals
    }

    private func sendDroppingBad(_ batch: inout HealthSyncBatch, totals: inout Totals) async throws -> HealthSyncReply {
        while true {
            do {
                return try await api.send(.healthSync, body: batch)
            } catch APIError.server(status: 400, _, let detail?) {
                guard let bad = Self.badItem(in: detail) else { throw APIError.server(status: 400, title: nil, detail: detail) }
                switch bad {
                case .item(let index) where batch.items.indices.contains(index): batch.items.remove(at: index)
                case .deleted(let index) where batch.deleted.indices.contains(index): batch.deleted.remove(at: index)
                default: throw APIError.server(status: 400, title: nil, detail: detail)
                }
                totals.dropped += 1
                if batch.items.isEmpty && batch.deleted.isEmpty { return HealthSyncReply(upserted: 0, unchanged: 0, deleted: 0, merged: 0) }
            }
        }
    }

    enum BadItem: Equatable {
        case item(Int), deleted(Int)
    }

    /// The server starts the detail with "items[3]:" or "deleted[1]:" (HealthSyncApiTests pins the format).
    static func badItem(in detail: String) -> BadItem? {
        for (prefix, make) in [("items[", BadItem.item), ("deleted[", BadItem.deleted)] where detail.hasPrefix(prefix) {
            let rest = detail.dropFirst(prefix.count)
            guard let close = rest.firstIndex(of: "]"), let index = Int(rest[..<close]) else { return nil }
            return make(index)
        }
        return nil
    }
}
