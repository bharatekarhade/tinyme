import SwiftUI

/// One logged entry in a list: icon, what it was, a couple of facts, and the time.
struct EntryRow: View {
    let entry: EntryDTO
    /// The colour of the dot for kinds without an icon.
    var accent: Color = Palette.brand

    var body: some View {
        let shown = EntryPresentation(entry)

        // Centred on the icon, so a one-line row doesn't sit high; a three-line journal quote starts at the top.
        HStack(alignment: shown.quote == nil ? .center : .top, spacing: Space.s3) {
            Group {
                if let symbol = shown.symbol {
                    Image(systemName: symbol)
                        .font(.system(size: 16))
                        .foregroundStyle(shown.isHealthEntry ? Palette.danger : Palette.ink)
                } else {
                    Circle()
                        .fill(accent)
                        .frame(width: 8, height: 8)
                }
            }
            .frame(width: 36, height: 36)
            .background(Palette.surfaceSunken, in: RoundedRectangle(cornerRadius: Radius.sm, style: .continuous))
            .accessibilityHidden(true)

            if let quote = shown.quote {
                Text(quote)
                    .textStyle(.label, weight: .regular)
                    .lineLimit(3)
                    .frame(maxWidth: .infinity, alignment: .leading)
            } else {
                VStack(alignment: .leading, spacing: 2) {
                    Text(shown.title)
                        .textStyle(.label, weight: .semibold)
                        .lineLimit(1)
                    if let subtitle = shown.subtitle {
                        Text(subtitle)
                            .textStyle(.caption)
                            .foregroundStyle(Palette.inkMuted)
                            .lineLimit(1)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }

            VStack(alignment: .trailing, spacing: 2) {
                if !shown.chips.isEmpty {
                    Text(shown.chips.joined(separator: " · "))
                        .textStyle(.label, weight: .semibold)
                        .lineLimit(1)
                }
                Text(shown.time)
                    .textStyle(.caption)
                    .foregroundStyle(Palette.inkMuted)
            }
            .layoutPriority(1)
        }
        .padding(.vertical, Space.s3)
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(.isButton)
    }
}
