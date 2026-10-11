import SwiftUI

/// Floating glass bar: Home · Upcoming in one pill, the mint + as its own circle.
struct HomeTabBar: View {
    enum Tab: String, CaseIterable, Identifiable {
        case home = "Home"
        case upcoming = "Upcoming"

        var id: Self { self }

        var systemImage: String {
            switch self {
            case .home: "house"
            case .upcoming: "calendar.badge.clock"
            }
        }
    }

    var selection: Tab = .home
    var onSelect: (Tab) -> Void = { _ in }
    var onNewChat: () -> Void = {}

    var body: some View {
        HStack(spacing: 10) {
            HStack(spacing: 4) {
                ForEach(Tab.allCases) { tab in
                    let isOn = tab == selection
                    Button {
                        onSelect(tab)
                    } label: {
                        VStack(spacing: 2) {
                            Image(systemName: tab.systemImage)
                                .font(.system(size: 19, weight: isOn ? .medium : .regular))
                                .frame(height: 22)
                            Text(tab.rawValue)
                                .textStyle(.caption, weight: isOn ? .semibold : .medium)
                        }
                        .foregroundStyle(isOn ? Palette.brandStrong : Palette.ink)
                        .frame(maxWidth: .infinity)
                        .frame(height: 52)
                        .background(isOn ? Palette.brand.opacity(0.18) : .clear, in: Capsule())
                        .contentShape(Capsule())
                    }
                    .buttonStyle(.plain)
                    .accessibilityAddTraits(isOn ? .isSelected : [])
                }
            }
            .padding(6)
            .glassSurface(Capsule())

            Button(action: onNewChat) {
                Image(systemName: "plus")
                    .font(.system(size: 24, weight: .semibold))
                    .foregroundStyle(Palette.onBrand)
                    .frame(width: 64, height: 64)
            }
            .buttonStyle(.plain)
            .glassSurface(Circle(), tint: Palette.brand)
            .accessibilityLabel("New chat with TinyMe")
        }
        .padding(.horizontal, Space.s4)
        .padding(.bottom, Space.s2)
    }
}

private extension View {
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
