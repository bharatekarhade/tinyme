import SwiftUI

extension View {
    /// Liquid Glass on iOS 26+, a frosted material on iOS 18–25.
    @ViewBuilder
    func glassSurface<S: Shape>(_ shape: S, tint: Color? = nil) -> some View {
        if #available(iOS 26, macOS 26, *) {
            glassEffect(tint.map { Glass.regular.tint($0).interactive() } ?? .regular, in: shape)
        } else {
            background {
                ZStack {
                    shape.fill(.ultraThinMaterial)
                    if let tint {
                        shape.fill(tint.opacity(0.88))
                    }
                }
                .shadow(color: .black.opacity(0.14), radius: 16, y: 12)
            }
        }
    }
}
