import Foundation

enum HomeRange: String, CaseIterable, Identifiable {
    case day = "Day"
    case week = "Week"
    case month = "Month"
    case year = "Year"

    var id: Self { self }

    var subtitle: String {
        switch self {
        case .day: "Here's how your day is going."
        case .week: "Here's how your week is going."
        case .month: "Here's how your month is going."
        case .year: "Here's how your year is going."
        }
    }
}

struct JournalStat {
    struct Day: Identifiable {
        let id: Int
        let label: String
        let name: String
        let entries: Int
    }

    let total: Int
    let deltaPercent: Int
    let streakDays: Int
    let days: [Day]
    let todayIndex: Int

    var peakIndex: Int? {
        days.indices.max { days[$0].entries < days[$1].entries }
    }
}

struct CaloriesStat {
    struct Macro: Identifiable {
        enum Kind { case protein, carbs, fat }

        let kind: Kind
        let name: String
        let grams: Int
        let goal: Int

        var id: String { name }
        var progress: Double { goal > 0 ? min(Double(grams) / Double(goal), 1) : 0 }
    }

    let eaten: Int
    let goal: Int
    let macros: [Macro]

    var remaining: Int { max(goal - eaten, 0) }
    var progress: Double { goal > 0 ? min(Double(eaten) / Double(goal), 1) : 0 }
}

struct SpendingStat {
    struct Category: Identifiable {
        enum Kind { case food, transport, shopping, other }

        let kind: Kind
        let name: String
        let amount: Int

        var id: String { name }
    }

    let spent: Int
    let budget: Int
    let deltaPercent: Int
    let currencyCode: String
    let categories: [Category]
}

struct GymStat {
    struct Day: Identifiable {
        enum Status { case done, planned, rest }

        let id: Int
        let label: String
        let name: String
        let status: Status
    }

    let visits: Int
    let goal: Int
    let days: [Day]
    let lastSession: String
}

/// Placeholder data matching the Home mockup, until the endpoints are wired in.
enum HomeSample {
    private static let letters = ["M", "T", "W", "T", "F", "S", "S"]
    private static let names = ["Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"]

    static let userName = "Bharat"

    static let journal = JournalStat(
        total: 37,
        deltaPercent: 20,
        streakDays: 12,
        days: [4, 6, 9, 5, 7, 4, 2].enumerated().map { index, entries in
            .init(id: index, label: letters[index], name: names[index], entries: entries)
        },
        todayIndex: 6
    )

    static let calories = CaloriesStat(
        eaten: 1640,
        goal: 2200,
        macros: [
            .init(kind: .protein, name: "Protein", grams: 92, goal: 120),
            .init(kind: .carbs, name: "Carbs", grams: 180, goal: 250),
            .init(kind: .fat, name: "Fat", grams: 48, goal: 70),
        ]
    )

    static let spending = SpendingStat(
        spent: 18420,
        budget: 30000,
        deltaPercent: -12,
        currencyCode: "JPY",
        categories: [
            .init(kind: .food, name: "Food", amount: 8470),
            .init(kind: .transport, name: "Transport", amount: 4050),
            .init(kind: .shopping, name: "Shopping", amount: 3320),
            .init(kind: .other, name: "Other", amount: 2580),
        ]
    )

    static let gym = GymStat(
        visits: 4,
        goal: 5,
        days: [GymStat.Day.Status.done, .done, .rest, .done, .rest, .done, .planned].enumerated().map { index, status in
            .init(id: index, label: letters[index], name: names[index], status: status)
        },
        lastSession: "Push day · 70 min · Sat"
    )

    static let trackerIdeas = ["Track my sleep", "Count my steps", "Water intake"]
}
