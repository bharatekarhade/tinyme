import SwiftUI

/// Draws one block of a card.
struct BlockView: View {
    let block: BlockDTO
    let range: HomeRange

    var body: some View {
        switch block {
        case .stat(let stat):
            StatBlockView(label: stat.label, unit: stat.unit, value: stat.value, goal: stat.goal, entries: stat.entries)
        case .progress(let progress):
            if progress.goal == nil {
                StatBlockView(label: progress.label, unit: progress.unit, value: progress.value, goal: nil, entries: progress.entries)
            } else {
                ProgressBlockView(block: progress)
            }
        case .chart(let chart):
            ChartBlockView(block: chart, range: range)
        case .latest(let latest):
            LatestBlockView(block: latest)
        case .goals, .checkins, .breakdown:
            BlockNote("This part isn't shown in the app yet")
        case .unavailable:
            BlockNote("This part couldn't load")
        case .unknown:
            BlockNote("Update TinyMe to see this")
        }
    }
}

/// A thin, quiet line standing in for a block that can't be drawn.
struct BlockNote: View {
    let text: String
    @Environment(\.cardStyle) private var style

    init(_ text: String) {
        self.text = text
    }

    var body: some View {
        Text(text)
            .textStyle(.caption)
            .foregroundStyle(style.muted)
    }
}

// MARK: - Stat

/// A big number with its label: "15 entries", "¥18,420 / ¥30,000".
struct StatBlockView: View {
    let label: String
    let unit: String?
    let value: Double?
    let goal: Double?
    let entries: Int

    @Environment(\.cardStyle) private var style
    @State private var shown = false

    private var shownValue: Double { shown ? (value ?? 0) : 0 }

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            HStack(alignment: .firstTextBaseline, spacing: 6) {
                Text(Formatters.headline(shownValue, unit: unit))
                    .textStyle(style.isHero ? .numeral : .display)
                    .contentTransition(.numericText(value: shownValue))
                if let goal {
                    Text("/ \(Formatters.headline(goal, unit: unit))")
                        .textStyle(.heading, weight: .medium)
                        .foregroundStyle(style.muted)
                }
            }
            Text(caption)
                .textStyle(.label)
                .foregroundStyle(isOver ? Palette.accent : style.muted)
        }
        .growIn(shown)
        .revealOnScroll($shown)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("\(Formatters.value(value ?? 0, unit: unit)), \(caption)")
    }

    private var isOver: Bool {
        guard let goal, let value else { return false }
        return value > goal
    }

    private var caption: String {
        guard entries > 0 else { return "No entries yet" }
        var parts = [label]
        if let unit, !unit.isEmpty, unit != "JPY", unit != label {
            parts.insert(unit, at: 0)
        }
        // A stat's goal is a limit (calories, budget): say what's left, or how far over.
        if let goal, let value {
            let left = goal - value
            parts.append(left >= 0 ? "\(Formatters.headline(left, unit: unit)) left" : "\(Formatters.headline(-left, unit: unit)) over")
        }
        return parts.joined(separator: " · ")
    }
}

// MARK: - Progress

/// A value against a goal, as a ring, a half-circle gauge or a bar.
struct ProgressBlockView: View {
    let block: ProgressBlock

    @Environment(\.cardStyle) private var style
    @State private var shown = false

    private var value: Double { block.value ?? 0 }
    private var goal: Double { block.goal ?? 0 }
    private var fraction: Double { goal > 0 ? min(max(value / goal, 0), 1) : 0 }
    private var isOver: Bool { value > goal }
    private var fill: Color { isOver ? Palette.accent : style.accent }
    private var shownFraction: Double { shown ? fraction : 0 }

    var body: some View {
        Group {
            switch block.style {
            case "gauge": gauge
            case "bar": bar
            default: ring
            }
        }
        .growIn(shown)
        .revealOnScroll($shown)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("\(block.label): \(Formatters.number(value)) \(goalLine), \(remainingText)")
    }

    private var ring: some View {
        HStack(spacing: Space.s4) {
            ZStack {
                Circle()
                    .stroke(style.track, lineWidth: 10)
                Circle()
                    .trim(from: 0, to: shownFraction)
                    .stroke(fill, style: StrokeStyle(lineWidth: 10, lineCap: .round))
                    .rotationEffect(.degrees(-90))
                Text(Formatters.percent(block.pct ?? fraction * 100))
                    .textStyle(.label, weight: .semibold)
            }
            .frame(width: 76, height: 76)
            .padding(5)
            summary(alignment: .leading)
            Spacer(minLength: 0)
        }
    }

    private var gauge: some View {
        let width: CGFloat = 22
        let stroke = StrokeStyle(lineWidth: width, lineCap: .round)
        return ZStack(alignment: .bottom) {
            Hatch(color: style.tile, stripe: 2.5, gap: 3.5)
                .mask(HalfArc(lineWidth: width).stroke(style: stroke))
            HalfArc(lineWidth: width)
                .trim(from: 0, to: shownFraction)
                .stroke(fill, style: stroke)
                .opacity(shown ? 1 : 0)
            summary(alignment: .center)
        }
        .frame(width: 240, height: 128)
        .frame(maxWidth: .infinity)
    }

    private var bar: some View {
        VStack(alignment: .leading, spacing: Space.s2) {
            summary(alignment: .leading)
            Capsule()
                .fill(style.track)
                .frame(height: 8)
                .overlay(alignment: .leading) {
                    GeometryReader { geometry in
                        Capsule()
                            .fill(fill)
                            .frame(width: geometry.size.width * shownFraction)
                    }
                }
        }
    }

    private func summary(alignment: HorizontalAlignment) -> some View {
        VStack(alignment: alignment, spacing: 0) {
            Text(Formatters.headline(shown ? value : 0, unit: block.unit))
                .textStyle(.display)
                .contentTransition(.numericText(value: shown ? value : 0))
            Text("\(goalLine) · \(Text(remainingText).foregroundStyle(isOver ? Palette.accent : style.muted))")
                .textStyle(.caption)
                .foregroundStyle(style.muted)
            Text(block.label)
                .textStyle(.caption)
                .foregroundStyle(style.muted)
        }
    }

    private var goalLine: String {
        "of \(Formatters.value(goal, unit: block.unit))"
    }

    private var remainingText: String {
        let left = goal - value
        return left >= 0 ? "\(Formatters.headline(left, unit: block.unit)) left" : "\(Formatters.headline(-left, unit: block.unit)) over"
    }
}

// MARK: - Chart

/// Pill bars, one per period; tap a bar to read its value.
/// On the Day range there is nothing to compare, so it shows today's count as a row of marks instead.
struct ChartBlockView: View {
    let block: ChartBlock
    let range: HomeRange

    @Environment(\.cardStyle) private var style
    @State private var shown = false
    @State private var selected: Int?

    private var maxValue: Double { max(block.points.compactMap(\.value).max() ?? 0, 0) }
    private var isEmpty: Bool { maxValue == 0 }
    private var today: ChartBlock.Point? { block.points.first { $0.state == "current" } }

    private var spacing: CGFloat {
        switch block.points.count {
        case ...7: 8
        case ...12: 6
        default: 2
        }
    }

    var body: some View {
        Group {
            if range == .day, let today {
                TodayMarks(label: block.label, value: today.value ?? 0, best: bestOtherDay, shown: shown)
            } else {
                bars
            }
        }
        .revealOnScroll($shown)
    }

    // MARK: Bars

    private var bars: some View {
        VStack(alignment: .leading, spacing: Space.s2) {
            Text(isEmpty ? "\(block.label) · No entries yet" : block.label)
                .textStyle(.caption)
                .foregroundStyle(style.muted)
            GeometryReader { geometry in
                HStack(alignment: .bottom, spacing: spacing) {
                    ForEach(Array(block.points.enumerated()), id: \.offset) { index, point in
                        bar(index: index, point: point)
                    }
                }
                .contentShape(Rectangle())
                .gesture(
                    SpatialTapGesture().onEnded { tap in
                        select(at: tap.location.x, width: geometry.size.width)
                    }
                )
            }
            .frame(height: 124)
        }
        .sensoryFeedback(.selection, trigger: selected)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(summary)
    }

    private func bar(index: Int, point: ChartBlock.Point) -> some View {
        let value = point.value ?? 0
        let isCurrent = point.state == "current"
        let isFuture = point.state == "future"
        let isSelected = index == selected
        let isPeak = index == block.peakIndex && value > 0
        // With nothing selected, the peak and today carry their value; a selection takes over.
        let isHighlighted = selected == nil ? isPeak : isSelected
        let fullHeight = maxValue > 0 && !isFuture ? 10 + 78 * value / maxValue : 10
        let label = xLabel(index: index, day: point.period)

        return VStack(spacing: 6) {
            Spacer(minLength: 0)
            if let text = valueLabel(index: index, point: point) {
                Text(text)
                    .textStyle(.caption, weight: .semibold)
                    .foregroundStyle(style.pillForeground)
                    .fixedSize()
                    .padding(.horizontal, 7)
                    .padding(.vertical, 1)
                    .background(style.pillBackground, in: Capsule())
                    .frame(width: 1)   // may be wider than its bar without pushing the others aside
                    .opacity(shown ? 1 : 0)
                    .transition(.opacity.combined(with: .scale(scale: 0.8, anchor: .bottom)))
                    .zIndex(1)
            }
            Group {
                if isFuture {
                    Capsule().strokeBorder(style.accent.opacity(0.3), lineWidth: 1)
                } else if isCurrent && !isSelected {
                    Hatch(color: style.accent.opacity(0.4))
                        .clipShape(Capsule())
                        .overlay(Capsule().strokeBorder(style.accent.opacity(0.4), lineWidth: 1.5))
                } else {
                    Capsule().fill(style.accent.opacity(isHighlighted ? 1 : 0.22))
                }
            }
            .frame(maxWidth: 34)
            .frame(height: shown ? fullHeight : 10)
            Text(label.isEmpty ? " " : label)
                .textStyle(.caption, weight: .semibold)
                .fixedSize()
                .frame(width: 1)   // labels may be wider than a thin bar
        }
        .frame(maxWidth: .infinity)
        .growIn(shown, delay: min(Double(index) * 0.05, 0.6))
    }

    private func valueLabel(index: Int, point: ChartBlock.Point) -> String? {
        let value = point.value ?? 0
        let isCurrent = point.state == "current"
        let number = Formatters.number(value)
        if let selected {
            guard index == selected else { return nil }
            let text = isCurrent ? "\(number) so far" : number
            // Thin bars have sparse axis labels, so say which period this is.
            return block.points.count > 12 ? "\(Self.shortDay(point.period)) · \(text)" : text
        }
        guard block.points.count <= 12, point.state != "future", value > 0 else { return nil }
        if isCurrent { return "\(number) so far" }
        return index == block.peakIndex ? number : nil
    }

    private func select(at x: CGFloat, width: CGFloat) {
        guard width > 0, !block.points.isEmpty else { return }
        let index = min(max(Int(x / width * CGFloat(block.points.count)), 0), block.points.count - 1)
        withAnimation(.snappy(duration: 0.2)) {
            // Tapping the selected bar again, or a day that hasn't happened, clears the selection.
            selected = index == selected || block.points[index].state == "future" ? nil : index
        }
    }

    /// Day and week: weekday letter. Month: the day number every 7th day. Year: month letter.
    private func xLabel(index: Int, day: LocalDay) -> String {
        let calendar = Calendar.current
        switch range {
        case .day, .week:
            guard let date = day.date(in: calendar) else { return "" }
            return calendar.veryShortWeekdaySymbols[calendar.component(.weekday, from: date) - 1]
        case .month:
            return index % 7 == 0 ? "\(day.day)" : ""
        case .year:
            return calendar.veryShortMonthSymbols[day.month - 1]
        }
    }

    private static func shortDay(_ day: LocalDay) -> String {
        day.date()?.formatted(.dateTime.day().month(.abbreviated)) ?? day.description
    }

    /// The highest earlier day in the same data, for "Best this week".
    private var bestOtherDay: (value: Double, name: String)? {
        let earlier = block.points.filter { $0.state == "past" && ($0.value ?? 0) > 0 }
        guard let best = earlier.max(by: { ($0.value ?? 0) < ($1.value ?? 0) }), let date = best.period.date() else {
            return nil
        }
        return (best.value ?? 0, date.formatted(.dateTime.weekday(.wide)))
    }

    private var summary: String {
        let total = block.points.compactMap(\.value).reduce(0, +)
        return "\(block.label): \(Formatters.number(total)) in total over \(block.points.count) periods"
    }
}

/// Today so far: one mark per entry, then a striped slot still waiting, because the day isn't over.
private struct TodayMarks: View {
    let label: String
    let value: Double
    let best: (value: Double, name: String)?
    let shown: Bool

    @Environment(\.cardStyle) private var style

    private let maxMarks = 10
    /// Marks only make sense for small whole counts (entries, visits), not for minutes or yen.
    private var usesMarks: Bool { value == value.rounded() && value >= 0 && value <= Double(maxMarks) }

    var body: some View {
        VStack(alignment: .leading, spacing: Space.s3) {
            HStack(alignment: .firstTextBaseline, spacing: Space.s2) {
                Text("\(Formatters.number(value)) so far")
                    .textStyle(.caption, weight: .semibold)
                    .foregroundStyle(style.pillForeground)
                    .padding(.horizontal, 10)
                    .frame(height: 24)
                    .background(style.pillBackground, in: Capsule())
                Text("today")
                    .textStyle(.caption)
                    .foregroundStyle(style.muted)
            }

            if usesMarks {
                HStack(spacing: 6) {
                    ForEach(0..<Int(value), id: \.self) { index in
                        Capsule()
                            .fill(style.accent)
                            .frame(width: 18, height: shown ? 44 : 10)
                            .growIn(shown, delay: Double(index) * 0.07)
                    }
                    openSlot
                        .frame(width: 18, height: shown ? 44 : 10)
                        .growIn(shown, delay: value * 0.07)
                    Spacer(minLength: 0)
                }
                .frame(height: 44, alignment: .bottom)
            } else {
                // A larger or fractional total: fill towards the best day instead of counting marks.
                let target = max(best?.value ?? value, value, 1)
                openSlot
                    .frame(height: 14)
                    .overlay(alignment: .leading) {
                        GeometryReader { geometry in
                            Capsule()
                                .fill(style.accent)
                                .frame(width: geometry.size.width * (shown ? value / target : 0))
                        }
                    }
                    .growIn(shown)
            }

            Text(footnote)
                .textStyle(.caption)
                .foregroundStyle(style.muted)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("\(label): \(Formatters.number(value)) so far today. \(footnote)")
    }

    /// The slot that's still open: striped, like today's bar in the week chart.
    private var openSlot: some View {
        Hatch(color: style.accent.opacity(0.4))
            .clipShape(Capsule())
            .overlay(Capsule().strokeBorder(style.accent.opacity(0.4), lineWidth: 1.5))
    }

    private var footnote: String {
        guard let best else {
            return value > 0 ? "Your best day this week so far" : "Nothing yet today"
        }
        let bestText = Formatters.number(best.value)
        if value > best.value { return "Your best day this week · beats \(best.name)'s \(bestText)" }
        if value == best.value { return "Level with \(best.name), your best day this week" }
        return "Best this week: \(bestText) on \(best.name)"
    }
}

// MARK: - Latest

/// The most recent entry on one line: "Recent workouts · Run · Thu".
struct LatestBlockView: View {
    let block: LatestBlock
    @Environment(\.cardStyle) private var style

    var body: some View {
        HStack(alignment: .firstTextBaseline) {
            Text(block.label)
                .foregroundStyle(style.muted)
            Spacer(minLength: Space.s2)
            Text(valueText)
                .fontWeight(.semibold)
                .multilineTextAlignment(.trailing)
        }
        .textStyle(.label)
        .padding(.top, Space.s3)
        .overlay(alignment: .top) {
            Rectangle()
                .fill(style.track)
                .frame(height: 1)
        }
        .accessibilityElement(children: .combine)
    }

    private var valueText: String {
        guard let entry = block.entry else { return "Nothing logged yet" }
        // JSON objects have no order, so sort the keys to keep the line stable between loads.
        var parts = entry.data.keys.sorted().compactMap { Self.text(for: entry.data[$0]) }
        if let text = entry.text, !text.isEmpty {
            parts.append(text)
        }
        parts.append(Self.dayText(entry.localDay))
        let line = parts.joined(separator: " · ")
        return line.prefix(1).uppercased() + line.dropFirst()
    }

    private static func text(for value: JSONValue?) -> String? {
        switch value {
        case .string(let string): string.isEmpty ? nil : string
        case .number(let number): Formatters.number(number)
        case .bool(let bool): bool ? "yes" : "no"
        default: nil
        }
    }

    /// "Sat" within the last week, otherwise "12 Oct".
    private static func dayText(_ day: LocalDay) -> String {
        let calendar = Calendar.current
        guard let date = day.date(in: calendar) else { return day.description }
        let daysAgo = calendar.dateComponents([.day], from: calendar.startOfDay(for: date), to: calendar.startOfDay(for: .now)).day ?? 0
        if (0..<7).contains(daysAgo) {
            return date.formatted(.dateTime.weekday(.abbreviated))
        }
        return date.formatted(.dateTime.day().month(.abbreviated))
    }
}
