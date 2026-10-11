import SwiftUI

/// One chat row: your message on the right in mint, TinyMe's on the left in white beside its face.
struct MessageBubble: View {
    let item: ChatItem
    var onRetry: () -> Void = {}
    /// Gives the model for a card a reply refers to. Nil: cards aren't shown inline.
    var inlineCard: ((String) -> InlineCardModel)?
    var onChangeGoal: (CardDTO) -> Void = { _ in }

    var body: some View {
        switch item {
        case .user(_, let text, let status, _):
            userRow(text: text, status: status)
        case .assistant(_, let text, let actions, let status, _):
            assistantRow(text: text, actions: actions, status: status)
        }
    }

    // MARK: - You

    private func userRow(text: String, status: ChatItem.UserStatus) -> some View {
        HStack {
            Spacer(minLength: 56)
            VStack(alignment: .trailing, spacing: 4) {
                Text(text)
                    .textStyle(.body)
                    .foregroundStyle(Palette.onBrand)
                    .padding(.horizontal, Space.s4)
                    .padding(.vertical, Space.s3)
                    .background(Palette.brand, in: Self.shape(tail: .trailing))
                    .opacity(status == .sending ? 0.6 : 1)
                if status == .failed {
                    Button(action: onRetry) {
                        Label("Not sent. Tap to try again", systemImage: "arrow.clockwise")
                            .textStyle(.caption, weight: .semibold)
                            .foregroundStyle(Palette.danger)
                            .frame(minHeight: 32)
                    }
                    .buttonStyle(.pressable)
                }
            }
        }
        .accessibilityElement(children: .combine)
        .accessibilityLabel(status == .failed ? "You, not sent: \(text)" : "You: \(text)")
    }

    // MARK: - TinyMe

    private func assistantRow(text: String, actions: [ActionDTO], status: ChatItem.AssistantStatus) -> some View {
        HStack(alignment: .bottom, spacing: Space.s2) {
            TinyMeFace(size: 28)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: Space.s2) {
                if !actions.isEmpty {
                    FlowLayout(spacing: 6) {
                        ForEach(Array(actions.enumerated()), id: \.offset) { _, action in
                            ActionChip(action: action)
                        }
                    }
                }
                if !text.isEmpty {
                    Text(Self.formatted(text))
                        .textStyle(.body)
                        .foregroundStyle(Palette.ink)
                        .padding(.horizontal, Space.s4)
                        .padding(.vertical, Space.s3)
                        .background {
                            Self.shape(tail: .leading)
                                .fill(Palette.surfaceRaised)
                                .cardShadow()
                        }
                        .accessibilityLabel("TinyMe: \(text)")
                } else if status == .streaming {
                    ThinkingDots()
                        .padding(.horizontal, Space.s4)
                        .padding(.vertical, 18)
                        .background {
                            Self.shape(tail: .leading)
                                .fill(Palette.surfaceRaised)
                                .cardShadow()
                        }
                        .accessibilityLabel("TinyMe is thinking")
                }
                if let inlineCard {
                    ForEach(Self.cardIds(in: actions), id: \.self) { cardId in
                        InlineCardView(model: inlineCard(cardId), onChangeGoal: onChangeGoal)
                    }
                }
                if case .failed(let message, let retryable) = status {
                    failure(message: message, retryable: retryable)
                }
            }
            Spacer(minLength: 32)
        }
    }

    private func failure(message: String, retryable: Bool) -> some View {
        VStack(alignment: .leading, spacing: Space.s2) {
            Label(message, systemImage: "exclamationmark.circle")
                .textStyle(.label)
                .foregroundStyle(Palette.onAccentSoft)
            if retryable {
                Button(action: onRetry) {
                    Text("Try again")
                        .textStyle(.label, weight: .semibold)
                        .foregroundStyle(Palette.onInk)
                        .padding(.horizontal, Space.s4)
                        .frame(height: 36)
                        .background(Palette.ink, in: Capsule())
                        .frame(minHeight: 44)
                }
                .buttonStyle(.pressable)
            }
        }
        .padding(.horizontal, Space.s4)
        .padding(.vertical, Space.s3)
        .background(Palette.accentSoft, in: Self.shape(tail: .leading))
    }

    /// Each card the reply touched, once, in the order it came up.
    private static func cardIds(in actions: [ActionDTO]) -> [String] {
        var seen = Set<String>()
        return actions.compactMap(\.cardId).filter { seen.insert($0).inserted }
    }

    // MARK: - Shared

    /// radius-md corners, with a small one where the bubble points at its speaker.
    private static func shape(tail: HorizontalEdge) -> UnevenRoundedRectangle {
        UnevenRoundedRectangle(
            topLeadingRadius: Radius.md,
            bottomLeadingRadius: tail == .leading ? 6 : Radius.md,
            bottomTrailingRadius: tail == .trailing ? 6 : Radius.md,
            topTrailingRadius: Radius.md,
            style: .continuous
        )
    }

    /// Replies may use **bold**, _italics_ and links; anything else stays as typed.
    private static func formatted(_ text: String) -> AttributedString {
        let options = AttributedString.MarkdownParsingOptions(interpretedSyntax: .inlineOnlyPreservingWhitespace)
        return (try? AttributedString(markdown: text, options: options)) ?? AttributedString(text)
    }
}

/// Three bobbing dots, the middle one mint, while TinyMe works on a reply.
private struct ThinkingDots: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var bobbing = false

    var body: some View {
        HStack(spacing: 5) {
            ForEach(0..<3, id: \.self) { index in
                Circle()
                    .fill(index == 1 ? Palette.brand : Palette.ink)
                    .frame(width: 7, height: 7)
                    .offset(y: bobbing && !reduceMotion ? -4 : 0)
                    .animation(
                        .easeInOut(duration: 0.5).repeatForever(autoreverses: true).delay(Double(index) * 0.15),
                        value: bobbing
                    )
            }
        }
        .onAppear { bobbing = true }
    }
}
