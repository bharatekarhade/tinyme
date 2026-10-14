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
            StatBlockView(
                label: stat.label, unit: stat.unit, value: stat.value, goal: stat.goal, entries: stat.entries,
                period: period, previous: showsChange ? stat.previous : nil, deltaPct: showsChange ? stat.deltaPct : nil,
                average: average, perDay: stat.averageDay?.currentPerDay,
                averageBasis: stat.averageDay?.averageLabel ?? "day",
                onOpen: onTap.map { tap in { tap(.block) } }
            )
        case .progress(let progress):
            if progress.goal == nil {
                StatBlockView(
                    label: progress.label, unit: progress.unit, value: progress.value, goal: nil, entries: progress.entries,
                    period: period, average: average, perDay: progress.averageDay?.currentPerDay,
                    onOpen: onTap.map { tap in { tap(.block) } }
                )
            } else {
                tappable {
                    withAverage(alignment: progress.style == "gauge" ? .center : .leading) {
                        ProgressBlockView(block: progress, period: period)
                    }
                }
            }
        case .chart(let chart):
            // Day charts are widened to a seven-day view for today. For a historical
            // day selection, that rolling chart is misleading; the stat already shows
            // the value for the selected date.
            if range != .day || period.isCurrent {
                withAverage {
                    ChartBlockView(block: chart, range: range, onOpenDay: onTap.map { tap in { tap(.point($0)) } })
                }
            }
        case .latest(let latest):
            tappable { LatestBlockView(block: latest) }
        case .goals(let goals):
            GoalsBlockView(block: goals, onItem: onTap.map { tap in { tap(.item($0)) } })
        case .checkins(let checkins):
            withAverage {
                CheckinsBlockView(block: checkins, range: range, period: period,
                                  onSlot: onTap.map { tap in { tap(.slot($0)) } })
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
    /// "↑ 15% vs your usual day/night": the prior daily baseline, or the prior logged-night baseline for sleep.
    var text: String? {
        deltaPct.map { Formatters.change($0, versus: "your usual \(averageLabel)") }
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
/// On Week, Month and Year a small switch beside the number swaps the total for the average per day.
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
    /// The total spread over the days of the period so far. Nil for numbers that can't be averaged this way.
    var perDay: Double?
    var averageBasis = "day"
    /// Opens the entries behind the number. Nil: not tappable.
    var onOpen: (() -> Void)?

    @Environment(\.cardStyle) private var style
    @State private var shown = false
    @State private var wantsAverage = false

    /// One day is its own average, and so is a period with nothing in it.
    private var canAverage: Bool { !period.isDay && perDay != nil && entries > 0 }
    private var showsAverage: Bool { wantsAverage && canAverage }
    private var number: Double { showsAverage ? (perDay ?? 0) : (value ?? 0) }
    private var shownValue: Double { shown ? number : 0 }

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            HStack(spacing: Space.s2) {
                Button {
                    onOpen?()
                } label: {
                    HStack(alignment: .firstTextBaseline, spacing: 6) {
                        Text(Formatters.headline(shownValue, unit: unit))
                            .textStyle(style.isHero ? .cardNumeral : .cardDisplay)
                            .contentTransition(.numericText(value: shownValue))
                        if let goal, !showsAverage {
                            Text("/ \(Formatters.headline(goal, unit: unit))")
                                .textStyle(.heading, weight: .medium)
                                .foregroundStyle(style.muted)
                        }
                    }
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                if canAverage {
                    averageSwitch
                }
            }
            Button {
                onOpen?()
            } label: {
                VStack(alignment: .leading, spacing: 2) {
                    Text(caption)
                        .textStyle(.label)
                        .foregroundStyle(isOver && !showsAverage ? Palette.accent : style.muted)
                    if !comparisons.isEmpty {
                        FlowLayout(spacing: 6) {
                            ForEach(comparisons, id: \.self) { text in
                                ChangePill(text: text)
                            }
                        }
                        .padding(.top, 6)
                    }
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
        }
        .growIn(shown)
        .revealOnScroll($shown)
        .sensoryFeedback(.selection, trigger: wantsAverage)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(([Formatters.value(number, unit: unit), caption] + comparisons).joined(separator: ", "))
        .accessibilityAddTraits(onOpen == nil ? [] : .isButton)
        .accessibilityAction { onOpen?() }
        .accessibilityActions {
            if canAverage {
                Button(showsAverage ? "Show total" : "Show average per \(averageBasis)") { wantsAverage.toggle() }
            }
        }
    }

    /// Swaps the total and the average per day. Filled while the average is showing.
    private var averageSwitch: some View {
        Button {
            withAnimation(.snappy(duration: 0.3)) { wantsAverage.toggle() }
        } label: {
            Image(systemName: "arrow.up.arrow.down")
                .font(.system(size: 12, weight: .bold))
                .foregroundStyle(showsAverage ? style.onAccent : style.foreground)
                .frame(width: 28, height: 28)
                .background(showsAverage ? style.accent : style.track, in: Circle())
                .frame(width: 44, height: 44)
                .contentShape(Rectangle())
        }
        .buttonStyle(.pressable)
        .accessibilityLabel(showsAverage ? "Show total" : "Show average per \(averageBasis)")
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
        guard entries > 0 else { return "No entries \(period.emptyPhrase)" }
        // Say which period the number covers.
        var parts = ["\(label) \(period.phrase)"]
        if let unit, !unit.isEmpty, unit != "JPY", unit != label {
            parts.insert(unit, at: 0)
        }
        if showsAverage {
            return (["avg per \(averageBasis)"] + parts).joined(separator: " · ")
        }
        // A stat's goal is a limit (calories, budget): say what's left, or how far over.
        if let goal, let value {
            let left = goal - value
            if left < 0 {
                parts.append("\(Formatters.headline(-left, unit: unit)) over")
            } else {
                parts.append(period.isCurrent ? "\(Formatters.headline(left, unit: unit)) left"
                                              : "\(Formatters.headline(left, unit: unit)) under goal")
            }
        }
        return parts.joined(separator: " · ")
    }
}

/// The selected date range and the range immediately before it.
struct StatPeriod {
    private enum Unit: Equatable {
        case day, week, month, year

        var component: Calendar.Component {
            switch self {
            case .day: .day
            case .week: .weekOfYear
            case .month: .month
            case .year: .year
            }
        }
    }

    private let unit: Unit
    let phrase: String
    let previousPhrase: String
    let isCurrent: Bool
    private let isPrevious: Bool
    var isDay: Bool { unit == .day }
    var emptyPhrase: String {
        isCurrent || isPrevious ? phrase : unit == .week ? "for \(phrase)" : phrase
    }

    init(range: HomeRange, selectedDate: LocalDay = .today()) {
        let unit: Unit = switch range {
        case .day: .day
        case .week: .week
        case .month: .month
        case .year: .year
        }
        self.init(unit: unit, selectedDate: selectedDate)
    }

    /// From a period badge: "today", "this_week", …, anchored to the selected date.
    init?(badge period: String?, range: HomeRange, selectedDate: LocalDay) {
        let unit: Unit
        switch period {
        case "today", "yesterday": unit = .day
        case "this_week": unit = .week
        case "this_month": unit = .month
        case "this_year": unit = .year
        default:
            if period == "range" {
                self.init(range: range, selectedDate: selectedDate)
                return
            }
            return nil
        }
        self.init(unit: unit, selectedDate: selectedDate)
    }

    private init(unit: Unit, selectedDate: LocalDay) {
        self.unit = unit
        let calendar = Self.calendar
        let anchor = selectedDate.date(in: calendar) ?? .now
        let start = Self.start(of: unit, containing: anchor, calendar: calendar)
        let today = Self.start(of: unit, containing: .now, calendar: calendar)
        isCurrent = calendar.isDate(start, inSameDayAs: today)
        let previous = calendar.date(byAdding: unit.component, value: -1, to: today) ?? today
        isPrevious = calendar.isDate(start, inSameDayAs: previous)
        phrase = isCurrent ? Self.currentPhrase(unit)
            : isPrevious ? Self.previousPhrase(unit)
            : Self.historicalPhrase(start, unit: unit, calendar: calendar)
        previousPhrase = isCurrent ? Self.previousPhrase(unit) : Self.earlierPhrase(unit)
    }

    private static var calendar: Calendar {
        var calendar = Calendar.autoupdatingCurrent
        calendar.firstWeekday = 2
        calendar.minimumDaysInFirstWeek = 4
        return calendar
    }

    private static func start(of unit: Unit, containing date: Date, calendar: Calendar) -> Date {
        switch unit {
        case .day: calendar.startOfDay(for: date)
        case .week: calendar.dateInterval(of: .weekOfYear, for: date)?.start ?? calendar.startOfDay(for: date)
        case .month: calendar.dateInterval(of: .month, for: date)?.start ?? calendar.startOfDay(for: date)
        case .year: calendar.dateInterval(of: .year, for: date)?.start ?? calendar.startOfDay(for: date)
        }
    }

    private static func historicalPhrase(_ start: Date, unit: Unit, calendar: Calendar) -> String {
        switch unit {
        case .day:
            "on " + start.formatted(.dateTime.month(.abbreviated).day())
        case .week:
            "the week of " + start.formatted(.dateTime.month(.abbreviated).day())
        case .month:
            "in " + start.formatted(.dateTime.month(.abbreviated).year())
        case .year:
            "in " + String(calendar.component(.year, from: start))
        }
    }

    private static func currentPhrase(_ unit: Unit) -> String {
        switch unit {
        case .day: "today"
        case .week: "this week"
        case .month: "this month"
        case .year: "this year"
        }
    }

    private static func previousPhrase(_ unit: Unit) -> String {
        switch unit {
        case .day: "yesterday"
        case .week: "last week"
        case .month: "last month"
        case .year: "last year"
        }
    }

    private static func earlierPhrase(_ unit: Unit) -> String {
        switch unit {
        case .day: "the day before"
        case .week: "the week before"
        case .month: "the month before"
        case .year: "the year before"
        }
    }
}

// MARK: - Progress

/// A value against a goal, as a ring, a half-circle gauge or a bar.
struct ProgressBlockView: View {
    let block: ProgressBlock
    let period: StatPeriod

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
                .textStyle(.cardDisplay)
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
        if left < 0 { return "\(Formatters.headline(-left, unit: block.unit)) over" }
        return period.isCurrent ? "\(Formatters.headline(left, unit: block.unit)) left"
            : "\(Formatters.headline(left, unit: block.unit)) under goal"
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
    private var total: Double { block.points.compactMap(\.value).reduce(0, +) }
    /// What a full-height bar stands for. Daily bars are measured against your usual day too,
    /// so a lone bar early in the week is tall on a big day and short on a quiet one.
    private var scaleMax: Double {
        guard range != .year, let usual = block.averageDay?.baseline else { return maxValue }
        return max(maxValue, usual)
    }
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
            if block.style == "line" {
                lineChart
            } else if block.style == "dots" {
                dotChart
            } else if range == .day, let today {
                TodayMarks(label: block.label, value: today.value ?? 0, best: bestOtherDay)
            } else {
                bars
            }
        }
        .revealOnScroll($shown)
    }

    /// A light weekly rhythm view: one mark per period, with size showing volume.
    private var dotChart: some View {
        VStack(alignment: .leading, spacing: Space.s3) {
            if isEmpty {
                Text("No entries yet")
                    .textStyle(.caption)
                    .foregroundStyle(style.muted)
            }
            if block.points.count <= 7 {
                HStack(spacing: 4) {
                    ForEach(Array(block.points.enumerated()), id: \.offset) { index, point in
                        dotPoint(index: index, point: point)
                            .frame(maxWidth: .infinity)
                    }
                }
                .frame(height: 54)
            } else {
                LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 4), count: 7), spacing: 8) {
                    ForEach(Array(block.points.enumerated()), id: \.offset) { index, point in
                        dotPoint(index: index, point: point)
                    }
                }
            }
            if let selected, block.points.indices.contains(selected) {
                selectedCaption(block.points[selected])
            }
        }
        .sensoryFeedback(.selection, trigger: selected)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(summary)
    }

    private func dotPoint(index: Int, point: ChartBlock.Point) -> some View {
        let value = max(0, point.value ?? 0)
        let isSelected = selected == index
        let isFuture = point.state == "future"
        let isCurrent = point.state == "current"
        // A day with nothing logged gets no dot; today still keeps its ring, and the future its dashed one.
        let isBlank = point.entries == 0 && !isFuture
        let diameter: CGFloat = value == 0 ? 9 : 12 + 10 * value / max(scaleMax, 1)
        return VStack(spacing: 7) {
            Spacer(minLength: 0)
            Circle()
                .fill(isFuture || value == 0 ? style.track : style.accent.opacity(isSelected ? 1 : 0.82))
                .frame(width: diameter, height: diameter)
                .opacity(isBlank ? 0 : 1)
                .overlay {
                    if isFuture || isCurrent {
                        Circle().strokeBorder(style.accent.opacity(isFuture ? 0.5 : 0.9),
                                              style: StrokeStyle(lineWidth: isCurrent ? 2 : 1.5,
                                                                 dash: isFuture ? [3, 2] : []))
                    }
                }
                .overlay {
                    if isSelected { Circle().stroke(style.foreground.opacity(0.35), lineWidth: 2).padding(-4) }
                }
                .overlay {
                    if isCurrent { ChartPulse(color: style.accent) }
                }
            Text(dotLabel(index: index, day: point.period))
                .textStyle(.caption, weight: isCurrent ? .bold : .medium)
                .foregroundStyle(isCurrent ? style.foreground : style.muted)
                .lineLimit(1)
        }
        .frame(maxWidth: .infinity)
        .frame(height: 54)
        .contentShape(Rectangle())
        .onTapGesture {
            guard point.entries > 0 else { return }
            if index == selected, let onOpenDay { onOpenDay(point.period) }
            else if !isFuture { withAnimation(.snappy(duration: 0.2)) { selected = index } }
        }
        .growIn(shown, delay: min(Double(index) * 0.025, 0.5))
    }

    private func dotLabel(index: Int, day: LocalDay) -> String {
        if block.points.count <= 7 { return xLabel(index: index, day: day) }
        return index % 5 == 0 || index == block.points.count - 1 ? "\(day.day)" : " "
    }

    /// A quiet, thin trend stroke with distinct markers for today and future dates.
    private var lineChart: some View {
        VStack(alignment: .leading, spacing: Space.s2) {
            if isEmpty {
                Text("No entries yet")
                    .textStyle(.caption)
                    .foregroundStyle(style.muted)
            }
            GeometryReader { geometry in
                let plotHeight = max(geometry.size.height - 22, 1)
                let coords = lineCoordinates(width: geometry.size.width, height: plotHeight)
                ZStack(alignment: .topLeading) {
                    Canvas { context, size in
                        var baseline = Path()
                        baseline.move(to: CGPoint(x: 0, y: plotHeight))
                        baseline.addLine(to: CGPoint(x: size.width, y: plotHeight))
                        context.stroke(baseline, with: .color(style.track), style: StrokeStyle(lineWidth: 1, dash: [4, 4]))
                        guard let first = coords.first else { return }
                        var path = Path()
                        path.move(to: first.point)
                        for item in coords.dropFirst() { path.addLine(to: item.point) }
                        context.stroke(path, with: .color(style.accent), style: StrokeStyle(lineWidth: 3, lineCap: .round, lineJoin: .round))
                    }
                    ForEach(coords, id: \.index) { item in
                        let point = block.points[item.index]
                        Circle()
                            .fill(point.state == "current" ? style.pillBackground : style.accent)
                            .frame(width: selected == item.index ? 13 : 9, height: selected == item.index ? 13 : 9)
                            .overlay(Circle().stroke(style.accent, lineWidth: point.state == "current" ? 2 : 0))
                            .overlay {
                                if point.state == "current" { ChartPulse(color: style.accent) }
                            }
                            .position(item.point)
                    }
                    HStack(alignment: .bottom, spacing: 0) {
                        ForEach(Array(block.points.enumerated()), id: \.offset) { index, point in
                            Text(xLabel(index: index, day: point.period).isEmpty ? " " : xLabel(index: index, day: point.period))
                                .textStyle(.caption, weight: .semibold)
                                .foregroundStyle(point.state == "current" ? style.foreground : style.muted)
                                .lineLimit(1)
                                .frame(maxWidth: .infinity)
                        }
                    }
                    .frame(height: 18)
                    .offset(y: plotHeight + 4)
                }
                .contentShape(Rectangle())
                .gesture(SpatialTapGesture().onEnded { tap in
                    select(at: tap.location.x, width: geometry.size.width)
                })
            }
            .frame(height: 118)
            if let selected, block.points.indices.contains(selected) {
                selectedCaption(block.points[selected])
            }
        }
        .sensoryFeedback(.selection, trigger: selected)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(summary)
    }

    private func lineCoordinates(width: CGFloat, height: CGFloat) -> [LineCoordinate] {
        guard !block.points.isEmpty else { return [] }
        let maxY = max(scaleMax, 1)
        let usableHeight = max(height - 8, 1)
        // Only days with something logged get a point; the line joins them across the empty days.
        return block.points.enumerated().compactMap { index, point in
            guard point.state != "future", point.entries > 0, let value = point.value else { return nil }
            // The middle of the day's column, so each point sits over its label (and where a tap picks it).
            let x = width * (CGFloat(index) + 0.5) / CGFloat(block.points.count)
            let y = usableHeight - CGFloat(max(0, value) / maxY) * (usableHeight - 8) + 4
            return LineCoordinate(index: index, point: CGPoint(x: x, y: y))
        }
    }

    private func selectedCaption(_ point: ChartBlock.Point) -> some View {
        Text("\(point.period.description) · \(Formatters.number(point.value ?? 0))\(point.state == "current" ? " so far" : "")")
            .textStyle(.caption, weight: .semibold)
            .foregroundStyle(style.muted)
    }

    private struct LineCoordinate: Identifiable {
        let index: Int
        let point: CGPoint
        var id: Int { index }
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
        // Days with nothing, and days still to come, are a thin line: the eye goes to the bars.
        let isBlank = isFuture || value <= 0
        let fullHeight: CGFloat = isBlank ? 3 : 10 + 78 * value / max(scaleMax, value)
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
                if isBlank {
                    Capsule().fill(style.accent.opacity(isFuture ? 0.14 : 0.3))
                } else if isCurrent && !isSelected {
                    Hatch(color: style.accent.opacity(0.4))
                        .clipShape(Capsule())
                        .overlay(Capsule().strokeBorder(style.accent.opacity(0.4), lineWidth: 1.5))
                } else {
                    Capsule().fill(style.accent.opacity(isHighlighted ? 1 : 0.22))
                }
            }
            .frame(maxWidth: 34)
            .frame(height: shown ? fullHeight : 3)
            .overlay(alignment: .top) {
                if isCurrent { ChartPulse(color: style.accent) }
            }
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
        // The only bar with anything in it: its number is the card's headline already.
        guard value < total else { return nil }
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
            selected = index == selected || block.points[index].state == "future"
                || (block.style == "line" && block.points[index].entries == 0) ? nil : index
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

/// A quiet heartbeat at the live edge of a chart. Past periods stay still, and
/// Reduce Motion leaves only the steady center point.
private struct ChartPulse: View {
    let color: Color

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var isBreathing = false

    var body: some View {
        ZStack {
            Circle()
                .stroke(color.opacity(isBreathing ? 0 : 0.58), lineWidth: 1.5)
                .scaleEffect(isBreathing ? 1.8 : 0.65)
            Circle()
                .fill(color)
                .frame(width: 6, height: 6)
        }
        .frame(width: 18, height: 18)
        .allowsHitTesting(false)
        .accessibilityHidden(true)
        .onAppear {
            guard !reduceMotion else { return }
            withAnimation(.easeOut(duration: 1.35).repeatForever(autoreverses: false)) {
                isBreathing = true
            }
        }
    }
}

/// Today so far: the count, and how it compares with the best day this week.
private struct TodayMarks: View {
    let label: String
    let value: Double
    let best: (value: Double, name: String)?

    @Environment(\.cardStyle) private var style

    var body: some View {
        if let footnote {
            Text(footnote)
                .textStyle(.caption)
                .foregroundStyle(style.muted)
        }
    }

    private var footnote: String? {
        guard let best else {
            return value > 0 ? "Your best day this week so far" : nil
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

/// A dot per day: filled when done, dashed when planned, and muted when no plan is scheduled.
struct CheckinsBlockView: View {
    let block: CheckinsBlock
    let range: HomeRange
    let period: StatPeriod
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
                        .textStyle(.cardDisplay)
                } else {
                    Text("\(block.doneCount)")
                        .textStyle(.cardDisplay)
                }
                Text(caption)
                    .textStyle(.label)
                    .foregroundStyle(style.muted)
            }

            if isRow {
                HStack(spacing: 6) {
                    ForEach(Array(block.slots.enumerated()), id: \.offset) { index, slot in
                        VStack(spacing: 6) {
                            dot(slot, size: 30, index: index)
                            Text(letter(for: slot.period))
                                .textStyle(.caption, weight: .semibold)
                                .foregroundStyle(style.muted)
                        }
                        .frame(maxWidth: .infinity)
                    }
                }
            } else {
                if range == .year {
                    LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 8), count: 4), spacing: 10) {
                        ForEach(Array(block.slots.enumerated()), id: \.offset) { index, slot in
                            habitCell(slot, index: index)
                        }
                    }
                } else {
                    VStack(spacing: 6) {
                        HStack(spacing: 4) {
                            ForEach(Calendar.current.veryShortStandaloneWeekdaySymbols.indices, id: \.self) { index in
                                Text(Calendar.current.veryShortStandaloneWeekdaySymbols[(index + 1) % 7])
                                    .textStyle(.caption, weight: .semibold)
                                    .foregroundStyle(style.muted)
                                    .frame(maxWidth: .infinity)
                            }
                        }
                        LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 5), count: 7), spacing: 5) {
                            if let first = block.slots.first, let date = first.period.date() {
                                let weekday = Calendar.current.component(.weekday, from: date)
                                ForEach(0..<((weekday + 5) % 7), id: \.self) { _ in
                                    Color.clear.frame(height: 34)
                                }
                            }
                            ForEach(Array(block.slots.enumerated()), id: \.offset) { index, slot in
                                habitCell(slot, index: index)
                            }
                        }
                    }
                }
            }
        }
        .revealOnScroll($shown)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("\(block.label): \(block.doneCount)\(block.goal.map { " of \($0)" } ?? "") done. \(caption)")
    }

    @ViewBuilder
    private func dot(_ slot: CheckinsBlock.Slot, size: CGFloat, index: Int) -> some View {
        ZStack {
            if slot.done {
                Circle().fill(style.accent)
            } else {
                switch slot.state {
                case "planned":
                    Circle().strokeBorder(style.accent, style: StrokeStyle(lineWidth: 1.8, dash: [4, 3]))
                case "future":
                    Circle().strokeBorder(style.muted.opacity(0.45), lineWidth: 1.2)
                case "current":
                    Circle().strokeBorder(style.accent, lineWidth: 1.5)
                default:
                    Circle().fill(style.track)
                }
            }
            if slot.done, let symbol = activitySymbol(slot.activity) {
                Image(systemName: symbol)
                    .font(.system(size: size * 0.48, weight: .semibold))
                    .foregroundStyle(style.onAccent)
                    .accessibilityHidden(true)
            }
        }
        .frame(width: size, height: size)
        .scaleEffect(shown ? 1 : 0.3)
        .opacity(shown ? 1 : 0)
        .growIn(shown, delay: min(Double(index) * 0.04, 0.6))
        .contentShape(Circle())
        .onTapGesture {
            if slot.state != "future" && slot.state != "planned" {
                onSlot?(slot.period)
            }
        }
    }

    private func habitCell(_ slot: CheckinsBlock.Slot, index: Int) -> some View {
        let isFuture = slot.state == "future"
        let isPlanned = slot.state == "planned"
        let isCurrent = slot.state == "current"
        return ZStack {
            RoundedRectangle(cornerRadius: 10, style: .continuous)
                .fill(slot.done ? style.accent : (isFuture ? style.track.opacity(0.45) : style.track))
            if isCurrent {
                RoundedRectangle(cornerRadius: 10, style: .continuous)
                    .strokeBorder(style.accent, lineWidth: 1.5)
            }
            VStack(spacing: 1) {
                Text(range == .year ? monthLetter(slot.period) : "\(slot.period.day)")
                    .textStyle(.caption, weight: isCurrent ? .bold : .medium)
                if slot.done, let symbol = activitySymbol(slot.activity) {
                    Image(systemName: symbol)
                        .font(.system(size: 14, weight: .semibold))
                        .frame(height: 16)
                        .accessibilityHidden(true)
                }
            }
            .foregroundStyle(slot.done ? style.onAccent : style.muted)
        }
        .frame(maxWidth: .infinity)
        .frame(height: 42)
        .overlay {
            if isPlanned {
                RoundedRectangle(cornerRadius: 10, style: .continuous)
                    .strokeBorder(style.accent, style: StrokeStyle(lineWidth: 1.8, dash: [4, 3]))
            } else if isFuture {
                RoundedRectangle(cornerRadius: 10, style: .continuous)
                    .strokeBorder(style.muted.opacity(0.45), style: StrokeStyle(lineWidth: 1, dash: [3, 2]))
            }
        }
        .scaleEffect(shown ? 1 : 0.86)
        .opacity(shown ? 1 : 0)
        .contentShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
        .accessibilityLabel("\(slot.period.description)\(slot.activity.map { ", \($0)" } ?? "")\(slot.done ? ", completed" : "")")
        .onTapGesture {
            if !isFuture && !isPlanned { onSlot?(slot.period) }
        }
        .growIn(shown, delay: min(Double(index) * 0.015, 0.4))
    }

    private var caption: String {
        let planned = plannedCaption
        guard let goal = block.goal else {
            if range == .year {
                let periodCaption = "\(block.label) \(period.phrase)"
                if let streak = block.streak, streak.current > 0 {
                    return [periodCaption, "\(streak.current)-day streak", planned].compactMap { $0 }.joined(separator: " · ")
                }
                return [periodCaption, planned].compactMap { $0 }.joined(separator: " · ")
            }
            if let streak = block.streak, streak.current > 0 {
                return ["\(block.label) · \(streak.current)-day streak", planned].compactMap { $0 }.joined(separator: " · ")
            }
            return ["\(block.label) · days active", planned].compactMap { $0 }.joined(separator: " · ")
        }
        let left = goal - block.doneCount
        let headline = left > 0 ? "\(block.label) · \(left) more to hit your goal" : "\(block.label) · goal reached"
        return [headline, planned].compactMap { $0 }.joined(separator: " · ")
    }

    private var plannedCaption: String? {
        guard let count = block.plannedCount, count > 0 else { return nil }
        return count == 1 ? "one more planned" : "\(count) planned"
    }

    private func letter(for day: LocalDay) -> String {
        let calendar = Calendar.current
        guard let date = day.date(in: calendar) else { return "" }
        return calendar.veryShortWeekdaySymbols[calendar.component(.weekday, from: date) - 1]
    }

    private func monthLetter(_ day: LocalDay) -> String {
        Calendar.current.veryShortMonthSymbols[day.month - 1]
    }

    /// Translate logged workout types to a compact symbol; multiple types on one period use the general strength icon.
    private func activitySymbol(_ activity: String?) -> String? {
        guard let activity, !activity.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return nil }
        let activities = activity.split(separator: ",").map { $0.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() }
        guard activities.count == 1, let value = activities.first else { return "dumbbell.fill" }
        if value.contains("walk") { return "figure.walk" }
        if value.contains("run") || value.contains("jog") { return "figure.run" }
        if ["strength", "weight", "lift", "push", "pull", "leg day", "gym"].contains(where: value.contains) {
            return "dumbbell.fill"
        }
        return nil
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
