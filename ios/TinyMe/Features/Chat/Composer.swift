import SwiftUI

/// The message field and send button at the bottom of chat. The mic joins it with voice input.
struct Composer: View {
    @Binding var text: String
    var canSend: Bool
    var isBusy: Bool
    var isFocused: FocusState<Bool>.Binding
    var onSend: () -> Void

    var body: some View {
        HStack(alignment: .bottom, spacing: 10) {
            TextField("Message TinyMe", text: $text, prompt: Text("Message TinyMe").foregroundStyle(Palette.inkMuted), axis: .vertical)
                .textStyle(.body)
                .foregroundStyle(Palette.ink)
                .lineLimit(1...5)
                .focused(isFocused)
                .padding(.horizontal, 20)
                .padding(.vertical, Space.s4)
                .frame(minHeight: 56)
                .glassSurface(RoundedRectangle(cornerRadius: Radius.lg, style: .continuous))

            Button(action: onSend) {
                Group {
                    if isBusy {
                        ProgressView()
                            .tint(Palette.inkMuted)
                    } else {
                        Image(systemName: "arrow.up")
                            .font(.system(size: 20, weight: .bold))
                    }
                }
                .foregroundStyle(canSend ? Palette.onBrand : Palette.inkMuted)
                .frame(width: 56, height: 56)
                .background(canSend ? Palette.brand : Palette.surfaceSunken, in: Circle())
                .shadow(color: canSend ? Palette.pressEdge : .clear, radius: 0, y: 3)
            }
            .buttonStyle(.pressable)
            .disabled(!canSend)
            .animation(.snappy(duration: 0.2), value: canSend)
            .accessibilityLabel(isBusy ? "TinyMe is replying" : "Send")
        }
        .padding(.horizontal, Space.s4)
        .padding(.top, Space.s2)
        .padding(.bottom, Space.s2)
    }
}
