import Foundation
import os

/// `GET /cards?range=`
struct CardListDTO: Decodable, Sendable, Hashable {
    let range: String
    let from: LocalDay
    let to: LocalDay
    let cards: [CardDTO]
    /// The journaling streak as of today. Absent on older servers.
    let journalStreak: JournalStreak?

    private enum CodingKeys: String, CodingKey {
        case range, from, to, cards
        case journalStreak = "journal_streak"
    }
}

/// Your journaling streak as of today: how many days in a row, and which of the last seven had an entry.
struct JournalStreak: Decodable, Sendable, Hashable {
    let current: Int
    let best: Int
    /// The last seven days, oldest first; the last one is today.
    let days: [Bool]

    var loggedToday: Bool { days.last ?? false }
    /// 7, 30, 100, … days: worth a small celebration.
    var isMilestone: Bool { loggedToday && [7, 30, 50, 100, 200, 365].contains(current) }
}

struct CardDTO: Decodable, Sendable, Hashable, Identifiable {
    let id: String
    let slug: String
    let title: String
    // Kept as raw strings: a newer server may send values this build doesn't know.
    let icon: String
    let accent: String
    let tone: String
    let status: String   // "draft" | "active"
    let system: Bool?
    let badge: BadgeDTO?
    let blocks: [BlockDTO]?
    /// Set when the server couldn't work this card out.
    let error: String?
}

struct BadgeDTO: Decodable, Sendable, Hashable {
    let kind: String   // "period" | "delta" | "streak"
    let period: String?
    let deltaPct: Double?
    let better: String?
    let compareLabel: String?
    let days: Int?

    private enum CodingKeys: String, CodingKey {
        case kind, period, better, days
        case deltaPct = "delta_pct"
        case compareLabel = "compare_label"
    }
}

enum BlockDTO: Decodable, Sendable, Hashable {
    case stat(StatBlock)
    case progress(ProgressBlock)
    case goals(GoalsBlock)
    case chart(ChartBlock)
    case checkins(CheckinsBlock)
    case breakdown(BreakdownBlock)
    case latest(LatestBlock)
    /// The server couldn't compute this block, or sent it in a shape this build can't read.
    case unavailable(blockType: String?)
    /// A block type newer than this build.
    case unknown(type: String)

    private static let log = Logger(subsystem: Bundle.main.bundleIdentifier ?? "TinyMe", category: "cards")

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        let type = try container.decode(String.self, forKey: .type)
        do {
            switch type {
            case "stat": self = .stat(try StatBlock(from: decoder))
            case "progress": self = .progress(try ProgressBlock(from: decoder))
            case "goals": self = .goals(try GoalsBlock(from: decoder))
            case "chart": self = .chart(try ChartBlock(from: decoder))
            case "checkins": self = .checkins(try CheckinsBlock(from: decoder))
            case "breakdown": self = .breakdown(try BreakdownBlock(from: decoder))
            case "latest": self = .latest(try LatestBlock(from: decoder))
            case "unavailable":
                self = .unavailable(blockType: try container.decodeIfPresent(String.self, forKey: .blockType))
            default: self = .unknown(type: type)
            }
        } catch {
            // One unreadable block shouldn't take the whole Home screen down.
            Self.log.error("Couldn't read a \(type, privacy: .public) block: \(String(describing: error), privacy: .public)")
            self = .unavailable(blockType: type)
        }
    }

    private enum CodingKeys: String, CodingKey {
        case type
        case blockType = "block_type"
    }
}

/// The period's per-day value against the daily average of the 30 days before it.
struct AverageDay: Decodable, Sendable, Hashable {
    let baseline: Double?
    let currentPerDay: Double?
    let deltaPct: Double?

    private enum CodingKeys: String, CodingKey {
        case baseline
        case currentPerDay = "current_per_day"
        case deltaPct = "delta_pct"
    }
}

extension BlockDTO {
    /// How many entries the block's number is made of, for blocks that count them.
    var entryCount: Int? {
        switch self {
        case .stat(let block): block.entries
        case .progress(let block): block.entries
        default: nil
        }
    }

    var averageDay: AverageDay? {
        switch self {
        case .stat(let block): block.averageDay
        case .progress(let block): block.averageDay
        case .chart(let block): block.averageDay
        case .checkins(let block): block.averageDay
        case .breakdown(let block): block.averageDay
        case .goals, .latest, .unavailable, .unknown: nil
        }
    }
}

struct StatBlock: Decodable, Sendable, Hashable {
    let label: String
    let unit: String?
    let value: Double?
    let goal: Double?
    let entries: Int
    let previous: Double?
    let deltaPct: Double?
    let averageDay: AverageDay?

    private enum CodingKeys: String, CodingKey {
        case label, unit, value, goal, entries, previous
        case deltaPct = "delta_pct"
        case averageDay = "average_day"
    }
}

struct ProgressBlock: Decodable, Sendable, Hashable {
    let label: String
    let unit: String?
    let style: String   // "gauge" | "ring" | "bar"
    let value: Double?
    let goal: Double?
    let remaining: Double?
    let pct: Double?
    let entries: Int
    let averageDay: AverageDay?

    private enum CodingKeys: String, CodingKey {
        case label, unit, style, value, goal, remaining, pct, entries
        case averageDay = "average_day"
    }
}

struct GoalsBlock: Decodable, Sendable, Hashable {
    struct Item: Decodable, Sendable, Hashable {
        let label: String
        let value: Double?
        let goal: Double?
        let pct: Double?
        let unit: String?
        let averageDay: AverageDay?

        private enum CodingKeys: String, CodingKey {
            case label, value, goal, pct, unit
            case averageDay = "average_day"
        }
    }

    /// The server lets a goals block go without a title.
    let label: String?
    let items: [Item]
}

struct ChartBlock: Decodable, Sendable, Hashable {
    struct Point: Decodable, Sendable, Hashable {
        let period: LocalDay
        let value: Double?
        let entries: Int
        let state: String   // "past" | "current" | "future"
    }

    let label: String
    let unit: String?
    let style: String   // "bars" | "line"
    let points: [Point]
    let goalLine: Double?
    let peakIndex: Int?
    let averageDay: AverageDay?

    private enum CodingKeys: String, CodingKey {
        case label, unit, style, points
        case goalLine = "goal_line"
        case peakIndex = "peak_index"
        case averageDay = "average_day"
    }
}

struct CheckinsBlock: Decodable, Sendable, Hashable {
    struct Slot: Decodable, Sendable, Hashable {
        let period: LocalDay
        let state: String
        let done: Bool
        let activity: String?
    }

    struct Streak: Decodable, Sendable, Hashable {
        let current: Int
        let best: Int
    }

    let label: String
    let slots: [Slot]
    let doneCount: Int
    let goal: Int?
    let streak: Streak?
    let averageDay: AverageDay?

    private enum CodingKeys: String, CodingKey {
        case label, slots, goal, streak
        case doneCount = "done_count"
        case averageDay = "average_day"
    }
}

struct BreakdownBlock: Decodable, Sendable, Hashable {
    struct Slice: Decodable, Sendable, Hashable {
        let key: String?   // nil = "Unlabeled"
        let value: Double?
        let pct: Double?
    }

    struct Other: Decodable, Sendable, Hashable {
        let value: Double?
        let pct: Double?
    }

    let label: String
    let style: String   // "segmented" | "bars"
    let unit: String?
    let total: Double?
    let slices: [Slice]
    let other: Other?
    let averageDay: AverageDay?

    private enum CodingKeys: String, CodingKey {
        case label, style, unit, total, slices, other
        case averageDay = "average_day"
    }
}

struct LatestBlock: Decodable, Sendable, Hashable {
    struct Entry: Decodable, Sendable, Hashable {
        let ts: Date
        let localDay: LocalDay
        let text: String?
        let data: [String: JSONValue]

        private enum CodingKeys: String, CodingKey {
            case ts, text, data
            case localDay = "local_day"
        }
    }

    let label: String
    let entry: Entry?
}

/// `GET /cards/suggestions`
struct SuggestionDTO: Decodable, Sendable, Hashable {
    let text: String
    let kind: String
}
