import SwiftUI

struct HomeView: View {
    let model: HomeModel
    let api: APIClient

    @Environment(AppEnvironment.self) private var environment
    @Environment(MainRouter.self) private var router
    @Environment(\.scenePhase) private var scenePhase
    @State private var cardToRemove: CardDTO?
    @State private var isPeriodPickerPresented = false

    var body: some View {
        @Bindable var model = model
        @Bindable var router = router

        NavigationStack(path: $router.homePath) {
            ScrollView {
                LazyVStack(spacing: 0, pinnedViews: [.sectionHeaders]) {
                    HomeHeader(initial: profileInitial, subtitle: model.range.subtitle, streak: model.streak) {
                        router.showsSettings = true
                    }
                    .padding(.horizontal, Space.s4)
                    .padding(.top, Space.s2)
                    .padding(.bottom, Space.s2)

                    Section {
                        VStack(spacing: Space.s4) {
                            // New identity per period, so every chart redraws for the selected dates.
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
                                            selectedDate: model.selectedDate,
                                            onOpen: { tap in router.open(card, range: model.range, selectedDate: model.selectedDate, tap: tap) },
                                            onRemove: { cardToRemove = card },
                                            onDrop: { droppedId in
                                                Task { await model.move(droppedId, onto: card.id) }
                                            }
                                        )
                                    }
                                }
                            }
                            .id("\(model.range.rawValue)-\(model.selectedDate.description)")

                            AddTrackerCard(ideas: model.suggestions.map(\.text)) { idea in
                                router.openChat(draft: idea)
                            }
                        }
                        .padding(.horizontal, Space.s4)
                        .padding(.top, Space.s2)
                        .padding(.bottom, Space.s6)
                        .animation(.snappy(duration: 0.3), value: model.phase)
                        .animation(.snappy(duration: 0.3), value: model.cards.map(\.id))
                    } header: {
                        VStack(spacing: Space.s2) {
                            PillSwitcher(options: HomeRange.allCases, selection: $model.range, title: \.title, label: "Range")
                            Button {
                                isPeriodPickerPresented = true
                            } label: {
                                HStack(spacing: 7) {
                                    Image(systemName: "calendar")
                                        .font(.system(size: 13, weight: .medium))
                                    Text(model.selectedPeriodLabel)
                                        .textStyle(.label, weight: .semibold)
                                        .lineLimit(1)
                                    Image(systemName: "chevron.down")
                                        .font(.system(size: 10, weight: .bold))
                                }
                                .foregroundStyle(Palette.ink)
                                .padding(.horizontal, Space.s4)
                                .frame(maxWidth: .infinity)
                                .frame(height: 40)
                                .background(Palette.surfaceSunken, in: Capsule())
                                .contentShape(Capsule())
                            }
                            .buttonStyle(.plain)
                            .accessibilityLabel("Choose \(model.range.title.lowercased()), \(model.selectedPeriodLabel)")
                        }
                        .padding(.horizontal, Space.s4)
                        .padding(.top, Space.s2)
                        .padding(.bottom, Space.s3)
                        .background {
                            Rectangle()
                                .fill(Palette.surface)
                                .ignoresSafeArea(edges: .top)
                        }
                        .overlay(alignment: .bottom) {
                            Rectangle()
                                .fill(Palette.surfaceSunken)
                                .frame(height: 1)
                        }
                        .zIndex(1)
                    }
                }
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
            .sheet(isPresented: $isPeriodPickerPresented) {
                HomePeriodPicker(model: model)
            }
            // "Home" names the back button on a drill-down; Home itself has its own header.
            .navigationTitle("Home")
            .toolbar(.hidden, for: .navigationBar)
            .navigationDestination(for: MainRouter.DrillTarget.self) { target in
                DrillDownView(target: target, api: api)
            }
        }
        .overlay(alignment: .top) {
            GeometryReader { geometry in
                Palette.surface
                    .frame(height: geometry.safeAreaInsets.top)
                    .frame(maxWidth: .infinity)
                    .offset(y: -geometry.safeAreaInsets.top)
            }
            .allowsHitTesting(false)
        }
        .tint(Palette.brandStrong)
        .task {
            model.show()
        }
        .onChange(of: model.range) {
            model.show()
        }
        .onChange(of: model.selectedDate) {
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

private struct HomePeriodPicker: View {
    let model: HomeModel
    private let options: [HomePeriodOption]
    @State private var selectedStart: LocalDay
    @State private var scrollPosition: LocalDay?
    @Environment(\.dismiss) private var dismiss

    init(model: HomeModel) {
        self.model = model
        let options = model.periodOptions
        self.options = options
        let selected = model.selectedPeriodStart
        let initial = options.first(where: { $0.start == selected })?.start
            ?? options.first?.start ?? .today()
        _selectedStart = State(initialValue: initial)
        _scrollPosition = State(initialValue: initial)
    }

    var body: some View {
        VStack(spacing: 0) {
            ZStack {
                Text("Select Time")
                    .textStyle(.title, weight: .semibold)
                    .foregroundStyle(Palette.ink)
                HStack {
                    Button {
                        dismiss()
                    } label: {
                        Image(systemName: "xmark")
                            .font(.system(size: 15, weight: .medium))
                            .foregroundStyle(Palette.brandStrong)
                            .frame(width: 44, height: 44)
                            .background(Circle().fill(.regularMaterial))
                            .overlay {
                                Circle().stroke(Palette.brandStrong.opacity(0.24), lineWidth: 1)
                            }
                    }
                    .buttonStyle(.pressable)
                    .accessibilityLabel("Close period picker")
                    Spacer()
                }
            }
            .padding(.top, Space.s4)
            .padding(.horizontal, Space.s4)

            ScrollView {
                LazyVStack(spacing: 0) {
                    ForEach(options) { option in
                        Text(option.dates)
                            .font(.system(size: 18, weight: .medium))
                            .foregroundStyle(option.start == selectedStart ? Palette.ink : Palette.inkMuted.opacity(0.52))
                            .frame(maxWidth: .infinity)
                            .frame(height: 52)
                            .background {
                                if option.start == selectedStart {
                                    RoundedRectangle(cornerRadius: 13, style: .continuous)
                                        .fill(Palette.surfaceRaised)
                                        .padding(.horizontal, Space.s6)
                                }
                            }
                            .contentShape(Rectangle())
                            .id(option.start)
                            .onTapGesture {
                                withAnimation(.snappy(duration: 0.2)) {
                                    selectedStart = option.start
                                    scrollPosition = option.start
                                }
                            }
                    }
                }
                .scrollTargetLayout()
            }
            .scrollIndicators(.hidden)
            // Keep the first and last rows centered too, while letting viewAligned snap
            // each 52-point date row to the fixed selection frame.
            .contentMargins(.vertical, 84, for: .scrollContent)
            .scrollTargetBehavior(.viewAligned)
            .scrollPosition(id: $scrollPosition, anchor: .center)
            .onAppear {
                scrollPosition = selectedStart
            }
            .onChange(of: scrollPosition) { _, newValue in
                if let newValue {
                    selectedStart = newValue
                }
            }
            .frame(height: 220)
            .mask {
                LinearGradient(
                    stops: [
                        .init(color: .clear, location: 0),
                        .init(color: .black, location: 0.24),
                        .init(color: .black, location: 0.76),
                        .init(color: .clear, location: 1)
                    ],
                    startPoint: .top,
                    endPoint: .bottom
                )
            }
            .overlay {
                RoundedRectangle(cornerRadius: 13, style: .continuous)
                    .stroke(Palette.brandStrong, lineWidth: 1.5)
                    .frame(height: 48)
                    .padding(.horizontal, Space.s6)
                    .allowsHitTesting(false)
            }

            Button {
                if let option = options.first(where: { $0.start == selectedStart }) {
                    model.select(option)
                }
                dismiss()
            } label: {
                Text("Confirm")
                    .textStyle(.heading, weight: .semibold)
                    .foregroundStyle(Palette.onBrand)
                    .frame(maxWidth: 250)
                    .frame(height: 54)
                    .background(Palette.brand, in: Capsule())
            }
            .buttonStyle(.pressable)
            .frame(maxWidth: .infinity)
            .padding(.bottom, Space.s6)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .foregroundStyle(Palette.ink)
        .presentationDetents([.height(470)])
        .presentationDragIndicator(.visible)
        .presentationCornerRadius(34)
        .presentationBackground(.regularMaterial)
    }
}

/// A card on Home. Tap it for the entries behind it. Your own cards can also be removed (long-press)
/// and dragged onto another to reorder; Journal stays put.
private struct HomeCardRow: View {
    let card: CardDTO
    let range: HomeRange
    let selectedDate: LocalDay
    var onOpen: (CardTap) -> Void
    var onRemove: () -> Void
    var onDrop: (String) -> Void

    @State private var isDropTarget = false

    private var shape: RoundedRectangle { RoundedRectangle(cornerRadius: Radius.lg, style: .continuous) }

    var body: some View {
        if card.system == true {
            CardView(card: card, range: range, selectedDate: selectedDate, onOpen: onOpen)
        } else {
            CardView(card: card, range: range, selectedDate: selectedDate, onOpen: onOpen)
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
    var streak: JournalStreak?
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
            if let streak {
                StreakStrip(streak: streak)
                    .padding(.top, Space.s3)
                    .transition(.opacity)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .animation(.snappy(duration: 0.3), value: streak)
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
