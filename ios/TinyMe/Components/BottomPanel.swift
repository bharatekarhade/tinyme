import SwiftUI

/// A panel that rises from the bottom over a dimmed screen: drag it down, tap outside, or close it from inside.
/// Drawn by the app so it sits flush with the screen edges on every iOS version.
struct BottomPanel<Content: View>: View {
    @Binding var isPresented: Bool
    @ViewBuilder var content: () -> Content

    @State private var dragOffset: CGFloat = 0

    var body: some View {
        GeometryReader { geometry in
            ZStack(alignment: .bottom) {
                if isPresented {
                    Palette.scrim
                        .ignoresSafeArea()
                        .onTapGesture { isPresented = false }
                        .transition(.opacity)
                        .accessibilityHidden(true)
                    panel(maxHeight: geometry.size.height * 0.88)
                        .offset(y: dragOffset)
                        .gesture(dragToDismiss)
                        .transition(.offset(y: 900))
                        .zIndex(1)
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottom)
        }
        .animation(.snappy(duration: 0.32), value: isPresented)
    }

    private func panel(maxHeight: CGFloat) -> some View {
        VStack(spacing: Space.s3) {
            Capsule()
                .fill(Palette.inkMuted.opacity(0.3))
                .frame(width: 40, height: 5)
                .accessibilityHidden(true)
            // As tall as its content, and scrolling only when that is more than fits.
            ViewThatFits(in: .vertical) {
                content()
                ScrollView {
                    content()
                }
                .scrollIndicators(.hidden)
            }
        }
        .padding(.horizontal, Space.s4)
        .padding(.top, 10)
        .padding(.bottom, Space.s4)
        .frame(maxWidth: .infinity)
        .frame(maxHeight: maxHeight, alignment: .top)
        .fixedSize(horizontal: false, vertical: true)
        .foregroundStyle(Palette.ink)
        .background {
            UnevenRoundedRectangle(topLeadingRadius: 36, topTrailingRadius: 36, style: .continuous)
                .fill(Palette.surface)
                .shadow(color: .black.opacity(0.18), radius: 20, y: -12)
                .ignoresSafeArea(edges: .bottom)
        }
        .accessibilityAddTraits(.isModal)
    }

    private var dragToDismiss: some Gesture {
        DragGesture()
            .onChanged { dragOffset = max(0, $0.translation.height) }
            .onEnded { drag in
                if drag.translation.height > 120 || drag.predictedEndTranslation.height > 320 {
                    isPresented = false
                }
                withAnimation(.snappy(duration: 0.25)) { dragOffset = 0 }
            }
    }
}
