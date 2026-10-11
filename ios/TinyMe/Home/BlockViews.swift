import SwiftUI

/// Draws one block of a card.
struct BlockView: View {
    let block: BlockDTO
    let range: HomeRange
    var period: StatPeriod
    /// False when the card's badge already shows the change against the previous period.
    var showsChange = true
    /// The comparison with your usual day, on the one block of the card that shows it.
    var average: AverageDay?
    /// Called when a part of the block is tapped. Nil: nothing is tappable.
    var onTap: ((BlockTap) -> Void)?

    var body: some View {
        switch block {
        case .stat(let stat):
            tappable {
                StatBlockView(
                    label: stat.label, unit: stat.unit, value: stat.value, goal: stat.goal, entries: stat.entries,
                    period: period, previous: showsChange ? stat.previous : nil, deltaPct: showsChange ? stat.deltaPct : nil,
                    average: average
                )
            }
        case .progress(let progress):
            if progress.goal == nil {
                tappable {
                    StatBlockView(label: progress.label, unit: progress.unit, value: progress.value, goal: nil, entries: progress.entries, period: period, average: average)
                }
            } else {
                tappable {
                    withAverage(alignment: progress.style == "gauge" ? .center : .leading) {
                        ProgressBlockView(block: progress)
                    }
                }
            }
        case .chart(let chart):
            withAverage {
                ChartBlockView(block: chart, range: range, onOpenDay: onTap.map { tap in { tap(.point($0)) } })
            }
        case .latest(let latest):
            tappable { LatestBlockView(block: latest) }
        case .goals(let goals):
            GoalsBlockView(block: goals, onItem: onTap.map { tap in { tap(.item($0)) } })
        case .checkins(let checkins):
            withAverage {
                CheckinsBlockView(block: checkins, range: range, onSlot: onTap.map { tap in { tap(.slot($0)) } })
            }
        case .breakdown(let breakdown):
            withAverage {
                BreakdownBlockView(block: breakdown, onSlice: onTap.map { tap in { tap(.slice($0)) } })
            }
        case .unavailable:
            BlockNote("This part couldn't load")
        case .unknown:
            BlockNote("Update TinyMe to see this")
        }
    }
}

/// The part of a block that was tapped.
enum BlockTap {
    case block
    case slice(String)
    case point(LocalDay)
    case slot(LocalDay)
    case item(Int)

    func cardTap(block index: Int) -> CardTap {
        switch self {
        case .block: CardTap(block: index)
        case .slice(let key): CardTap(block: index, key: key)
        case .point(let day), .slot(let day): CardTap(block: index, day: day)
        case .item(let item): CardTap(block: index, item: item)
        }
    }
}

extension BlockView {
    /// Makes the whole block open its entries, when taps are wanted.
    @ViewBuilder
    fileprivate func tappable(@ViewBuilder content: () -> some View) -> some View {
        if let onTap {
            Button {
                onTap(.block)
            } label: {
                content()
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
        } else {
            content()
        }
    }

    /// Adds the "vs your usual day" pill under a block, when this is the block that shows it.
    @ViewBuilder
    fileprivate func withAverage(alignment: HorizontalAlignment = .leading, @ViewBuilder content: () -> some View) -> some View {
        if let text = average?.text {
            VStack(alignment: alignment, spacing: Space.s2) {
                content()
                ChangePill(text: text)
            }
            .frame(maxWidth: .infinity, alignment: alignment == .center ? .center : .leading)
        } else {
            content()
        }
    }
}

extension AverageDay {
    /// "↑ 15% vs your usual day": the usual day is the average of the 30 days before this period.
    var text: String? {
        deltaPct.map { Formatters.change($0, versus: "your usual day") }
    }
}

extension StatBlock {
    /// "↓ 12% vs last month". The server sends no percentage when the earlier period was zero.
    func changeText(_ period: StatPeriod) -> String? {
        StatPeriod.changeText(deltaPct: deltaPct, previous: previous, value: value, period: period)
    }
}

extension StatPeriod {
    static func changeText(deltaPct: Double?, previous: Double?, value: Double?, period: StatPeriod) -> String? {
        if let deltaPct {
            return Formatters.change(deltaPct, versus: period.previousPhrase)
        }
        if let previous, previous == 0, let value, value > 0 {
            return "↑ from 0 \(period.previousPhrase)"
        }
        return nil
    }
}

/// A small, neutral pill for a comparison: "↓ 12% vs last month".
struct ChangePill: View {
    let text: String
    @Environment(\.cardStyle) private var style

    var body: some View {
        Text(text)
            .textStyle(.caption, weight: .semibold)
            .foregroundStyle(style.foreground)
            .padding(.horizontal, Space.s2)
            .frame(height: 22)
            .background(style.track, in: Capsule())
            .fixedSize()
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
    let period: StatPeriod
    var previous: Double?
    var deltaPct: Double?
    var average: AverageDay?

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
            if !comparisons.isEmpty {
                FlowLayout(spacing: 6) {
                    ForEach(comparisons, id: \.self) { text in
                        ChangePill(text: text)
                    }
                }
                .padding(.top, 6)
            }
        }
        .growIn(shown)
        .revealOnScroll($shown)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(([Formatters.value(value ?? 0, unit: unit), caption] + comparisons).joined(separator: ", "))
    }

    private var isOver: Bool {
        guard let goal, let value else { return false }
        return value > goal
    }

    /// Against the previous period, and against your usual day.
    private var comparisons: [String] {
        [change, average?.text].compactMap { $0 }
    }

    /// "↓ 12% vs last month". The server sends no percentage when the earlier period was zero.
    private var change: String? {
        StatPeriod.changeText(deltaPct: deltaPct, previous: previous, value: value, period: period)
    }

    private var caption: String {
        guard entries > 0 else { return "No entries \(period.phrase)" }
        // Say which period the number covers.
        var parts = ["\(label) \(period.phrase)"]
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

/// A period a number covers, and the one it is compared with.
enum StatPeriod {
    case today, week, month, year

    init(range: HomeRange) {
        switch range {
        case .day: self = .today
        case .week: self = .week
        case .month: self = .month
        case .year: self = .year
        }
    }

    /// From a period badge: "today", "this_week", …
    init?(badge period: String?) {
        switch period {
        case "today": self = .today
        case "this_week": self = .week
        case "this_month": self = .month
        case "this_year": self = .year
        default: return nil
        }
    }

    var phrase: String {
        switch self {
        case .today: "today"
        case .week: "this week"
        case .month: "this month"
        case .year: "this year"
        }
    }

    var previousPhrase: String {
        switch self {
        case .today: "yesterday"
        case .week: "last week"
        case .month: "last month"
        case .year: "last year"
        }
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
    /// Opens the entries for one bar. The first tap on a bar shows its value; a second tap on it calls this.
    var onOpenDay: ((LocalDay) -> Void)?

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
            if isEmpty {
                Text("No entries yet")
                    .textStyle(.caption)
                    .foregroundStyle(style.muted)
            }
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
        let isCrowded = block.points.count > 12
        let number = isCurrent ? "\(Formatters.number(value)) so far" : Formatters.number(value)
        // Thin bars have sparse axis labels, so say which period this is.
        let text = isCrowded ? "\(Self.shortDay(point.period)) · \(number)" : number
        if let selected {
            // The arrow says a second tap opens that bar's entries.
            return index == selected ? (onOpenDay == nil ? text : "\(text) ›") : nil
        }
        // Nothing selected: the highlighted (peak) bar always carries its number,
        // and so does today when there is room for a second label.
        guard point.state != "future", value > 0 else { return nil }
        if index == block.peakIndex { return text }
        return isCurrent && !isCrowded ? text : nil
    }

    private func select(at x: CGFloat, width: CGFloat) {
        guard width > 0, !block.points.isEmpty else { return }
        let index = min(max(Int(x / width * CGFloat(block.points.count)), 0), block.points.count - 1)
        if index == selected, let onOpenDay {
            onOpenDay(block.points[index].period)
            return
        }
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
        // Words first, then numbers: "Push day · 70 min".
        let keys = entry.data.keys.sorted()
        var parts = keys.compactMap { key in entry.data[key]?.stringValue.flatMap { $0.isEmpty ? nil : $0 } }
        parts += keys.compactMap { key in Self.number(entry.data[key], key: key) }
        if let text = entry.text, !text.isEmpty {
            parts.append(text)
        }
        parts.append(Self.dayText(entry.localDay))
        let line = parts.joined(separator: " · ")
        return line.prefix(1).uppercased() + line.dropFirst()
    }

    /// A numeric field with the unit its name implies: minutes → "70 min", protein_g → "32 g".
    private static func number(_ value: JSONValue?, key: String) -> String? {
        guard case .number(let number) = value else { return nil }
        let unit: String? = switch key {
        case "minutes": "min"
        case "kcal": "kcal"
        case "amount": "JPY"
        case _ where key.hasSuffix("_g"): "g"
        default: nil
        }
        return Formatters.value(number, unit: unit)
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

// MARK: - Goals

/// Up to four small bars side by side, each a value against its goal: "Protein · 92 / 120 g".
struct GoalsBlockView: View {
    let block: GoalsBlock
    var onItem: ((Int) -> Void)?

    @Environment(\.cardStyle) private var style
    @State private var shown = false

    private var items: [GoalsBlock.Item] { Array(block.items.prefix(4)) }

    var body: some View {
        HStack(alignment: .top, spacing: Space.s3) {
            ForEach(Array(items.enumerated()), id: \.offset) { index, item in
                VStack(alignment: .leading, spacing: 6) {
                    Text(item.label)
                        .textStyle(.caption)
                        .foregroundStyle(style.muted)
                        .lineLimit(1)
                    Capsule()
                        .fill(style.track)
                        .frame(height: 6)
                        .overlay(alignment: .leading) {
                            GeometryReader { geometry in
                                Capsule()
                                    .fill(color(at: index))
                                    .frame(width: geometry.size.width * (shown ? fraction(item) : 0))
                            }
                        }
                    Text(text(for: item))
                        .textStyle(.label, weight: .semibold)
                        .lineLimit(1)
                        .minimumScaleFactor(0.8)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .contentShape(Rectangle())
                .onTapGesture { onItem?(index) }
                .accessibilityElement(children: .ignore)
                .accessibilityLabel("\(item.label): \(text(for: item))")
                .accessibilityAddTraits(onItem == nil ? [] : .isButton)
            }
        }
        .growIn(shown, delay: 0.15)
        .revealOnScroll($shown)
    }

    private func fraction(_ item: GoalsBlock.Item) -> Double {
        if let pct = item.pct { return min(max(pct / 100, 0), 1) }
        guard let value = item.value, let goal = item.goal, goal > 0 else { return 0 }
        return min(max(value / goal, 0), 1)
    }

    private func text(for item: GoalsBlock.Item) -> String {
        let value = item.value.map(Formatters.number) ?? "–"
        guard let goal = item.goal else { return item.value.map { Formatters.value($0, unit: item.unit) } ?? "–" }
        return "\(value) / \(Formatters.value(goal, unit: item.unit))"
    }

    /// Each bar gets its own colour so three goals can be told apart at a glance.
    private func color(at index: Int) -> Color {
        [style.foreground, Palette.accent, Palette.sunny, Palette.brand][index % 4]
    }
}

// MARK: - Check-ins

/// A dot per day: ticked when done, faint when missed, outlined when still to come.
struct CheckinsBlockView: View {
    let block: CheckinsBlock
    let range: HomeRange
    var onSlot: ((LocalDay) -> Void)?

    @Environment(\.cardStyle) private var style
    @State private var shown = false

    /// A week fits in one row with letters underneath; longer ranges become a grid with the label inside each dot.
    private var isRow: Bool { block.slots.count <= 7 }

    var body: some View {
        VStack(alignment: .leading, spacing: Space.s4) {
            VStack(alignment: .leading, spacing: 0) {
                if let goal = block.goal {
                    Text("\(block.doneCount)\(Text(" of \(goal)").foregroundStyle(style.muted))")
                        .textStyle(.display)
                } else {
                    Text("\(block.doneCount)")
                        .textStyle(.display)
                }
                Text(caption)
                    .textStyle(.label)
                    .foregroundStyle(style.muted)
            }

            if isRow {
                HStack(spacing: 6) {
                    ForEach(Array(block.slots.enumerated()), id: \.offset) { index, slot in
                        VStack(spacing: 6) {
                            dot(slot, size: 34, index: index)
                            Text(letter(for: slot.period))
                                .textStyle(.caption, weight: .semibold)
                                .foregroundStyle(style.muted)
                        }
                        .frame(maxWidth: .infinity)
                    }
                }
            } else {
                LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 6), count: min(block.slots.count, 7)), spacing: 6) {
                    ForEach(Array(block.slots.enumerated()), id: \.offset) { index, slot in
                        dot(slot, size: 30, index: index, label: gridLabel(for: slot.period))
                    }
                }
            }
        }
        .revealOnScroll($shown)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("\(block.label): \(block.doneCount)\(block.goal.map { " of \($0)" } ?? "") done. \(caption)")
    }

    @ViewBuilder
    private func dot(_ slot: CheckinsBlock.Slot, size: CGFloat, index: Int, label: String? = nil) -> some View {
        Group {
            if slot.done {
                ZStack {
                    Circle().fill(style.accent)
                    if let label {
                        Text(label).textStyle(.caption, weight: .semibold)
                    } else {
                        Image(systemName: "checkmark").font(.system(size: 13, weight: .bold))
                    }
                }
                .foregroundStyle(style.onAccent)
            } else {
                ZStack {
                    switch slot.state {
                    case "future":
                        Circle().strokeBorder(style.accent.opacity(0.6), style: StrokeStyle(lineWidth: 1.5, dash: [4, 3]))
                    case "current":
                        Circle().strokeBorder(style.accent, lineWidth: 1.5)
                    default:
                        Circle().fill(style.track)
                    }
                    if let label {
                        Text(label).textStyle(.caption).foregroundStyle(style.muted)
                    }
                }
            }
        }
        .frame(width: size, height: size)
        .scaleEffect(shown ? 1 : 0.3)
        .opacity(shown ? 1 : 0)
        .growIn(shown, delay: min(Double(index) * 0.04, 0.6))
        .contentShape(Circle())
        .onTapGesture {
            if slot.state != "future" {
                onSlot?(slot.period)
            }
        }
    }

    private var caption: String {
        guard let goal = block.goal else { return block.label }
        let left = goal - block.doneCount
        return left > 0 ? "\(block.label) · \(left) more to hit your goal" : "\(block.label) · goal reached"
    }

    private func letter(for day: LocalDay) -> String {
        let calendar = Calendar.current
        guard let date = day.date(in: calendar) else { return "" }
        return calendar.veryShortWeekdaySymbols[calendar.component(.weekday, from: date) - 1]
    }

    private func gridLabel(for day: LocalDay) -> String {
        range == .year ? Calendar.current.veryShortMonthSymbols[day.month - 1] : "\(day.day)"
    }
}

// MARK: - Breakdown

/// How a total splits by category: one segmented bar (or a bar per category) with a legend.
struct BreakdownBlockView: View {
    let block: BreakdownBlock
    /// Opens the entries of one category. "Other" and unlabeled parts can't be opened yet.
    var onSlice: ((String) -> Void)?

    @Environment(\.cardStyle) private var style
    @State private var shown = false

    private struct Part: Identifiable {
        let id: Int
        let name: String
        /// The category as the server knows it; nil for "Unlabeled" and "Other".
        let key: String?
        let value: Double?
        let pct: Double
        /// "Other" is everything too small to name, drawn hatched.
        let isOther: Bool
    }

    private func open(_ part: Part) {
        if let key = part.key {
            onSlice?(key)
        }
    }

    private var parts: [Part] {
        var parts = block.slices.enumerated().map { index, slice in
            Part(id: index, name: Self.name(slice.key), key: slice.key?.nonEmpty, value: slice.value, pct: max(slice.pct ?? 0, 0), isOther: false)
        }
        if let other = block.other, (other.pct ?? 0) > 0 || (other.value ?? 0) > 0 {
            parts.append(Part(id: parts.count, name: "Other", key: nil, value: other.value, pct: max(other.pct ?? 0, 0), isOther: true))
        }
        return parts
    }

    private var isEmpty: Bool { (block.total ?? 0) == 0 || parts.isEmpty }

    var body: some View {
        Group {
            if isEmpty {
                BlockNote("\(block.label) · No entries yet")
            } else if block.style == "bars" {
                bars
            } else {
                segmented
            }
        }
        .growIn(shown)
        .revealOnScroll($shown)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(summary)
    }

    private var segmented: some View {
        VStack(alignment: .leading, spacing: Space.s3) {
            GeometryReader { geometry in
                let total = max(parts.map(\.pct).reduce(0, +), 1)
                let available = geometry.size.width - 4 * CGFloat(max(parts.count - 1, 0))
                HStack(spacing: 4) {
                    ForEach(parts) { part in
                        swatch(part)
                            .frame(width: max(available * part.pct / total, 4))
                            .contentShape(Rectangle())
                            .onTapGesture { open(part) }
                    }
                }
            }
            .frame(height: 14)
            .mask {
                Rectangle().scaleEffect(x: shown ? 1 : 0, anchor: .leading)
            }

            LazyVGrid(columns: [GridItem(.flexible(), spacing: Space.s4), GridItem(.flexible())], spacing: Space.s2) {
                ForEach(parts) { part in
                    HStack(spacing: Space.s2) {
                        swatch(part)
                            .frame(width: 8, height: 8)
                        Text(part.name)
                            .textStyle(.label, weight: .regular)
                            .lineLimit(1)
                        Spacer(minLength: 0)
                        Text(valueText(part))
                            .textStyle(.label, weight: .semibold)
                    }
                    .frame(minHeight: 28)
                    .contentShape(Rectangle())
                    .onTapGesture { open(part) }
                }
            }
        }
    }

    private var bars: some View {
        VStack(alignment: .leading, spacing: Space.s3) {
            ForEach(parts) { part in
                VStack(alignment: .leading, spacing: 4) {
                    HStack {
                        Text(part.name)
                            .textStyle(.label, weight: .regular)
                        Spacer(minLength: Space.s2)
                        Text(valueText(part))
                            .textStyle(.label, weight: .semibold)
                    }
                    Capsule()
                        .fill(style.track)
                        .frame(height: 8)
                        .overlay(alignment: .leading) {
                            GeometryReader { geometry in
                                swatch(part)
                                    .frame(width: geometry.size.width * (shown ? min(part.pct / 100, 1) : 0))
                            }
                        }
                }
                .contentShape(Rectangle())
                .onTapGesture { open(part) }
            }
        }
    }

    @ViewBuilder
    private func swatch(_ part: Part) -> some View {
        if part.isOther {
            Hatch(color: style.muted.opacity(0.35))
                .clipShape(Capsule())
                .overlay(Capsule().strokeBorder(style.muted.opacity(0.35), lineWidth: 1))
        } else {
            Capsule().fill(color(at: part.id))
        }
    }

    /// Categories take the palette's colours in turn; they differ in lightness as well as hue.
    private func color(at index: Int) -> Color {
        let colors = [Palette.accent, Palette.brand, Palette.sunny, style.foreground.opacity(0.7)]
        return colors[index % colors.count].opacity(index < colors.count ? 1 : 0.5)
    }

    private func valueText(_ part: Part) -> String {
        part.value.map { Formatters.value($0, unit: block.unit) } ?? Formatters.percent(part.pct)
    }

    private static func name(_ key: String?) -> String {
        guard let key, !key.isEmpty else { return "Unlabeled" }
        return key.prefix(1).uppercased() + key.dropFirst()
    }

    private var summary: String {
        guard !isEmpty else { return "\(block.label): no entries yet" }
        return "\(block.label): " + parts.map { "\($0.name) \(Formatters.percent($0.pct))" }.joined(separator: ", ")
    }
}
