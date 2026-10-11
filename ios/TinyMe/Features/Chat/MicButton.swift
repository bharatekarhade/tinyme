import SwiftUI

/// The round mint mic. Hold it to talk; a soft ring breathes around it while it listens.
struct MicButton: View {
    var isListening: Bool
    var onPress: () -> Void
    var onRelease: () -> Void

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var isHeld = false
    @State private var breathing = false

    var body: some View {
        Image(systemName: "mic")
            .font(.system(size: 22, weight: .semibold))
            .foregroundStyle(Palette.onBrand)
            .frame(width: 56, height: 56)
            .background(Palette.brand, in: Circle())
            .shadow(color: isHeld ? .clear : Palette.pressEdge, radius: 0, y: 3)
            .offset(y: isHeld ? 3 : 0)
            .background {
                if isListening {
                    Circle()
                        .fill(Palette.brandSoft)
                        .scaleEffect(breathing && !reduceMotion ? 1.45 : 1.25)
                        .animation(.easeInOut(duration: 0.9).repeatForever(autoreverses: true), value: breathing)
                        .onAppear { breathing = true }
                        .onDisappear { breathing = false }
                }
            }
            .contentShape(Circle())
            .gesture(
                DragGesture(minimumDistance: 0)
                    .onChanged { _ in
                        guard !isHeld else { return }
                        isHeld = true
                        onPress()
                    }
                    .onEnded { _ in
                        isHeld = false
                        onRelease()
                    }
            )
            .sensoryFeedback(.impact(weight: .medium), trigger: isHeld)
            .animation(.snappy(duration: 0.12), value: isHeld)
            .accessibilityElement()
            .accessibilityLabel(isListening ? "Listening. Let go to stop" : "Hold to talk")
            .accessibilityAddTraits(.isButton)
    }
}
