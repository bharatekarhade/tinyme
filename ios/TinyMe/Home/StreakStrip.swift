import SwiftUI

/// The streak in the Home header: TinyMe's face, the count, and a dot per day. Not a card, and not tied to the range.
struct StreakStrip: View {
    let streak: JournalStreak

    var body: some View {
        HStack(spacing: Space.s3) {
            StreakFace(mood: mood, size: 44)
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

    private var mood: StreakFace.Mood {
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

/// TinyMe's face, with a mood: asleep with no streak, awake while one runs, pleased at a milestone.
/// Awake, it blinks every few seconds.
struct StreakFace: View {
    enum Mood {
        case sleepy, awake, pleased
    }

    var mood: Mood
    var size: CGFloat

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var eyesClosed = false

    var body: some View {
        HStack(spacing: size * 0.16) {
            eye
            eye
        }
        .foregroundStyle(mood == .sleepy ? Palette.inkMuted : Palette.onBrand)
        .frame(width: size, height: size)
        .background(background, in: Circle())
        .task(id: mood) {
            eyesClosed = false
            guard mood == .awake, !reduceMotion else { return }
            await blink()
        }
    }

    /// Mostly single blinks a few seconds apart, now and then a quick double.
    private func blink() async {
        while !Task.isCancelled {
            try? await Task.sleep(for: .seconds(Double.random(in: 2.6...5.2)))
            for _ in 0..<(Int.random(in: 0..<5) == 0 ? 2 : 1) {
                guard !Task.isCancelled else { return }
                withAnimation(.easeIn(duration: 0.07)) { eyesClosed = true }
                try? await Task.sleep(for: .milliseconds(110))
                withAnimation(.easeOut(duration: 0.12)) { eyesClosed = false }
                try? await Task.sleep(for: .milliseconds(180))
            }
        }
    }

    private var background: Color {
        switch mood {
        case .sleepy: Palette.surfaceSunken
        case .awake: Palette.brand
        case .pleased: Palette.sunny
        }
    }

    @ViewBuilder
    private var eye: some View {
        switch mood {
        case .sleepy:
            Capsule().frame(width: size * 0.17, height: size * 0.05)
        case .awake:
            Capsule()
                .frame(width: size * 0.125, height: size * 0.19)
                .scaleEffect(y: eyesClosed ? 0.12 : 1)
        case .pleased:
            // Smiling eyes: the top half of a ring.
            Circle()
                .trim(from: 0.5, to: 1)
                .stroke(style: StrokeStyle(lineWidth: size * 0.06, lineCap: .round))
                .frame(width: size * 0.17, height: size * 0.17)
                .offset(y: size * 0.04)
        }
    }
}
