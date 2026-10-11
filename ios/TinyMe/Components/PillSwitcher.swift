import SwiftUI

/// A row of options in a sunken pill, with a white thumb that slides to the chosen one.
struct PillSwitcher<Option: Hashable>: View {
    let options: [Option]
    @Binding var selection: Option
    let title: (Option) -> String
    var label: String

    @Namespace private var thumb

    var body: some View {
        HStack(spacing: 0) {
            ForEach(options, id: \.self) { option in
                let isOn = option == selection
                Button {
                    withAnimation(.snappy(duration: 0.25)) { selection = option }
                } label: {
                    Text(title(option))
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
        .accessibilityLabel(label)
    }
}
