import SwiftUI

private struct GrowIn: ViewModifier {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    var shown: Bool
    var delay: Double

    func body(content: Content) -> some View {
        content.animation(reduceMotion ? nil : .spring(duration: 0.8, bounce: 0.2).delay(delay), value: shown)
    }
}

extension View {
    /// Animates whatever depends on `shown` with the chart spring (instant when Reduce Motion is on).
    func growIn(_ shown: Bool, delay: Double = 0) -> some View {
        modifier(GrowIn(shown: shown, delay: delay))
    }

    /// Flips `shown` the first time the view scrolls into sight, so charts fill as you reach them.
    func revealOnScroll(_ shown: Binding<Bool>) -> some View {
        onScrollVisibilityChange(threshold: 0.35) { visible in
            if visible {
                shown.wrappedValue = true
            }
        }
    }
}

/// Controls dip slightly while pressed.
struct PressableStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed ? 0.95 : 1)
            .animation(.snappy(duration: 0.15), value: configuration.isPressed)
    }
}

extension ButtonStyle where Self == PressableStyle {
    static var pressable: PressableStyle { PressableStyle() }
}
