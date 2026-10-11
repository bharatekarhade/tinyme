import SwiftUI

struct HomeView: View {
    @Environment(AppEnvironment.self) private var environment
    @Environment(\.scenePhase) private var scenePhase
    @State private var model: HomeModel
    @State private var showsSettings = false

    init(api: APIClient) {
        _model = State(initialValue: HomeModel(api: api))
    }

    var body: some View {
        @Bindable var model = model

        ScrollView {
            VStack(spacing: Space.s4) {
                HomeHeader(initial: profileInitial, subtitle: model.range.subtitle) {
                    showsSettings = true
                }
                .padding(.bottom, Space.s2)
                RangeSwitcher(selection: $model.range)

                // New identity per range, so every chart fills again on switch.
                Group {
                    switch model.phase {
                    case .loading:
                        ForEach(0..<2, id: \.self) { _ in
                            CardPlaceholder()
                        }
                    case .failed(let message):
                        LoadFailedCard(message: message) {
                            model.load()
                        }
                    case .loaded:
                        ForEach(model.cards) { card in
                            CardView(card: card, range: model.range)
                        }
                    }
                }
                .id(model.range)

                AddTrackerCard(ideas: model.suggestions.map(\.text))
            }
            .padding(.horizontal, Space.s4)
            .padding(.top, Space.s2)
            .padding(.bottom, Space.s6)
            .animation(.snappy(duration: 0.3), value: model.phase)
        }
        .scrollIndicators(.hidden)
        .refreshable {
            await model.reload()
        }
        .foregroundStyle(Palette.ink)
        .background(Palette.surface.ignoresSafeArea())
        .safeAreaInset(edge: .bottom) {
            HomeTabBar()
        }
        .overlay {
            SettingsSheet(isPresented: $showsSettings, session: environment.session)
        }
        .task {
            model.load()
        }
        .onChange(of: model.range) {
            model.load()
        }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active {
                model.load()
            }
        }
    }

    private var profileInitial: String {
        if case .signedIn(_, let email) = environment.session.state {
            return email.prefix(1).uppercased()
        }
        return ""
    }
}

private struct HomeHeader: View {
    var initial: String
    var subtitle: String
    var onProfile: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: Space.s2) {
                Text(Date.now, format: .dateTime.weekday(.wide).day().month(.wide))
                    .textStyle(.label)
                    .foregroundStyle(Palette.inkMuted)
                Spacer()
                Button(action: onProfile) {
                    Text(initial)
                        .textStyle(.label, weight: .semibold)
                        .foregroundStyle(Palette.onInk)
                        .frame(width: 40, height: 40)
                        .background(Palette.ink, in: Circle())
                        .frame(width: 44, height: 44)
                }
                .accessibilityLabel("Settings")
            }
            .buttonStyle(.pressable)

            Text("Hey there")
                .textStyle(.display)
                .padding(.top, Space.s2)
                .accessibilityAddTraits(.isHeader)
            Text(subtitle)
                .textStyle(.body)
                .foregroundStyle(Palette.inkMuted)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

private struct RangeSwitcher: View {
    @Binding var selection: HomeRange
    @Namespace private var thumb

    var body: some View {
        HStack(spacing: 0) {
            ForEach(HomeRange.allCases) { range in
                let isOn = range == selection
                Button {
                    withAnimation(.snappy(duration: 0.25)) { selection = range }
                } label: {
                    Text(range.title)
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
        .accessibilityLabel("Range")
    }
}

/// Stands in for a card while the first load for a range is on its way.
private struct CardPlaceholder: View {
    @State private var pulsing = false

    var body: some View {
        RoundedRectangle(cornerRadius: Radius.lg, style: .continuous)
            .fill(Palette.surfaceSunken)
            .frame(height: 220)
            .opacity(pulsing ? 0.5 : 1)
            .animation(.easeInOut(duration: 0.9).repeatForever(autoreverses: true), value: pulsing)
            .onAppear { pulsing = true }
            .accessibilityLabel("Loading your cards")
    }
}

private struct LoadFailedCard: View {
    var message: String
    var onRetry: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: Space.s3) {
            Text("Couldn't load your cards")
                .textStyle(.heading)
            Text(message)
                .textStyle(.label, weight: .regular)
                .foregroundStyle(Palette.inkMuted)
            Button(action: onRetry) {
                Text("Try again")
                    .textStyle(.label, weight: .semibold)
                    .foregroundStyle(Palette.onInk)
                    .padding(.horizontal, Space.s6)
                    .frame(height: 44)
                    .background(Palette.ink, in: Capsule())
            }
            .buttonStyle(.pressable)
        }
        .raisedCard()
    }
}

// MARK: - Add a tracker

private struct AddTrackerCard: View {
    var ideas: [String]
    var onPick: (String) -> Void = { _ in }

    var body: some View {
        VStack(alignment: .leading, spacing: Space.s3) {
            HStack(spacing: Space.s3) {
                Image(systemName: "plus")
                    .font(.system(size: 18, weight: .semibold))
                    .foregroundStyle(Palette.onBrand)
                    .frame(width: 40, height: 40)
                    .background(Palette.brand, in: Circle())
                    .accessibilityHidden(true)
                VStack(alignment: .leading, spacing: 0) {
                    Text("Track something new")
                        .textStyle(.heading)
                    Text("Just ask, and I'll build the card.")
                        .textStyle(.label, weight: .regular)
                        .foregroundStyle(Palette.inkMuted)
                }
            }

            if !ideas.isEmpty {
                FlowLayout(spacing: Space.s2) {
                    ForEach(ideas, id: \.self) { idea in
                        Button {
                            onPick(idea)
                        } label: {
                            Text("“\(idea)”")
                                .textStyle(.label)
                                .padding(.horizontal, Space.s4)
                                .frame(height: 38)
                                .background(Palette.surfaceRaised, in: Capsule())
                                .cardShadow()
                                .frame(minHeight: 44)
                        }
                        .buttonStyle(.pressable)
                    }
                }
            }
        }
        .padding(Space.s6)
        .frame(maxWidth: .infinity, alignment: .leading)
        .overlay {
            RoundedRectangle(cornerRadius: Radius.lg, style: .continuous)
                .strokeBorder(Palette.inkMuted.opacity(0.35), style: StrokeStyle(lineWidth: 1.5, dash: [6, 5]))
        }
    }
}
