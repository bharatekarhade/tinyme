import Foundation

/// One thing the user logged.
struct EntryDTO: Decodable, Sendable, Hashable, Identifiable {
    let id: String
    let ts: Date
    let localDay: LocalDay
    let kind: String
    let source: String
    let quantity: Double
    let text: String?
    let data: [String: JSONValue]

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        id = try container.decode(String.self, forKey: .id)
        ts = try container.decode(Date.self, forKey: .ts)
        localDay = try container.decode(LocalDay.self, forKey: .localDay)
        kind = try container.decode(String.self, forKey: .kind)
        source = try container.decodeIfPresent(String.self, forKey: .source) ?? "chat"
        quantity = try container.decodeIfPresent(Double.self, forKey: .quantity) ?? 1
        text = try container.decodeIfPresent(String.self, forKey: .text)
        data = try container.decodeIfPresent([String: JSONValue].self, forKey: .data) ?? [:]
    }

    private enum CodingKeys: String, CodingKey {
        case id, ts, kind, source, quantity, text, data
        case localDay = "local_day"
    }
}

/// `GET /entries`
struct EntryPageDTO: Decodable, Sendable {
    let entries: [EntryDTO]
    let nextCursor: String?
    let unknownKinds: [String]

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        entries = try container.decode([EntryDTO].self, forKey: .entries)
        nextCursor = try container.decodeIfPresent(String.self, forKey: .nextCursor)
        unknownKinds = try container.decodeIfPresent([String].self, forKey: .unknownKinds) ?? []
    }

    private enum CodingKeys: String, CodingKey {
        case entries
        case nextCursor = "next_cursor"
        case unknownKinds = "unknown_kinds"
    }
}

/// `GET /kinds`: a kind of entry logged in the last 90 days, for the History chips.
struct KindDTO: Decodable, Sendable, Hashable, Identifiable {
    let kind: String
    let label: String
    let count: Int
    let lastLoggedOn: LocalDay?

    var id: String { kind }

    private enum CodingKeys: String, CodingKey {
        case kind, label, count
        case lastLoggedOn = "last_logged_on"
    }
}

/// `GET /cards/{id}/entries`: the entries behind one block of a card, with the total they add up to.
struct CardEntriesDTO: Decodable, Sendable {
    struct CardHeader: Decodable, Sendable {
        let id: String
        let title: String
        let icon: String
        let accent: String
        let tone: String
    }

    struct BlockHeader: Decodable, Sendable {
        let index: Int
        let type: String
        let label: String?
        let op: String?
        let unit: String?
    }

    struct Filter: Decodable, Sendable {
        let day: LocalDay?
        let key: String?
    }

    struct DayTotal: Decodable, Sendable {
        let day: LocalDay
        let value: Double?
    }

    let card: CardHeader
    let block: BlockHeader
    let range: String
    /// "today", "this_week", "this_month", "this_year", "day" (narrowed to one day) or "all_time".
    let period: String
    let from: LocalDay?
    let to: LocalDay?
    let filter: Filter?
    let total: Double?
    let dayTotals: [DayTotal]?
    let entries: [EntryDTO]
    let nextCursor: String?

    private enum CodingKeys: String, CodingKey {
        case card, block, range, period, from, to, filter, total, entries
        case dayTotals = "day_totals"
        case nextCursor = "next_cursor"
    }
}
