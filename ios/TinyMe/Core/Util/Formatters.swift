import Foundation

/// How numbers from the server are shown. One place, so every card agrees.
enum Formatters {
    /// Grouped, with at most one decimal: 1,640 · 7.5
    static func number(_ value: Double) -> String {
        value.formatted(.number.precision(.fractionLength(0...1)))
    }

    /// A value with its unit: ¥18,420 · 1,640 kcal · 92 g · 3 workouts
    static func value(_ value: Double, unit: String?) -> String {
        guard let unit, !unit.isEmpty else { return number(value) }
        if unit == "JPY" {
            return value.formatted(.currency(code: "JPY").precision(.fractionLength(0)))
        }
        return "\(number(value)) \(unit)"
    }

    /// The figure alone for big type; the unit goes in the caption (currency keeps its symbol).
    static func headline(_ value: Double, unit: String?) -> String {
        unit == "JPY" ? self.value(value, unit: unit) : number(value)
    }

    static func percent(_ value: Double) -> String {
        "\(Int(value.magnitude.rounded()))%"
    }
}
