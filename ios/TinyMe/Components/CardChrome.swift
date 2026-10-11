import SwiftUI

/// Icon tile + title row shared by the tracker cards.
struct CardHeader<Trailing: View>: View {
    var title: String
    var systemImage: String
    var tileColor: Color
    @ViewBuilder var trailing: Trailing

    var body: some View {
        HStack(spacing: Space.s3) {
            Image(systemName: systemImage)
                .font(.system(size: 17, weight: .regular))
                .frame(width: 36, height: 36)
                .background(tileColor, in: RoundedRectangle(cornerRadius: Radius.sm, style: .continuous))
                .accessibilityHidden(true)
            Text(title)
                .textStyle(.heading)
            Spacer(minLength: Space.s2)
            trailing
        }
    }
}

/// Small status pill: ink for "selected / now", quiet fills for everything else.
struct Pill: View {
    var text: String
    var background: Color = Palette.ink
    var foreground: Color = Palette.onInk

    var body: some View {
        Text(text)
            .textStyle(.caption, weight: .semibold)
            .foregroundStyle(foreground)
            .padding(.horizontal, 10)
            .frame(height: 24)
            .background(background, in: Capsule())
    }
}

/// Round "open" affordance in the corner of a card.
struct OpenButton: View {
    var label: String
    var background: Color
    var foreground: Color
    var action: () -> Void = {}

    var body: some View {
        Button(action: action) {
            Image(systemName: "arrow.up.right")
                .font(.system(size: 15, weight: .semibold))
                .foregroundStyle(foreground)
                .frame(width: 40, height: 40)
                .background(background, in: Circle())
                .frame(width: 44, height: 44)
        }
        .buttonStyle(.pressable)
        .accessibilityLabel(label)
    }
}

extension View {
    /// White raised card: surface-raised, radius-lg, space-6 padding, shadow-card.
    func raisedCard() -> some View {
        padding(Space.s6)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Palette.surfaceRaised, in: RoundedRectangle(cornerRadius: Radius.lg, style: .continuous))
            .cardShadow()
    }
}
