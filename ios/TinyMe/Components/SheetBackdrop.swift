import SwiftUI

/// A lightly frosted, brand-tinted surface shared by the app's detail sheets.
struct SheetBackdrop: View {
    var body: some View {
        Rectangle()
            .fill(.ultraThinMaterial)
            .overlay {
                LinearGradient(
                    stops: [
                        .init(color: Palette.brandSoft.opacity(0.58), location: 0),
                        .init(color: Palette.surface.opacity(0.94), location: 0.32),
                        .init(color: Palette.surface.opacity(0.98), location: 1)
                    ],
                    startPoint: .top,
                    endPoint: .bottom
                )
            }
            .ignoresSafeArea()
    }
}
