import Foundation
import WidgetKit

extension WidgetSnapshot {
    /// Saves how the cards look now and asks the widget to redraw.
    static func publish(cards: [CardDTO], range: HomeRange) {
        WidgetSnapshot(updatedAt: .now, stats: cards.compactMap { Stat(card: $0, range: range) }).save()
        WidgetCenter.shared.reloadTimelines(ofKind: kind)
    }

    static func clearAndReload() {
        clear()
        WidgetCenter.shared.reloadTimelines(ofKind: kind)
    }
}

extension WidgetSnapshot.Stat {
    /// One line for a card, from its headline block. Nil for a card with no single figure to show.
    init?(card: CardDTO, range: HomeRange) {
        guard card.error == nil else { return nil }
        let period: StatPeriod = card.badge?.kind == "period" ? StatPeriod(badge: card.badge?.period) ?? StatPeriod(range: range) : StatPeriod(range: range)

        for block in card.blocks ?? [] {
            let value: String
            let caption: String
            var change = block.averageDay?.text
            switch block {
            case .stat(let stat):
                value = Formatters.headline(stat.value ?? 0, unit: stat.unit)
                caption = "\(stat.label) \(period.phrase)"
                change = stat.changeText(period) ?? change
            case .progress(let progress):
                value = Formatters.headline(progress.value ?? 0, unit: progress.unit)
                caption = progress.goal.map { "of \(Formatters.value($0, unit: progress.unit))" } ?? progress.label
            case .checkins(let checkins):
                value = checkins.goal.map { "\(checkins.doneCount) of \($0)" } ?? "\(checkins.doneCount)"
                caption = checkins.label
            default:
                continue
            }
            self.init(
                title: card.title,
                symbol: CardView.symbol(for: card.icon),
                accent: card.accent,
                value: value,
                caption: caption,
                change: change
            )
            return
        }
        return nil
    }
}
