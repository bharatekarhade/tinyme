import SwiftUI

/// Everything about one entry, and two ways to ask TinyMe to fix it. Read-only: changes go through chat.
struct EntryDetailSheet: View {
    let entry: EntryDTO
    var onClose: () -> Void
    /// Opens chat with this text in the composer.
    var onAsk: (String) -> Void

    var body: some View {
        let shown = EntryPresentation(entry)

        VStack(alignment: .leading, spacing: Space.s4) {
            HStack {
                Pill(text: shown.kindLabel, background: Palette.surfaceSunken, foreground: Palette.ink)
                Spacer()
                Button(action: onClose) {
                    Image(systemName: "xmark")
                        .font(.system(size: 14, weight: .bold))
                        .frame(width: 40, height: 40)
                        .background(Palette.surfaceSunken, in: Circle())
                        .frame(width: 44, height: 44)
                }
                .buttonStyle(.pressable)
                .accessibilityLabel("Close")
            }

            VStack(alignment: .leading, spacing: 4) {
                if shown.quote == nil {
                    Text(shown.title)
                        .textStyle(.title)
                }
                if let value = shown.primaryValue {
                    Text(value)
                        .textStyle(.display)
                }
            }

            if !shown.fields.isEmpty {
                VStack(spacing: 0) {
                    ForEach(Array(shown.fields.enumerated()), id: \.element.id) { index, field in
                        if index > 0 {
                            Rectangle()
                                .fill(Palette.line)
                                .frame(height: 1)
                        }
                        HStack(alignment: .firstTextBaseline, spacing: Space.s3) {
                            Text(field.label)
                                .textStyle(.label, weight: .regular)
                                .foregroundStyle(Palette.inkMuted)
                            Spacer(minLength: Space.s2)
                            Text(field.value)
                                .textStyle(.label, weight: .semibold)
                                .multilineTextAlignment(.trailing)
                        }
                        .padding(.horizontal, 18)
                        .padding(.vertical, Space.s3)
                        .accessibilityElement(children: .combine)
                    }
                }
                .background {
                    RoundedRectangle(cornerRadius: Radius.lg, style: .continuous)
                        .fill(Palette.surfaceRaised)
                        .cardShadow()
                }
            }

            if let text = entry.text?.trimmingCharacters(in: .whitespacesAndNewlines), !text.isEmpty {
                Text(text)
                    .textStyle(.body)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }

            Text("Logged \(shown.day), \(shown.time)")
                .textStyle(.caption)
                .foregroundStyle(Palette.inkMuted)

            VStack(spacing: Space.s2) {
                Button {
                    onAsk(shown.changeDraft)
                } label: {
                    Text("Ask TinyMe to change this")
                        .textStyle(.label, weight: .semibold)
                        .foregroundStyle(Palette.onInk)
                        .frame(maxWidth: .infinity)
                        .frame(height: 48)
                        .background(Palette.ink, in: Capsule())
                }
                Button {
                    onAsk(shown.deleteDraft)
                } label: {
                    Text("Ask TinyMe to delete this")
                        .textStyle(.label, weight: .semibold)
                        .foregroundStyle(Palette.danger)
                        .frame(maxWidth: .infinity)
                        .frame(height: 44)
                }
            }
            .buttonStyle(.pressable)
        }
    }
}
