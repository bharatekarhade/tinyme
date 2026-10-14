import SwiftUI

/// The streak in the Home header: TinyMe's face, the count, and a dot per day. Not a card, and not tied to the range.
struct StreakStrip: View {
    let streak: JournalStreak

    var body: some View {
        HStack(spacing: Space.s3) {
            TinyMeFace(size: 44, mood: mood, reactsToTap: true)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 0) {
                Text(title)
                    .textStyle(.heading)
                    .foregroundStyle(Palette.ink)
                Text(note)
                    .textStyle(.caption)
                    .foregroundStyle(Palette.inkMuted)
            }
            Spacer(minLength: Space.s2)
            HStack(spacing: 6) {
                ForEach(Array(streak.days.enumerated()), id: \.offset) { index, done in
                    dot(done: done, isToday: index == streak.days.count - 1)
                }
            }
            .accessibilityHidden(true)
        }
        .accessibilityElement(children: .combine)
        .accessibilityLabel("Journal streak: \(title). \(note)")
    }

    @ViewBuilder
    private func dot(done: Bool, isToday: Bool) -> some View {
        if done {
            Circle()
                .fill(streak.isMilestone ? Palette.sunny : Palette.brand)
                .frame(width: 10, height: 10)
        } else if isToday {
            // Still open: today isn't over.
            Circle()
                .strokeBorder(Palette.brand, lineWidth: 1.5)
                .frame(width: 10, height: 10)
        } else {
            Circle()
                .fill(Palette.surfaceSunken)
                .frame(width: 10, height: 10)
        }
    }

    private var mood: TinyMeFace.Mood {
        if streak.current == 0 { return .sleepy }
        return streak.isMilestone ? .pleased : .awake
    }

    private var title: String {
        switch streak.current {
        case 0: streak.best > 0 ? "No streak right now" : "No streak yet"
        case 1: "1 day so far"
        default: "\(streak.current) days in a row"
        }
    }

    private var note: String {
        if streak.current == 0 {
            return streak.best > 0 ? "Your best is \(streak.best) days. Journal today to start again." : "Journal today to start one."
        }
        if !streak.loggedToday { return "Journal today to keep it going." }
        if streak.isMilestone { return "That's a milestone. Nice." }
        if streak.current >= streak.best { return "Your longest yet." }
        return "Your best is \(streak.best) days."
    }
}
