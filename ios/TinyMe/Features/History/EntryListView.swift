import SwiftUI

/// Entries grouped by day, newest first. Used by History and by a card's drill-down;
/// each supplies what goes above the list and what to say when it is empty.
struct EntryListView<Header: View, Empty: View>: View {
    let model: EntryListModel
    @ViewBuilder var header: () -> Header
    @ViewBuilder var empty: () -> Empty

    @Environment(MainRouter.self) private var router

    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: Space.s4) {
                header()

                switch model.phase {
                case .loading:
                    ForEach(0..<3, id: \.self) { _ in
                        RoundedRectangle(cornerRadius: Radius.lg, style: .continuous)
                            .fill(Palette.surfaceSunken)
                            .frame(height: 72)
                    }
                    .accessibilityLabel("Loading")
                case .failed(let message):
                    failure(message)
                case .loaded:
                    if model.isEmpty {
                        empty()
                            .frame(maxWidth: .infinity)
                            .padding(.top, Space.s6)
                    } else {
                        ForEach(model.sections) { section in
                            self.section(section)
                        }
                        if model.isLoadingMore {
                            ProgressView()
                                .tint(Palette.inkMuted)
                                .frame(maxWidth: .infinity)
                                .padding(.vertical, Space.s2)
                        }
                    }
                }
            }
            .padding(.horizontal, Space.s4)
            .padding(.top, Space.s2)
            .padding(.bottom, Space.s6)
        }
        .scrollIndicators(.hidden)
        .scrollDismissesKeyboard(.interactively)
        .refreshable {
            await model.refresh()
        }
        .foregroundStyle(Palette.ink)
        .background(Palette.surface.ignoresSafeArea())
    }

    private func section(_ section: EntryListModel.DaySection) -> some View {
        VStack(alignment: .leading, spacing: Space.s2) {
            Text("\(section.title) · \(section.total.map { Formatters.value($0, unit: model.unit) } ?? "\(section.entries.count)")")
                .textStyle(.label, weight: .semibold)
                .foregroundStyle(Palette.inkMuted)
                .padding(.horizontal, 4)
                .accessibilityAddTraits(.isHeader)

            VStack(spacing: 0) {
                ForEach(Array(section.entries.enumerated()), id: \.element.id) { index, entry in
                    if index > 0 {
                        Rectangle()
                            .fill(Palette.line)
                            .frame(height: 1)
                    }
                    Button {
                        router.show(entry)
                    } label: {
                        EntryRow(entry: entry)
                    }
                    .buttonStyle(.plain)
                    .onAppear {
                        model.loadMoreIfNeeded(entry)
                    }
                }
            }
            .padding(.horizontal, Space.s4)
            .background {
                RoundedRectangle(cornerRadius: Radius.lg, style: .continuous)
                    .fill(Palette.surfaceRaised)
                    .cardShadow()
            }
        }
    }

    private func failure(_ message: String) -> some View {
        VStack(alignment: .leading, spacing: Space.s3) {
            Text("Couldn't load this")
                .textStyle(.heading)
            Text(message)
                .textStyle(.label, weight: .regular)
                .foregroundStyle(Palette.inkMuted)
            Button {
                model.reload()
            } label: {
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
