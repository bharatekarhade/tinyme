import SwiftUI

/// Everything you've logged, newest first, with a search box and a chip per kind.
struct HistoryView: View {
    let model: EntryListModel
    let api: APIClient

    @Environment(MainRouter.self) private var router
    @State private var kinds: [KindDTO] = []
    @State private var search = ""
    @FocusState private var searchFocused: Bool

    private var isFiltered: Bool {
        model.kind != nil || !model.query.isEmpty
    }

    var body: some View {
        EntryListView(model: model) {
            VStack(alignment: .leading, spacing: Space.s4) {
                Text("History")
                    .textStyle(.display)
                    .padding(.top, Space.s2)
                    .accessibilityAddTraits(.isHeader)
                searchField
                KindChips(kinds: kinds, selection: model.kind) { kind in
                    model.setKind(kind)
                }
                // The chips run edge to edge, past the list's side padding.
                .padding(.horizontal, -Space.s4)
            }
        } empty: {
            emptyState
        }
        .onChange(of: search) { _, text in
            model.setQuery(text.trimmingCharacters(in: .whitespaces))
        }
        .task {
            if router.historyNeedsReset {
                router.historyNeedsReset = false
                search = ""
                model.resetFilters()
            } else {
                search = model.query
            }
            if router.historyIsStale || !model.hasLoaded {
                router.historyIsStale = false
                model.reload()
            }
            // Loaded each time History appears, so the counts follow what was just logged.
            if let loaded: [KindDTO] = try? await api.get(.kinds) {
                kinds = loaded
            }
        }
    }

    private var searchField: some View {
        HStack(spacing: Space.s2) {
            Image(systemName: "magnifyingglass")
                .font(.system(size: 15, weight: .medium))
                .foregroundStyle(Palette.inkMuted)
                .accessibilityHidden(true)
            TextField("Search your log", text: $search, prompt: Text("Search your log").foregroundStyle(Palette.inkMuted))
                .textStyle(.body)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .submitLabel(.search)
                .focused($searchFocused)
            if !search.isEmpty {
                Button {
                    search = ""
                } label: {
                    Image(systemName: "xmark.circle.fill")
                        .font(.system(size: 17))
                        .foregroundStyle(Palette.inkMuted)
                        .frame(width: 44, height: 44)
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Clear search")
            }
        }
        .padding(.leading, Space.s4)
        .padding(.trailing, search.isEmpty ? Space.s4 : 2)
        .frame(height: 48)
        .background(Palette.surfaceSunken, in: Capsule())
    }

    @ViewBuilder
    private var emptyState: some View {
        VStack(spacing: Space.s3) {
            if isFiltered {
                Text(model.query.isEmpty ? "Nothing of this kind yet." : "No matches for “\(model.query)”.")
                    .textStyle(.body)
                    .foregroundStyle(Palette.inkMuted)
                    .multilineTextAlignment(.center)
                emptyAction(model.query.isEmpty ? "Show everything" : "Clear search") {
                    search = ""
                    model.resetFilters()
                }
            } else {
                Text("Nothing logged yet. Tell TinyMe what you did.")
                    .textStyle(.body)
                    .foregroundStyle(Palette.inkMuted)
                    .multilineTextAlignment(.center)
                emptyAction("Open chat") {
                    router.openChat()
                }
            }
        }
        .padding(.horizontal, Space.s6)
    }

    private func emptyAction(_ title: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(title)
                .textStyle(.label, weight: .semibold)
                .foregroundStyle(Palette.onInk)
                .padding(.horizontal, Space.s6)
                .frame(height: 44)
                .background(Palette.ink, in: Capsule())
        }
        .buttonStyle(.pressable)
    }
}

/// "All" plus one chip per kind logged in the last 90 days. One at a time; the chosen one is mint.
private struct KindChips: View {
    let kinds: [KindDTO]
    let selection: String?
    var onSelect: (String?) -> Void

    var body: some View {
        ScrollView(.horizontal) {
            HStack(spacing: Space.s2) {
                chip("All", count: nil, kind: nil)
                ForEach(kinds) { kind in
                    chip(kind.label, count: kind.count, kind: kind.kind)
                }
            }
            .padding(.horizontal, Space.s4)
        }
        .scrollIndicators(.hidden)
    }

    private func chip(_ label: String, count: Int?, kind: String?) -> some View {
        let isOn = kind == selection
        return Button {
            onSelect(kind)
        } label: {
            HStack(spacing: 6) {
                Text(label)
                    .textStyle(.label, weight: isOn ? .semibold : .medium)
                if let count {
                    Text("\(count)")
                        .textStyle(.caption)
                        .opacity(0.7)
                }
            }
            .foregroundStyle(isOn ? Palette.onBrand : Palette.ink)
            .padding(.horizontal, Space.s4)
            .frame(height: 40)
            .background(isOn ? Palette.brand : Palette.surfaceRaised, in: Capsule())
            .overlay {
                if !isOn {
                    Capsule().strokeBorder(Palette.line, lineWidth: 1)
                }
            }
            .frame(minHeight: 44)
        }
        .buttonStyle(.pressable)
        .accessibilityLabel(count.map { "\(label), \($0) entries" } ?? label)
        .accessibilityAddTraits(isOn ? .isSelected : [])
    }
}
