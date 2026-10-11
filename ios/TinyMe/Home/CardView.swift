import SwiftUI

/// The colours a card's blocks draw with, decided by the card's `tone` and `accent`.
struct CardStyle: Sendable {
    let background: Color
    let foreground: Color
    let muted: Color
    /// Faint fill for bar tracks, empty rings and dividers.
    let track: Color
    /// The colour of bars, rings and segments.
    let accent: Color
    let tile: Color
    /// Marks drawn on top of an `accent` fill (the tick in a done day).
    let onAccent: Color
    /// Small value labels sitting on the card.
    let pillBackground: Color
    let pillForeground: Color
    let isRaised: Bool
    /// The mint hero card (Journal): bigger numbers.
    let isHero: Bool

    init(tone: String, accent: String) {
        let (strong, soft): (Color, Color) = switch accent {
        case "coral": (Palette.accent, Palette.accentSoft)
        case "sunny": (Palette.sunny, Palette.sunnySoft)
        case "ink": (Palette.ink, Palette.surfaceSunken)
        default: (Palette.brand, Palette.brandSoft)   // mint, and anything newer
        }
        switch tone {
        case "ink":
            background = Palette.ink
            foreground = Palette.onInk
            muted = Palette.onInk.opacity(0.7)
            track = Palette.onInk.opacity(0.12)
            self.accent = accent == "ink" ? Palette.onInk : strong
            onAccent = accent == "ink" ? Palette.ink : Palette.onBrand
            tile = Palette.onInk.opacity(0.12)
            pillBackground = Palette.onInk
            pillForeground = Palette.ink
            isRaised = false
            isHero = false
        case "brand":
            background = Palette.brand
            foreground = Palette.onBrand
            muted = Palette.onBrand.opacity(0.75)
            track = Palette.onBrand.opacity(0.18)
            self.accent = Palette.onBrand
            onAccent = Palette.brand
            tile = Palette.onBrand.opacity(0.12)
            pillBackground = Palette.surfaceRaised
            pillForeground = Palette.ink
            isRaised = false
            isHero = true
        default:   // surface, and anything newer
            background = Palette.surfaceRaised
            foreground = Palette.ink
            muted = Palette.inkMuted
            track = Palette.surfaceSunken
            self.accent = strong
            onAccent = accent == "ink" ? Palette.onInk : Palette.onBrand
            tile = soft
            pillBackground = Palette.ink
            pillForeground = Palette.onInk
            isRaised = true
            isHero = false
        }
    }
}

private struct CardStyleKey: EnvironmentKey {
    static let defaultValue = CardStyle(tone: "surface", accent: "mint")
}

extension EnvironmentValues {
    var cardStyle: CardStyle {
        get { self[CardStyleKey.self] }
        set { self[CardStyleKey.self] = newValue }
    }
}

/// One server-defined card: icon, title, badge, then its blocks in order.
struct CardView: View {
    let card: CardDTO
    let range: HomeRange
    /// A draft shown in chat: a "Preview" pill takes the badge's place.
    var isPreview = false
    /// Called with the part that was tapped, to show the entries behind it. Nil: the card isn't tappable (chat previews).
    var onOpen: ((CardTap) -> Void)?

    private var style: CardStyle { CardStyle(tone: card.tone, accent: card.accent) }

    var body: some View {
        VStack(alignment: .leading, spacing: Space.s4) {
            Button {
                onOpen?(CardTap())
            } label: {
                HStack(spacing: Space.s3) {
                    Image(systemName: Self.symbol(for: card.icon))
                        .font(.system(size: 17))
                        .frame(width: 36, height: 36)
                        .background(style.tile, in: RoundedRectangle(cornerRadius: Radius.sm, style: .continuous))
                        .accessibilityHidden(true)
                    Text(card.title)
                        .textStyle(.heading)
                        .lineLimit(1)
                    Spacer(minLength: Space.s2)
                    if isPreview {
                        Pill(text: "Preview", background: style.track, foreground: style.muted)
                    } else if let badge = card.badge, keepsBadge {
                        BadgeView(badge: badge)
                    } else if let headline {
                        Pill(text: headline.text, background: style.track, foreground: style.foreground)
                    }
                    // No comparison to show: the corner stays empty. The caption already names the period.
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .disabled(onOpen == nil)

            if card.error != nil {
                BlockNote("Couldn't load this card")
            } else {
                let blocks = card.blocks ?? []
                // Whatever the corner pill already says isn't repeated under the number.
                let corner = keepsBadge || isPreview ? nil : headline
                // Several blocks can carry the same comparison with your usual day: say it once, on the first.
                let averageIndex = corner?.isAverage == true ? nil : blocks.firstIndex { $0.averageDay?.deltaPct != nil }
                ForEach(Array(blocks.enumerated()), id: \.offset) { index, block in
                    BlockView(
                        block: block,
                        range: range,
                        period: period,
                        showsChange: card.badge?.kind != "delta" && corner?.isAverage != false,
                        average: index == averageIndex ? block.averageDay : nil,
                        onTap: onOpen.map { open in
                            { tap in open(tap.cardTap(block: index)) }
                        }
                    )
                }
            }
        }
        .padding(Space.s6)
        .frame(maxWidth: .infinity, alignment: .leading)
        .foregroundStyle(style.foreground)
        .background {
            let shape = RoundedRectangle(cornerRadius: Radius.lg, style: .continuous).fill(style.background)
            if style.isRaised {
                shape.cardShadow()
            } else {
                shape
            }
        }
        .environment(\.cardStyle, style)
    }

    /// A streak or a server-made change badge stays in the corner; a plain period badge ("Today") is never shown.
    private var keepsBadge: Bool {
        switch card.badge?.kind {
        case "delta": card.badge?.deltaPct != nil
        case "streak": (card.badge?.days ?? 0) > 0
        default: false
        }
    }

    /// The comparison for the corner: against the previous period if there is one, else against your usual day.
    private var headline: (text: String, isAverage: Bool)? {
        let blocks = card.blocks ?? []
        for block in blocks {
            if case .stat(let stat) = block, let text = stat.changeText(period) {
                return (text, false)
            }
        }
        if let text = blocks.lazy.compactMap({ $0.averageDay?.text }).first {
            return (text, true)
        }
        // Nothing earlier to compare with yet: say how much has been logged instead.
        if let entries = blocks.lazy.compactMap({ $0.entryCount }).first, entries > 0 {
            return (entries == 1 ? "1 entry" : "\(entries) entries", true)
        }
        return nil
    }

    /// The period the card's numbers cover: the one its badge names, otherwise the Home range.
    private var period: StatPeriod {
        if card.badge?.kind == "period", let named = StatPeriod(badge: card.badge?.period) {
            return named
        }
        return StatPeriod(range: range)
    }

    /// Server icon names → SF Symbols.
    nonisolated static func symbol(for icon: String) -> String {
        switch icon {
        case "journal": "text.book.closed"
        case "flame": "flame"
        case "wallet": "creditcard"
        case "dumbbell": "dumbbell"
        case "cup": "cup.and.saucer"
        case "bed": "bed.double"
        case "heart": "heart"
        case "steps": "figure.walk"
        case "drop": "drop"
        case "book": "book"
        case "star": "star"
        case "chart": "chart.bar"
        default: "square.grid.2x2"
        }
    }
}

/// The pill at the top right of a card: a period, a change against last time, or a streak.
struct BadgeView: View {
    let badge: BadgeDTO
    @Environment(\.cardStyle) private var style

    var body: some View {
        if let content {
            Pill(text: content.text, background: content.background, foreground: content.foreground)
        }
    }

    private var content: (text: String, background: Color, foreground: Color)? {
        switch badge.kind {
        case "period":
            guard let text = Self.periodText(badge.period) else { return nil }
            return (text, style.foreground, style.background)
        case "delta":
            guard let delta = badge.deltaPct else { return nil }
            let versus = Self.compareText(badge.compareLabel)
            if delta == 0 {
                return ("Same as \(versus)", style.track, style.muted)
            }
            let text = "\(delta > 0 ? "↑" : "↓") \(Formatters.percent(delta)) vs \(versus)"
            let isGood = (delta < 0 && badge.better == "lower") || (delta > 0 && badge.better == "higher")
            let isBad = (delta > 0 && badge.better == "lower") || (delta < 0 && badge.better == "higher")
            guard style.isRaised else { return (text, style.foreground, style.background) }
            if isGood { return (text, Palette.brandSoft, Palette.ink) }
            if isBad { return (text, Palette.accentSoft, Palette.ink) }
            return (text, Palette.surfaceSunken, Palette.inkMuted)
        case "streak":
            guard let days = badge.days, days > 0 else { return nil }
            return ("\(days)-day streak", Palette.sunny, Palette.onBrand)
        default:
            return nil
        }
    }

    private static func periodText(_ period: String?) -> String? {
        switch period {
        case "today": "Today"
        case "yesterday": "Yesterday"
        case "this_week": "This week"
        case "this_month": "This month"
        case "this_year": "This year"
        default: nil
        }
    }

    private static func compareText(_ label: String?) -> String {
        switch label {
        case "yesterday": "yesterday"
        case "last_month": "last month"
        case "last_year": "last year"
        default: "last week"
        }
    }
}
