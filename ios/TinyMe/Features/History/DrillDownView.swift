import SwiftUI

/// The entries behind a card's number. Opened by tapping a card, or one segment, bar or dot of it.
struct DrillDownView: View {
    @State private var model: EntryListModel

    init(target: MainRouter.DrillTarget, api: APIClient) {
        _model = State(initialValue: EntryListModel(
            api: api,
            source: .card(id: target.cardId, range: target.range, block: target.block, item: target.item, day: target.day, key: target.key)
        ))
    }

    var body: some View {
        EntryListView(model: model) {
            if let header = model.header {
                DrillDownHeader(header: header, onClearFilter: model.clearFilter)
            }
        } empty: {
            Text("No entries \(model.header?.period.lowercasedFirst ?? "here") yet.")
                .textStyle(.body)
                .foregroundStyle(Palette.inkMuted)
        }
        .navigationTitle(model.header?.title ?? "")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar(.visible, for: .navigationBar)
        .toolbarBackground(Palette.surface, for: .navigationBar)
        .task {
            if !model.hasLoaded {
                model.reload()
            }
        }
    }
}

/// The total the list adds up to, the period it covers, and the segment or day it is narrowed to.
private struct DrillDownHeader: View {
    let header: EntryListModel.Header
    var onClearFilter: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: Space.s2) {
            if let total = header.total {
                Text(Formatters.value(total, unit: header.unit))
                    .textStyle(.numeral)
                    .lineLimit(1)
                    .minimumScaleFactor(0.6)
                    .contentTransition(.numericText(value: total))
            }
            HStack(spacing: Space.s2) {
                Text([header.period, header.dates].compactMap { $0 }.joined(separator: " · "))
                    .textStyle(.label)
                    .foregroundStyle(Palette.inkMuted)
                if let filter = header.filter {
                    Button(action: onClearFilter) {
                        HStack(spacing: 6) {
                            Text(filter)
                                .textStyle(.caption, weight: .semibold)
                            Image(systemName: "xmark")
                                .font(.system(size: 9, weight: .bold))
                        }
                        .foregroundStyle(Palette.onInk)
                        .padding(.horizontal, Space.s3)
                        .frame(height: 28)
                        .background(Palette.ink, in: Capsule())
                        .frame(minHeight: 44)
                    }
                    .buttonStyle(.pressable)
                    .accessibilityLabel("Showing \(filter) only. Tap to show everything")
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .animation(.snappy(duration: 0.25), value: header)
    }
}
