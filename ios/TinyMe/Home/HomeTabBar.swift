import SwiftUI

/// Floating glass navigation for Home, Upcoming and G Scan, with chat always one tap away.
struct HomeTabBar: View {
    enum Tab: String, CaseIterable, Identifiable {
        case home = "Home"
        case upcoming = "Upcoming"
        case gscan = "G Scan"

        var id: Self { self }

        var systemImage: String {
            switch self {
            case .home: "house"
            case .upcoming: "calendar"
            case .gscan: "envelope.badge"
            }
        }
    }

    var selection: Tab = .home
    /// How many things wait on a tab; shown as a small count on its icon.
    var badges: [Tab: Int] = [:]
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
                                .overlay(alignment: .topTrailing) {
                                    if let count = badges[tab], count > 0 {
                                        Text(count > 99 ? "99+" : "\(count)")
                                            .font(.system(size: 11, weight: .bold))
                                            .foregroundStyle(Palette.onBrand)
                                            .padding(.horizontal, 5)
                                            .frame(minWidth: 18, minHeight: 18)
                                            .background(Palette.accent, in: Capsule())
                                            .offset(x: 12, y: -6)
                                            .accessibilityHidden(true)
                                    }
                                }
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
                    .accessibilityValue(badges[tab].map { $0 > 0 ? "\($0) waiting" : "" } ?? "")
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
