import SwiftUI

/// Floating glass bar: Home · History in one pill, the mint + as its own circle.
/// Upcoming joins them once there is a calendar to show.
struct HomeTabBar: View {
    enum Tab: String, CaseIterable, Identifiable {
        case home = "Home"
        case history = "History"

        var id: Self { self }

        var systemImage: String {
            switch self {
            case .home: "house"
            case .history: "clock"   // symmetric, so it sits centred over its label
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
