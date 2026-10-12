import SwiftUI

struct HomeView: View {
    let model: HomeModel
    let api: APIClient

    @Environment(AppEnvironment.self) private var environment
    @Environment(MainRouter.self) private var router
    @Environment(\.scenePhase) private var scenePhase
    @State private var cardToRemove: CardDTO?

    var body: some View {
        @Bindable var model = model
        @Bindable var router = router

        NavigationStack(path: $router.homePath) {
            ScrollView {
                VStack(spacing: Space.s4) {
                    HomeHeader(initial: profileInitial, subtitle: model.range.subtitle) {
                        router.showsSettings = true
                    }
                    .padding(.bottom, Space.s2)
                    PillSwitcher(options: HomeRange.allCases, selection: $model.range, title: \.title, label: "Range")

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
                                HomeCardRow(
                                    card: card,
                                    range: model.range,
                                    onOpen: { tap in router.open(card, range: model.range, tap: tap) },
                                    onRemove: { cardToRemove = card },
                                    onDrop: { droppedId in
                                        Task { await model.move(droppedId, onto: card.id) }
                                    }
                                )
                            }
                        }
                    }
                    .id(model.range)

                    AddTrackerCard(ideas: model.suggestions.map(\.text)) { idea in
                        router.openChat(draft: idea)
                    }
                }
                .padding(.horizontal, Space.s4)
                .padding(.top, Space.s2)
                .padding(.bottom, Space.s6)
                .animation(.snappy(duration: 0.3), value: model.phase)
                .animation(.snappy(duration: 0.3), value: model.cards.map(\.id))
            }
            .scrollIndicators(.hidden)
            .refreshable {
                await model.reload()
            }
            .foregroundStyle(Palette.ink)
            .background(Palette.surface.ignoresSafeArea())
            .overlay(alignment: .top) {
                if let banner = model.banner {
                    Label(banner, systemImage: "exclamationmark.circle")
                        .textStyle(.label)
                        .foregroundStyle(Palette.onAccentSoft)
                        .padding(.horizontal, Space.s4)
                        .padding(.vertical, Space.s3)
                        .background(Palette.accentSoft, in: Capsule())
                        .cardShadow()
                        .padding(.top, Space.s2)
                        .transition(.move(edge: .top).combined(with: .opacity))
                }
            }
            .animation(.snappy(duration: 0.3), value: model.banner)
            .confirmationDialog(
                "Remove \(cardToRemove?.title ?? "this card") from Home?",
                isPresented: Binding(get: { cardToRemove != nil }, set: { if !$0 { cardToRemove = nil } }),
                titleVisibility: .visible,
                presenting: cardToRemove
            ) { card in
                Button("Remove", role: .destructive) {
                    Task { await model.remove(card) }
                }
                Button("Cancel", role: .cancel) {}
            } message: { _ in
                Text("What you've logged stays. Ask me any time to build the card again.")
            }
            // "Home" names the back button on a drill-down; Home itself has its own header.
            .navigationTitle("Home")
            .toolbar(.hidden, for: .navigationBar)
            .navigationDestination(for: MainRouter.DrillTarget.self) { target in
                DrillDownView(target: target, api: api)
            }
        }
        .tint(Palette.brandStrong)
        .task {
            model.show()
        }
        .onChange(of: model.range) {
            model.show()
        }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active {
                model.refresh()
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

/// A card on Home. Tap it for the entries behind it. Your own cards can also be removed (long-press)
/// and dragged onto another to reorder; Journal stays put.
private struct HomeCardRow: View {
    let card: CardDTO
    let range: HomeRange
    var onOpen: (CardTap) -> Void
    var onRemove: () -> Void
    var onDrop: (String) -> Void

    @State private var isDropTarget = false

    private var shape: RoundedRectangle { RoundedRectangle(cornerRadius: Radius.lg, style: .continuous) }

    var body: some View {
        if card.system == true {
            CardView(card: card, range: range, onOpen: onOpen)
        } else {
            CardView(card: card, range: range, onOpen: onOpen)
                .contentShape(.contextMenuPreview, shape)
                .contentShape(.dragPreview, shape)
                .contextMenu {
                    Button("Remove from Home", systemImage: "minus.circle", role: .destructive, action: onRemove)
                }
                .draggable(card.id)
                .dropDestination(for: String.self) { ids, _ in
                    guard let id = ids.first else { return false }
                    onDrop(id)
                    return true
                } isTargeted: { isDropTarget = $0 }
                .overlay {
                    if isDropTarget {
                        shape.strokeBorder(Palette.brandStrong, lineWidth: 2)
                    }
                }
        }
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
