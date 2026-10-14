import Foundation

/// How one entry reads on screen. Worked out in one place so the list row and the detail sheet always agree.
struct EntryPresentation {
    struct Field: Identifiable {
        let id: String
        let label: String
        let value: String
    }

    /// SF Symbol for the kind, or nil for a kind without one (drawn as a dot).
    let symbol: String?
    let isHealthEntry: Bool
    let kindLabel: String
    let title: String
    /// Up to three short facts: "¥980", "food".
    let chips: [String]
    /// The entry's text on one line, when the title didn't already come from it.
    let subtitle: String?
    /// Journal entries show their words, quoted, in place of a title and chips.
    let quote: String?
    /// The headline number for the detail sheet, if the entry has one.
    let primaryValue: String?
    /// "12:40" in the phone's time zone.
    let time: String
    /// "Sun 11 Oct"
    let day: String
    /// Every data field, for the detail sheet.
    let fields: [Field]
    let changeDraft: String
    let deleteDraft: String

    init(_ entry: EntryDTO, calendar: Calendar = .current) {
        let text = entry.text?.trimmingCharacters(in: .whitespacesAndNewlines).nonEmpty
        let firstLine = text?.split(separator: "\n").first.map(String.init)

        isHealthEntry = entry.source == "health"
        symbol = isHealthEntry ? "heart.text.square.fill" : Self.symbol(for: entry.kind)
        kindLabel = Self.humanized(entry.kind)

        var titleFromText = false
        var baseTitle: String
        if let merchant = entry.data["merchant"]?.stringValue?.nonEmpty {
            baseTitle = merchant
        } else if let type = entry.data["type"]?.stringValue?.nonEmpty {
            baseTitle = type.capitalizedFirst
        } else if let firstLine {
            baseTitle = firstLine
            titleFromText = true
        } else {
            baseTitle = Self.humanized(entry.kind)
        }
        if entry.quantity > 1 {
            baseTitle = "\(Formatters.number(entry.quantity)) × \(baseTitle)"
        }
        title = baseTitle

        if entry.kind == "journal", let text {
            quote = "“\(text)”"
            chips = []
            subtitle = nil
        } else {
            quote = nil
            chips = Array(Self.chips(for: entry.data).prefix(3))
            subtitle = titleFromText ? nil : firstLine
        }

        primaryValue = ["amount", "kcal", "minutes", "hours"].lazy
            .compactMap { key in entry.data[key]?.numberValue.map { Self.value($0, key: key) } }
            .first

        time = entry.ts.formatted(date: .omitted, time: .shortened)
        let date = entry.localDay.date(in: calendar)
        day = date?.formatted(.dateTime.weekday(.abbreviated).day().month(.abbreviated)) ?? entry.localDay.description
        fields = Self.fields(for: entry.data)

        let what = (text?.split(separator: "\n").first.map(String.init) ?? title).lowercasedFirst
        changeDraft = "Change my \(time) \(what) (\(day)): "
        deleteDraft = "Delete my \(time) \(what) (\(day))"
    }

    // MARK: - Rules

    private static func symbol(for kind: String) -> String? {
        switch kind {
        case "drink": "cup.and.saucer"
        case "meal": "fork.knife"
        case "expense": "creditcard"
        case "workout": "dumbbell"
        case "sleep": "bed.double"
        case "journal": "text.book.closed"
        case "mood": "face.smiling"
        default: nil
        }
    }

    /// Known fields only, in a fixed order. Anything else waits for the detail sheet.
    private static func chips(for data: [String: JSONValue]) -> [String] {
        var chips: [String] = []
        for key in ["amount", "kcal", "minutes", "hours", "km", "steps"] {
            guard let number = data[key]?.numberValue else { continue }
            var chip = value(number, key: key)
            if key == "kcal", data["kcal_estimated"]?.boolValue == true {
                chip += " est."
            }
            chips.append(chip)
        }
        if let category = data["category"]?.stringValue?.nonEmpty {
            chips.append(category)
        }
        return chips
    }

    private static let units: [String: String] = [
        "amount": "JPY", "kcal": "kcal", "minutes": "min", "hours": "h", "km": "km", "steps": "steps",
    ]

    private static let labels: [String: String] = [
        "amount": "Amount", "kcal": "Calories", "kcal_estimated": "Estimated", "protein_g": "Protein",
        "carbs_g": "Carbs", "fat_g": "Fat", "minutes": "Duration", "hours": "Hours", "km": "Distance",
        "steps": "Steps", "category": "Category", "merchant": "Place", "type": "Type",
    ]

    private static let fieldOrder = [
        "merchant", "type", "category", "amount", "kcal", "kcal_estimated", "protein_g", "carbs_g", "fat_g",
        "minutes", "hours", "km", "steps",
    ]

    private static func value(_ number: Double, key: String) -> String {
        if let unit = units[key] { return Formatters.value(number, unit: unit) }
        if key.hasSuffix("_g") { return Formatters.value(number, unit: "g") }
        return Formatters.number(number)
    }

    private static func fields(for data: [String: JSONValue]) -> [Field] {
        let known = fieldOrder.filter { data[$0] != nil }
        let others = data.keys.filter { !fieldOrder.contains($0) }.sorted()
        return (known + others).compactMap { key in
            let text: String? = switch data[key] {
            case .string(let string): string.nonEmpty
            case .number(let number): value(number, key: key)
            case .bool(let bool): bool ? "Yes" : "No"
            case .array(let items): items.compactMap(\.stringValue).joined(separator: ", ").nonEmpty
            default: nil
            }
            guard let text else { return nil }
            let label = labels[key] ?? humanized(key.hasSuffix("_g") ? String(key.dropLast(2)) : key)
            return Field(id: key, label: label, value: text)
        }
    }

    /// "some_key" → "Some key"
    static func humanized(_ key: String) -> String {
        key.replacingOccurrences(of: "_", with: " ").capitalizedFirst
    }
}

extension String {
    var nonEmpty: String? { isEmpty ? nil : self }
    var capitalizedFirst: String { prefix(1).uppercased() + dropFirst() }
    var lowercasedFirst: String { prefix(1).lowercased() + dropFirst() }
}
