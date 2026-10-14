import SwiftUI

/// A small set of app-owned prompts that adapts to the kind of thing the user wants to do.
/// Choosing one drafts it in the composer; it never sends without the user's confirmation.
struct ChatQuickStart: View {
    private enum Intent: String, CaseIterable, Identifiable {
        case log, ask, plan

        var id: Self { self }
        var title: String { rawValue.capitalized }
    }

    private struct Prompt: Identifiable {
        let title: String
        let message: String
        let symbol: String

        var id: String { title }
    }

    var onChoose: (String) -> Void
    @State private var intent: Intent = .log

    private var prompts: [Prompt] {
        switch intent {
        case .log:
            [
                Prompt(title: "Had a coffee", message: "I had a coffee", symbol: "cup.and.saucer"),
                Prompt(title: "Ate a meal", message: "I ate a meal", symbol: "fork.knife"),
                Prompt(title: "Went for a run", message: "I went for a run", symbol: "figure.run")
            ]
        case .ask:
            [
                Prompt(title: "Coffee count today", message: "How many coffees have I had today?", symbol: "cup.and.saucer"),
                Prompt(title: "My recent spending", message: "What have I spent this week?", symbol: "creditcard"),
                Prompt(title: "What's next?", message: "What's coming up today?", symbol: "calendar")
            ]
        case .plan:
            [
                Prompt(title: "Plan my day", message: "Help me plan my day", symbol: "sun.max"),
                Prompt(title: "Remind me to stretch", message: "Remind me to stretch today", symbol: "bell"),
                Prompt(title: "Schedule a workout", message: "Schedule a workout this week", symbol: "dumbbell")
            ]
        }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: Space.s3) {
            HStack(spacing: Space.s2) {
                Image(systemName: "sparkle")
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(Palette.brandStrong)
                Text("Try a prompt")
                    .textStyle(.label, weight: .semibold)
                    .foregroundStyle(Palette.ink)
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            PillSwitcher(options: Intent.allCases, selection: $intent, title: \.title, label: "Prompt type")

            ScrollView(.horizontal) {
                HStack(spacing: Space.s2) {
                    ForEach(prompts) { prompt in
                        Button {
                            onChoose(prompt.message)
                        } label: {
                            HStack(spacing: 7) {
                                Image(systemName: prompt.symbol)
                                    .font(.system(size: 13, weight: .medium))
                                    .foregroundStyle(Palette.brandStrong)
                                Text(prompt.title)
                                    .textStyle(.caption, weight: .semibold)
                                    .foregroundStyle(Palette.ink)
                                    .lineLimit(1)
                            }
                            .padding(.horizontal, Space.s3)
                            .frame(minHeight: 40)
                            .background(Palette.surfaceRaised, in: Capsule())
                            .overlay(Capsule().stroke(Palette.line, lineWidth: 1))
                            .contentShape(Capsule())
                        }
                        .buttonStyle(.pressable)
                        .accessibilityHint("Adds this prompt to the message field for you to review")
                    }
                }
                .padding(.vertical, 2)
            }
            .scrollIndicators(.hidden)
            .contentMargins(.trailing, 2)
            .animation(.snappy(duration: 0.22), value: intent)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}
