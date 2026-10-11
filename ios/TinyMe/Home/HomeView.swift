import SwiftUI

struct HomeView: View {
    @Environment(AppEnvironment.self) private var environment
    @State private var range: HomeRange = .week
    @State private var showsSettings = false

    var body: some View {
        ScrollView {
            VStack(spacing: Space.s4) {
                HomeHeader(name: HomeSample.userName, subtitle: range.subtitle) {
                    showsSettings = true
                }
                    .padding(.bottom, Space.s2)
                RangeSwitcher(selection: $range)
                // New identity per range, so every chart fills again on switch.
                Group {
                    JournalCard(stat: HomeSample.journal)
                    CaloriesCard(stat: HomeSample.calories)
                    SpendingCard(stat: HomeSample.spending)
                    GymCard(stat: HomeSample.gym)
                }
                .id(range)
                AddTrackerCard(ideas: HomeSample.trackerIdeas)
            }
            .padding(.horizontal, Space.s4)
            .padding(.top, Space.s2)
            .padding(.bottom, Space.s6)
        }
        .scrollIndicators(.hidden)
        .foregroundStyle(Palette.ink)
        .background(Palette.surface.ignoresSafeArea())
        .safeAreaInset(edge: .bottom) {
            HomeTabBar()
        }
        .overlay {
            SettingsSheet(isPresented: $showsSettings, session: environment.session)
        }
    }
}

private struct HomeHeader: View {
    var name: String
    var subtitle: String
    var onProfile: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: Space.s2) {
                Text(Date.now, format: .dateTime.weekday(.wide).day().month(.wide))
                    .textStyle(.label)
                    .foregroundStyle(Palette.inkMuted)
                Spacer()
                Button {} label: {
                    Image(systemName: "bell")
                        .font(.system(size: 17))
                        .frame(width: 40, height: 40)
                        .background(Palette.surfaceSunken, in: Circle())
                        .overlay(alignment: .topTrailing) {
                            Circle()
                                .fill(Palette.accent)
                                .frame(width: 8, height: 8)
                                .offset(x: -9, y: 9)
                        }
                        .frame(width: 44, height: 44)
                }
                .accessibilityLabel("Notifications")
                Button(action: onProfile) {
                    Text(name.prefix(1))
                        .textStyle(.label, weight: .semibold)
                        .foregroundStyle(Palette.onInk)
                        .frame(width: 40, height: 40)
                        .background(Palette.ink, in: Circle())
                        .frame(width: 44, height: 44)
                }
                .accessibilityLabel("Settings")
            }
            .buttonStyle(.pressable)

            Text("Hey \(name)")
                .textStyle(.display)
                .padding(.top, Space.s2)
                .accessibilityAddTraits(.isHeader)
            Text(subtitle)
                .textStyle(.body)
                .foregroundStyle(Palette.inkMuted)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

private struct RangeSwitcher: View {
    @Binding var selection: HomeRange
    @Namespace private var thumb

    var body: some View {
        HStack(spacing: 0) {
            ForEach(HomeRange.allCases) { range in
                let isOn = range == selection
                Button {
                    withAnimation(.snappy(duration: 0.25)) { selection = range }
                } label: {
                    Text(range.rawValue)
                        .textStyle(.label, weight: isOn ? .semibold : .medium)
                        .foregroundStyle(isOn ? Palette.ink : Palette.inkMuted)
                        .frame(maxWidth: .infinity)
                        .frame(height: 36)
                        .background {
                            if isOn {
                                Capsule()
                                    .fill(Palette.surfaceRaised)
                                    .shadow(color: .black.opacity(0.12), radius: 1.5, y: 1)
                                    .matchedGeometryEffect(id: "thumb", in: thumb)
                            }
                        }
                        .contentShape(Capsule())
                }
                .buttonStyle(.plain)
                .accessibilityAddTraits(isOn ? .isSelected : [])
            }
        }
        .padding(4)
        .background(Palette.surfaceSunken, in: Capsule())
        .sensoryFeedback(.selection, trigger: selection)
        .accessibilityElement(children: .contain)
        .accessibilityLabel("Range")
    }
}

// MARK: - Journal

private struct JournalCard: View {
    var stat: JournalStat
    @State private var shown = false

    private var maxEntries: Int { max(stat.days.map(\.entries).max() ?? 1, 1) }

    var body: some View {
        VStack(alignment: .leading, spacing: Space.s4) {
            HStack(alignment: .top) {
                VStack(alignment: .leading, spacing: 2) {
                    Label("Journal · pinned", systemImage: "pin.fill")
                        .labelStyle(.titleAndIcon)
                        .textStyle(.label, weight: .semibold)
                    Text(shown ? stat.total : 0, format: .number)
                        .textStyle(.numeral)
                        .contentTransition(.numericText(value: Double(shown ? stat.total : 0)))
                        .growIn(shown)
                    HStack(spacing: Space.s2) {
                        Text("entries this week")
                            .textStyle(.label)
                        Pill(
                            text: "\(stat.deltaPercent >= 0 ? "↑" : "↓") \(abs(stat.deltaPercent))%",
                            background: Palette.onBrand,
                            foreground: Palette.brand
                        )
                    }
                }
                Spacer()
                VStack(alignment: .trailing, spacing: Space.s2) {
                    OpenButton(label: "Open journal", background: Palette.onBrand, foreground: Palette.brand)
                    Pill(text: "\(stat.streakDays)-day streak", background: Palette.sunny, foreground: Palette.onBrand)
                }
            }

            HStack(alignment: .bottom, spacing: Space.s2) {
                ForEach(stat.days) { day in
                    bar(for: day)
                }
            }
            .frame(height: 124)
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(chartSummary)
        }
        .padding(Space.s6)
        .foregroundStyle(Palette.onBrand)
        .background(Palette.brand, in: RoundedRectangle(cornerRadius: Radius.lg, style: .continuous))
        .revealOnScroll($shown)
    }

    private func bar(for day: JournalStat.Day) -> some View {
        let isToday = day.id == stat.todayIndex
        let isPeak = day.id == stat.peakIndex
        let height = 18 + 70 * CGFloat(day.entries) / CGFloat(maxEntries)

        return VStack(spacing: 6) {
            Spacer(minLength: 0)
            if isToday || isPeak {
                Text(isToday ? "\(day.entries) so far" : "\(day.entries)")
                    .textStyle(.caption, weight: .semibold)
                    .foregroundStyle(Palette.ink)
                    .fixedSize()
                    .padding(.horizontal, 7)
                    .padding(.vertical, 1)
                    .background(Palette.surfaceRaised, in: Capsule())
                    .opacity(shown ? 1 : 0)
            }
            Group {
                if isToday {
                    // Striped: the day isn't over yet.
                    Hatch(color: Palette.onBrand.opacity(0.35))
                        .clipShape(Capsule())
                        .overlay(Capsule().strokeBorder(Palette.onBrand.opacity(0.35), lineWidth: 1.5))
                } else {
                    Capsule().fill(Palette.onBrand.opacity(isPeak ? 1 : 0.18))
                }
            }
            .frame(maxWidth: 34)
            .frame(height: shown ? height : 10)
            Text(day.label)
                .textStyle(.caption, weight: .semibold)
        }
        .frame(maxWidth: .infinity)
        .growIn(shown, delay: Double(day.id) * 0.06)
    }

    private var chartSummary: String {
        let parts = stat.days.map { day in
            "\(day.name) \(day.entries)" + (day.id == stat.todayIndex ? " so far" : "")
        }
        return "Journal entries: " + parts.joined(separator: ", ")
    }
}

// MARK: - Calories

private struct CaloriesCard: View {
    var stat: CaloriesStat
    @State private var shown = false

    private let gaugeWidth: CGFloat = 22

    var body: some View {
        VStack(spacing: Space.s3) {
            CardHeader(title: "Calories", systemImage: "flame", tileColor: Palette.accentSoft) {
                Pill(text: "Today")
            }

            ZStack(alignment: .bottom) {
                Hatch(color: Palette.accentSoft, stripe: 2.5, gap: 3.5)
                    .mask(HalfArc(lineWidth: gaugeWidth).stroke(style: gaugeStroke))
                HalfArc(lineWidth: gaugeWidth)
                    .trim(from: 0, to: shown ? stat.progress : 0)
                    .stroke(Palette.accent, style: gaugeStroke)
                    .opacity(shown ? 1 : 0)
                VStack(spacing: 0) {
                    Text(shown ? stat.eaten : 0, format: .number)
                        .textStyle(.display)
                        .contentTransition(.numericText(value: Double(shown ? stat.eaten : 0)))
                    Text("of \(stat.goal.formatted()) kcal · \(stat.remaining.formatted()) left")
                        .textStyle(.caption)
                        .foregroundStyle(Palette.inkMuted)
                }
            }
            .frame(width: 240, height: 128)
            .growIn(shown)
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(
                "\(stat.eaten.formatted()) of \(stat.goal.formatted()) calories eaten, \(Int((stat.progress * 100).rounded())) percent"
            )

            HStack(alignment: .top, spacing: Space.s3) {
                ForEach(stat.macros) { macro in
                    VStack(alignment: .leading, spacing: 6) {
                        Text(macro.name)
                            .textStyle(.caption)
                            .foregroundStyle(Palette.inkMuted)
                        Capsule()
                            .fill(Palette.surfaceSunken)
                            .frame(height: 6)
                            .overlay(alignment: .leading) {
                                GeometryReader { geo in
                                    Capsule()
                                        .fill(color(for: macro.kind))
                                        .frame(width: geo.size.width * (shown ? macro.progress : 0))
                                }
                            }
                        Text("\(macro.grams) / \(macro.goal)g")
                            .textStyle(.label, weight: .semibold)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .accessibilityElement(children: .ignore)
                    .accessibilityLabel("\(macro.name), \(macro.grams) of \(macro.goal) grams")
                }
            }
            .padding(.top, 4)
            .growIn(shown, delay: 0.15)
        }
        .raisedCard()
        .revealOnScroll($shown)
    }

    private var gaugeStroke: StrokeStyle {
        StrokeStyle(lineWidth: gaugeWidth, lineCap: .round)
    }

    private func color(for kind: CaloriesStat.Macro.Kind) -> Color {
        switch kind {
        case .protein: Palette.ink
        case .carbs: Palette.accent
        case .fat: Palette.sunny
        }
    }
}

// MARK: - Spending

private struct SpendingCard: View {
    var stat: SpendingStat
    @State private var shown = false

    private let segmentSpacing: CGFloat = 4

    var body: some View {
        VStack(alignment: .leading, spacing: Space.s4) {
            CardHeader(title: "Spending", systemImage: "creditcard", tileColor: Palette.sunnySoft) {
                Pill(
                    text: "\(stat.deltaPercent <= 0 ? "↓" : "↑") \(abs(stat.deltaPercent))% vs last week",
                    background: Palette.brandSoft,
                    foreground: Palette.ink
                )
            }

            HStack(alignment: .firstTextBaseline, spacing: 6) {
                Text(money(stat.spent))
                    .textStyle(.display)
                Text("/ \(money(stat.budget))")
                    .textStyle(.heading, weight: .medium)
                    .foregroundStyle(Palette.inkMuted)
            }
            .accessibilityElement(children: .ignore)
            .accessibilityLabel("\(money(stat.spent)) spent of \(money(stat.budget)) budget")

            GeometryReader { geo in
                let total = CGFloat(max(stat.categories.map(\.amount).reduce(0, +), 1))
                let available = geo.size.width - segmentSpacing * CGFloat(max(stat.categories.count - 1, 0))
                HStack(spacing: segmentSpacing) {
                    ForEach(stat.categories) { category in
                        swatch(for: category.kind)
                            .frame(width: max(available * CGFloat(category.amount) / total, 0))
                    }
                }
            }
            .frame(height: 14)
            .mask {
                Rectangle().scaleEffect(x: shown ? 1 : 0, anchor: .leading)
            }
            .growIn(shown)
            .accessibilityHidden(true)

            LazyVGrid(columns: [GridItem(.flexible(), spacing: Space.s4), GridItem(.flexible())], spacing: Space.s2) {
                ForEach(stat.categories) { category in
                    HStack(spacing: Space.s2) {
                        swatch(for: category.kind)
                            .frame(width: 8, height: 8)
                        Text(category.name)
                            .textStyle(.label, weight: .regular)
                        Spacer(minLength: 0)
                        Text(money(category.amount))
                            .textStyle(.label, weight: .semibold)
                    }
                    .accessibilityElement(children: .combine)
                }
            }
        }
        .raisedCard()
        .revealOnScroll($shown)
    }

    @ViewBuilder
    private func swatch(for kind: SpendingStat.Category.Kind) -> some View {
        switch kind {
        case .food: Capsule().fill(Palette.accent)
        case .transport: Capsule().fill(Palette.brand)
        case .shopping: Capsule().fill(Palette.sunny)
        case .other:
            Hatch(color: Palette.line)
                .clipShape(Capsule())
                .overlay(Capsule().strokeBorder(Palette.line, lineWidth: 1))
        }
    }

    private func money(_ amount: Int) -> String {
        amount.formatted(.currency(code: stat.currencyCode).precision(.fractionLength(0)))
    }
}

// MARK: - Gym

private struct GymCard: View {
    var stat: GymStat
    @State private var shown = false

    private var muted: Color { Palette.onInk.opacity(0.7) }

    var body: some View {
        VStack(alignment: .leading, spacing: Space.s4) {
            CardHeader(title: "Gym", systemImage: "dumbbell", tileColor: Palette.onInk.opacity(0.12)) {
                OpenButton(label: "Open gym", background: Palette.onInk, foreground: Palette.ink)
            }

            VStack(alignment: .leading, spacing: 0) {
                Text("\(stat.visits)\(Text(" of \(stat.goal)").foregroundStyle(muted))")
                    .textStyle(.display)
                Text(goalLine)
                    .textStyle(.label)
                    .foregroundStyle(muted)
            }
            .accessibilityElement(children: .ignore)
            .accessibilityLabel("\(stat.visits) of \(stat.goal) visits")

            HStack(spacing: 6) {
                ForEach(stat.days) { day in
                    VStack(spacing: 6) {
                        dot(for: day.status)
                            .scaleEffect(shown ? 1 : 0.3)
                            .opacity(shown ? 1 : 0)
                            .growIn(shown, delay: Double(day.id) * 0.06)
                        Text(day.label)
                            .textStyle(.caption, weight: .semibold)
                            .foregroundStyle(muted)
                    }
                    .frame(maxWidth: .infinity)
                }
            }
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(visitSummary)

            HStack {
                Text("Last session")
                    .foregroundStyle(muted)
                Spacer()
                Text(stat.lastSession)
                    .fontWeight(.semibold)
            }
            .textStyle(.label)
            .padding(.top, Space.s3)
            .overlay(alignment: .top) {
                Rectangle()
                    .fill(Palette.onInk.opacity(0.12))
                    .frame(height: 1)
            }
        }
        .padding(Space.s6)
        .foregroundStyle(Palette.onInk)
        .background(Palette.ink, in: RoundedRectangle(cornerRadius: Radius.lg, style: .continuous))
        .revealOnScroll($shown)
    }

    @ViewBuilder
    private func dot(for status: GymStat.Day.Status) -> some View {
        switch status {
        case .done:
            Image(systemName: "checkmark")
                .font(.system(size: 13, weight: .bold))
                .foregroundStyle(Palette.onBrand)
                .frame(width: 34, height: 34)
                .background(Palette.brand, in: Circle())
        case .planned:
            Circle()
                .strokeBorder(Palette.brand, style: StrokeStyle(lineWidth: 1.5, dash: [4, 3]))
                .frame(width: 34, height: 34)
        case .rest:
            Circle()
                .fill(Palette.onInk.opacity(0.08))
                .frame(width: 34, height: 34)
        }
    }

    private var goalLine: String {
        let left = stat.goal - stat.visits
        return left > 0 ? "visits · \(left) more to hit your goal" : "visits · goal reached"
    }

    private var visitSummary: String {
        let done = stat.days.filter { $0.status == .done }.map(\.name)
        let planned = stat.days.filter { $0.status == .planned }.map(\.name)
        var summary = "Gym visits: " + done.formatted(.list(type: .and)) + " done"
        if !planned.isEmpty {
            summary += "; " + planned.formatted(.list(type: .and)) + " planned"
        }
        return summary
    }
}

// MARK: - Add a tracker

private struct AddTrackerCard: View {
    var ideas: [String]
    var onPick: (String) -> Void = { _ in }

    var body: some View {
        VStack(alignment: .leading, spacing: Space.s3) {
            HStack(spacing: Space.s3) {
                Image(systemName: "plus")
                    .font(.system(size: 18, weight: .semibold))
                    .foregroundStyle(Palette.onBrand)
                    .frame(width: 40, height: 40)
                    .background(Palette.brand, in: Circle())
                    .accessibilityHidden(true)
                VStack(alignment: .leading, spacing: 0) {
                    Text("Track something new")
                        .textStyle(.heading)
                    Text("Just ask, and I'll build the card.")
                        .textStyle(.label, weight: .regular)
                        .foregroundStyle(Palette.inkMuted)
                }
            }

            FlowLayout(spacing: Space.s2) {
                ForEach(ideas, id: \.self) { idea in
                    Button {
                        onPick(idea)
                    } label: {
                        Text("“\(idea)”")
                            .textStyle(.label)
                            .padding(.horizontal, Space.s4)
                            .frame(height: 38)
                            .background(Palette.surfaceRaised, in: Capsule())
                            .cardShadow()
                            .frame(minHeight: 44)
                    }
                    .buttonStyle(.pressable)
                }
            }
        }
        .padding(Space.s6)
        .frame(maxWidth: .infinity, alignment: .leading)
        .overlay {
            RoundedRectangle(cornerRadius: Radius.lg, style: .continuous)
                .strokeBorder(Palette.inkMuted.opacity(0.35), style: StrokeStyle(lineWidth: 1.5, dash: [6, 5]))
        }
    }
}

#Preview {
    HomeView()
        .environment(AppEnvironment(authStore: AuthStore(service: "app.tinyme.preview")))
}
