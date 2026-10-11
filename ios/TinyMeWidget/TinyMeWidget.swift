import SwiftUI
import WidgetKit

@main
struct TinyMeWidgetBundle: WidgetBundle {
    init() {
        Fonts.register()
    }

    var body: some Widget {
        StatsWidget()
    }
}

/// "Journal something?" on top, and under it your stats, one at a time.
struct StatsWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: WidgetSnapshot.kind, provider: StatsProvider()) { entry in
            StatsWidgetView(entry: entry)
                .containerBackground(Palette.surface, for: .widget)
        }
        .configurationDisplayName("TinyMe")
        .description("Journal in one tap, and see how things are going.")
        .supportedFamilies([.systemMedium])
    }
}

struct StatsEntry: TimelineEntry {
    let date: Date
    /// Nil until the app has loaded Home once.
    let stat: WidgetSnapshot.Stat?
    /// Which stat this is, so the view can tell one from the next and slide between them.
    let index: Int
}

struct StatsProvider: TimelineProvider {
    /// How long each stat stays up. iOS may redraw less often than this when it is saving power.
    private let step: TimeInterval = 10
    private let entriesPerTimeline = 180   // half an hour, then iOS asks again

    func placeholder(in context: Context) -> StatsEntry {
        StatsEntry(date: .now, stat: .placeholder, index: 0)
    }

    func getSnapshot(in context: Context, completion: @escaping @Sendable (StatsEntry) -> Void) {
        let stats = WidgetSnapshot.load()?.stats ?? []
        completion(StatsEntry(date: .now, stat: stats.first ?? (context.isPreview ? .placeholder : nil), index: 0))
    }

    func getTimeline(in context: Context, completion: @escaping @Sendable (Timeline<StatsEntry>) -> Void) {
        let stats = WidgetSnapshot.load()?.stats ?? []
        let now = Date.now
        guard stats.count > 1 else {
            // Nothing to rotate through: one entry, checked again in a while.
            let entry = StatsEntry(date: now, stat: stats.first, index: 0)
            completion(Timeline(entries: [entry], policy: .after(now.addingTimeInterval(30 * 60))))
            return
        }
        let entries = (0..<entriesPerTimeline).map { offset in
            StatsEntry(date: now.addingTimeInterval(Double(offset) * step), stat: stats[offset % stats.count], index: offset)
        }
        completion(Timeline(entries: entries, policy: .atEnd))
    }
}

struct StatsWidgetView: View {
    let entry: StatsEntry

    var body: some View {
        VStack(spacing: 10) {
            Link(destination: WidgetSnapshot.chatURL) {
                HStack(spacing: Space.s2) {
                    Face(size: 26)
                    Text("Journal something?")
                        .textStyle(.label)
                        .foregroundStyle(Palette.inkMuted)
                    Spacer(minLength: 0)
                    Image(systemName: "mic")
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundStyle(Palette.onBrand)
                        .frame(width: 32, height: 32)
                        .background(Palette.brand, in: Circle())
                }
                .padding(.leading, 10)
                .padding(.trailing, 6)
                .frame(height: 44)
                .background(Palette.surfaceRaised, in: Capsule())
            }
            .accessibilityLabel("Journal something. Opens TinyMe chat")

            Group {
                if let stat = entry.stat {
                    StatRow(stat: stat)
                } else {
                    Text("Open TinyMe to see how things are going.")
                        .textStyle(.label, weight: .regular)
                        .foregroundStyle(Palette.inkMuted)
                        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
                }
            }
            // A new identity per entry, so each stat slides up to replace the last.
            .id(entry.index)
            .transition(.push(from: .bottom))
        }
        .foregroundStyle(Palette.ink)
    }
}

private struct StatRow: View {
    let stat: WidgetSnapshot.Stat

    private var colors: (strong: Color, soft: Color) {
        switch stat.accent {
        case "coral": (Palette.accent, Palette.accentSoft)
        case "sunny": (Palette.sunny, Palette.sunnySoft)
        case "ink": (Palette.ink, Palette.surfaceSunken)
        default: (Palette.brand, Palette.brandSoft)
        }
    }

    var body: some View {
        HStack(alignment: .center, spacing: Space.s3) {
            Image(systemName: stat.symbol)
                .font(.system(size: 17))
                .frame(width: 40, height: 40)
                .background(colors.soft, in: RoundedRectangle(cornerRadius: Radius.sm, style: .continuous))
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 0) {
                Text(stat.title)
                    .textStyle(.caption, weight: .semibold)
                    .foregroundStyle(Palette.inkMuted)
                HStack(alignment: .firstTextBaseline, spacing: 6) {
                    Text(stat.value)
                        .textStyle(.title)
                        .lineLimit(1)
                        .minimumScaleFactor(0.7)
                    Text(stat.caption)
                        .textStyle(.caption)
                        .foregroundStyle(Palette.inkMuted)
                        .lineLimit(1)
                }
            }
            Spacer(minLength: 0)
            if let change = stat.change {
                Text(change)
                    .textStyle(.caption, weight: .semibold)
                    .lineLimit(2)
                    .multilineTextAlignment(.trailing)
                    .padding(.horizontal, Space.s2)
                    .padding(.vertical, 4)
                    .background(Palette.surfaceSunken, in: RoundedRectangle(cornerRadius: Radius.sm, style: .continuous))
                    .frame(maxWidth: 110, alignment: .trailing)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
        .accessibilityElement(children: .combine)
    }
}

/// TinyMe's face, as in the app.
private struct Face: View {
    var size: CGFloat

    var body: some View {
        HStack(spacing: size * 0.16) {
            Capsule().frame(width: size * 0.125, height: size * 0.19)
            Capsule().frame(width: size * 0.125, height: size * 0.19)
        }
        .foregroundStyle(Palette.onBrand)
        .frame(width: size, height: size)
        .background(Palette.brand, in: Circle())
    }
}

extension WidgetSnapshot.Stat {
    /// Shown in the widget gallery before there is real data.
    static let placeholder = WidgetSnapshot.Stat(
        title: "Journal", symbol: "text.book.closed", accent: "mint",
        value: "15", caption: "entries this week", change: "↑ 20% vs last week"
    )
}
