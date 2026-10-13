import SwiftUI

struct UpcomingView: View {
    let model: UpcomingModel
    var onOpenChat: (String) -> Void = { _ in }

    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.openURL) private var openURL
    @State private var planToDelete: OccurrenceDTO?

    var body: some View {
        @Bindable var model = model

        ScrollViewReader { proxy in
            ScrollView {
                VStack(alignment: .leading, spacing: Space.s6) {
                    header
                    weekStrip
                    if let next = model.upcoming?.nextUp {
                        nextUp(next)
                    }

                    switch model.phase {
                    case .loading where model.upcoming == nil:
                        ProgressView()
                            .tint(Palette.brandStrong)
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, Space.s6)
                    case .failed(let message) where model.upcoming == nil:
                        loadError(message)
                    default:
                        if let message = model.errorMessage {
                            Label(message, systemImage: "exclamationmark.circle")
                                .textStyle(.caption)
                                .foregroundStyle(Palette.danger)
                        }
                        if model.selectedItems.isEmpty && model.laterItems.isEmpty && model.overdueItems.isEmpty {
                            emptyState
                        } else {
                            selectedDaySection
                            if !model.overdueItems.isEmpty {
                                overdueSection
                            }
                            if !model.laterItems.isEmpty {
                                laterSection
                            }
                        }
                    }
                }
                .padding(.horizontal, Space.s4)
                .padding(.top, Space.s3)
                .padding(.bottom, 110)
            }
            .scrollIndicators(.hidden)
            .refreshable { await model.refresh() }
            .background(Palette.surface.ignoresSafeArea())
            .foregroundStyle(Palette.ink)
            .onChange(of: model.selectedDay) { _, day in
                withAnimation(.snappy(duration: 0.28)) {
                    proxy.scrollTo("day-\(day.description)", anchor: .top)
                }
            }
        }
        .onAppear { model.load() }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active { model.invalidate() }
        }
        .confirmationDialog(
            "Delete \(planToDelete?.title ?? "this plan")?",
            isPresented: Binding(get: { planToDelete != nil }, set: { if !$0 { planToDelete = nil } }),
            titleVisibility: .visible,
            presenting: planToDelete
        ) { item in
            Button("Delete plan", role: .destructive) { model.delete(item) }
            Button("Cancel", role: .cancel) {}
        } message: { _ in
            Text("This removes the plan and its future occurrences.")
        }
    }

    private var header: some View {
        VStack(alignment: .leading, spacing: 3) {
            Text("Upcoming")
                .textStyle(.display)
                .accessibilityAddTraits(.isHeader)
            Text("\(model.upcoming?.items.count ?? 0) \(model.weekCountLabel)")
                .textStyle(.body)
                .foregroundStyle(Palette.inkMuted)
        }
        .padding(.top, Space.s2)
    }

    private var weekStrip: some View {
        HStack(spacing: 5) {
            ForEach(0..<7, id: \.self) { offset in
                if let day = Self.adding(offset, to: model.weekStart) {
                    let summary = model.weekDays.first { $0.day == day }
                    let selected = day == model.selectedDay
                    let today = day == (model.upcoming?.today ?? .today())
                    Button { model.select(day) } label: {
                        VStack(spacing: 7) {
                            Text(Self.weekday(day))
                                .textStyle(.caption, weight: selected ? .semibold : .medium)
                                .foregroundStyle(selected ? Palette.ink : Palette.inkMuted)
                            Text("\(day.day)")
                                .textStyle(.label, weight: selected || today ? .semibold : .medium)
                                .foregroundStyle(selected || today ? Palette.onInk : Palette.ink)
                                .frame(width: 38, height: 38)
                                .background(selected || today ? Palette.ink : .clear, in: Circle())
                            Circle()
                                .fill((summary?.count ?? 0) > 0 ? Palette.brandStrong : .clear)
                                .frame(width: 5, height: 5)
                        }
                        .frame(maxWidth: .infinity)
                        .frame(height: 83)
                        .contentShape(RoundedRectangle(cornerRadius: Radius.md))
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("\(Self.fullDate(day)), \(summary?.count ?? 0) plans")
                    .accessibilityAddTraits(selected ? .isSelected : [])
                }
            }
        }
        .padding(.horizontal, Space.s2)
        .padding(.vertical, Space.s2)
        .background(Palette.surfaceRaised, in: RoundedRectangle(cornerRadius: Radius.lg, style: .continuous))
        .gesture(DragGesture(minimumDistance: 24).onEnded { value in
            if value.translation.width < -45 {
                model.moveWeek(by: 1)
            } else if value.translation.width > 45, !model.isCurrentWeek {
                model.moveWeek(by: -1)
            }
        })
        .accessibilityHint("Swipe left or right to change week")
    }

    @ViewBuilder
    private func nextUp(_ item: OccurrenceDTO) -> some View {
        VStack(alignment: .leading, spacing: Space.s3) {
            HStack(spacing: 7) {
                Image(systemName: "sparkle")
                Text("NEXT UP")
                    .tracking(1.1)
            }
            .font(.system(size: 11, weight: .bold))
            .foregroundStyle(Palette.onInk.opacity(0.66))

            HStack(alignment: .top, spacing: Space.s3) {
                VStack(alignment: .leading, spacing: 5) {
                    Text(item.title)
                        .textStyle(.title, weight: .semibold)
                        .foregroundStyle(Palette.onInk)
                        .lineLimit(2)
                    if let place = item.place, !place.isEmpty {
                        Label(place, systemImage: "mappin.and.ellipse")
                            .textStyle(.caption)
                            .foregroundStyle(Palette.onInk.opacity(0.72))
                            .lineLimit(1)
                    }
                }
                Spacer(minLength: 2)
                TimelineView(.periodic(from: .now, by: 60)) { context in
                    Text(Self.relativeTime(item.startsAt, now: context.date))
                        .textStyle(.label, weight: .semibold)
                        .foregroundStyle(Palette.ink)
                        .padding(.horizontal, 10)
                        .frame(height: 30)
                        .background(Palette.brand, in: Capsule())
                        .fixedSize()
                }
            }

            HStack(spacing: Space.s2) {
                if let place = item.place, !place.isEmpty {
                    Button {
                        if let url = Self.mapsURL(place) { openURL(url) }
                    } label: {
                        Label("Directions", systemImage: "arrow.up.right")
                            .font(.system(size: 13, weight: .semibold))
                            .foregroundStyle(Palette.onInk)
                            .padding(.horizontal, 12)
                            .frame(height: 36)
                            .background(Palette.onInk.opacity(0.12), in: Capsule())
                    }
                    .buttonStyle(.plain)
                }
                Button {
                    onOpenChat("I'm running late for \(item.title)")
                } label: {
                    Label("Running late", systemImage: "figure.walk")
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundStyle(Palette.onInk)
                        .padding(.horizontal, 12)
                        .frame(height: 36)
                        .background(Palette.onInk.opacity(0.12), in: Capsule())
                }
                .buttonStyle(.plain)
            }
        }
        .padding(Space.s4)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Palette.ink, in: RoundedRectangle(cornerRadius: Radius.lg, style: .continuous))
        .accessibilityElement(children: .contain)
    }

    private var selectedDaySection: some View {
        VStack(alignment: .leading, spacing: Space.s3) {
            SectionTitle(
                title: selectedDayTitle,
                subtitle: model.selectedItems.isEmpty ? "Nothing on the calendar" : "\(model.selectedItems.count) planned"
            )
            VStack(spacing: 0) {
                let items = Self.sortedDayItems(model.selectedItems)
                let markerIndex = Self.nowMarkerIndex(items, now: model.upcoming?.now, today: model.upcoming?.today, day: model.selectedDay)
                ForEach(Array(items.enumerated()), id: \.element.id) { index, item in
                    if index == markerIndex { NowMarker() }
                    occurrenceRow(item)
                    if index < items.count - 1 { Divider().overlay(Palette.line).padding(.leading, 54) }
                }
                if markerIndex == items.count { NowMarker() }
            }
            .padding(.horizontal, Space.s4)
            .background(Palette.surfaceRaised, in: RoundedRectangle(cornerRadius: Radius.lg, style: .continuous))
            .id("day-\(model.selectedDay.description)")
        }
    }

    private var overdueSection: some View {
        VStack(alignment: .leading, spacing: Space.s3) {
            SectionTitle(title: "Overdue", subtitle: "Still on your list")
            VStack(spacing: 0) {
                ForEach(Array(model.overdueItems.enumerated()), id: \.element.id) { index, item in
                    HStack(alignment: .top, spacing: Space.s2) {
                        Text("From \(item.day.date()?.formatted(.dateTime.weekday(.abbreviated)) ?? "earlier")")
                            .textStyle(.caption, weight: .semibold)
                            .foregroundStyle(Palette.accent)
                            .frame(width: 58, alignment: .leading)
                        occurrenceRow(item)
                    }
                    if index < model.overdueItems.count - 1 { Divider().overlay(Palette.line).padding(.leading, 70) }
                }
            }
            .padding(.horizontal, Space.s4)
            .background(Palette.surfaceRaised, in: RoundedRectangle(cornerRadius: Radius.lg, style: .continuous))
        }
    }

    private var laterSection: some View {
        VStack(alignment: .leading, spacing: Space.s3) {
            SectionTitle(title: "Later this week", subtitle: nil)
            let grouped = Dictionary(grouping: model.laterItems, by: \.day)
            ForEach(grouped.keys.sorted(), id: \.self) { day in
                VStack(spacing: 0) {
                    HStack(spacing: Space.s3) {
                        VStack(spacing: 1) {
                            Text(Self.weekday(day).uppercased())
                                .font(.system(size: 9, weight: .bold))
                            Text("\(day.day)")
                                .font(.system(size: 17, weight: .semibold))
                        }
                        .foregroundStyle(Palette.brandStrong)
                        .frame(width: 42, height: 42)
                        .background(Palette.brandSoft, in: RoundedRectangle(cornerRadius: Radius.sm, style: .continuous))
                        Text(Self.fullDate(day))
                            .textStyle(.label, weight: .semibold)
                        Spacer()
                    }
                    .padding(.vertical, Space.s3)
                    ForEach(Array((grouped[day] ?? []).enumerated()), id: \.element.id) { index, item in
                        occurrenceRow(item)
                        if index < (grouped[day]?.count ?? 0) - 1 {
                            Divider().overlay(Palette.line).padding(.leading, 54)
                        }
                    }
                }
                .padding(.horizontal, Space.s4)
                .background(Palette.surfaceRaised, in: RoundedRectangle(cornerRadius: Radius.lg, style: .continuous))
            }
        }
    }

    private func occurrenceRow(_ item: OccurrenceDTO) -> some View {
        HStack(alignment: .center, spacing: Space.s3) {
            if item.isTask {
                Button { model.markDone(item) } label: {
                    Image(systemName: item.isDone ? "checkmark.circle.fill" : "circle")
                        .font(.system(size: 22, weight: .regular))
                        .foregroundStyle(item.isDone ? Palette.brandStrong : Palette.inkMuted)
                        .frame(width: 36, height: 44)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .disabled(model.workingIds.contains(item.id))
                .accessibilityLabel(item.isDone ? "Mark \(item.title) not done" : "Mark \(item.title) done")
            } else {
                Group {
                    if item.isHabit && item.status == "done" {
                        Image(systemName: "checkmark.circle.fill")
                            .font(.system(size: 21, weight: .medium))
                            .foregroundStyle(Palette.brandStrong)
                    } else {
                        Image(systemName: item.source == "people" ? "birthday.cake" : (item.isHabit ? habitSymbol(item.habit) : "calendar"))
                            .font(.system(size: 16, weight: .medium))
                            .foregroundStyle(Palette.inkMuted)
                            .overlay {
                                if item.isHabit {
                                    Circle()
                                        .strokeBorder(style: StrokeStyle(lineWidth: 1.5, dash: [3, 2]))
                                        .foregroundStyle(Palette.inkMuted)
                                        .frame(width: 25, height: 25)
                                }
                            }
                    }
                }
                .frame(width: 36, height: 40)
            }

            VStack(alignment: .leading, spacing: 3) {
                HStack(spacing: 6) {
                    if let time = item.clockLabel {
                        Text(time)
                            .textStyle(.caption, weight: .semibold)
                            .foregroundStyle(Palette.inkMuted)
                    }
                    Text(item.title)
                        .textStyle(.label, weight: .semibold)
                        .lineLimit(1)
                        .strikethrough(item.status == "done")
                    if item.isTask {
                        Text("Task")
                            .font(.system(size: 9, weight: .semibold))
                            .foregroundStyle(Palette.inkMuted)
                            .padding(.horizontal, 7)
                            .frame(height: 18)
                            .background(Palette.surfaceSunken, in: Capsule())
                    }
                }
                if let detail = rowDetail(item) {
                    Text(detail)
                        .textStyle(.caption)
                        .foregroundStyle(Palette.inkMuted)
                        .lineLimit(1)
                }
            }
            Spacer(minLength: 0)
        }
        .frame(minHeight: 52)
        .contentShape(Rectangle())
        .swipeActions(edge: .trailing, allowsFullSwipe: false) {
            if item.editable, let _ = item.planId {
                Button(role: .destructive) { planToDelete = item } label: {
                    Label("Delete", systemImage: "trash")
                }
                if item.isRepeating {
                    Button { model.skip(item) } label: { Label("Skip", systemImage: "forward.end") }
                        .tint(Palette.inkMuted)
                }
                if item.isTask {
                    Button { model.markDone(item) } label: { Label(item.isDone ? "Undo" : "Done", systemImage: "checkmark") }
                        .tint(Palette.brandStrong)
                } else if item.isHabit {
                    Button { onOpenChat("did my \(item.habit ?? item.title.lowercased())") } label: {
                        Label("Log it", systemImage: "bubble.left")
                    }
                    .tint(Palette.brandStrong)
                }
            }
        }
    }

    private var emptyState: some View {
        VStack(spacing: Space.s3) {
            Image(systemName: "calendar.badge.plus")
                .font(.system(size: 30, weight: .light))
                .foregroundStyle(Palette.brandStrong)
                .padding(.bottom, Space.s2)
            Text("Nothing planned")
                .textStyle(.heading)
            Text("Try: “lunch with Aiko on Friday at 1.”")
                .textStyle(.body)
                .foregroundStyle(Palette.inkMuted)
                .multilineTextAlignment(.center)
            Button { onOpenChat("lunch with Aiko on Friday at 1") } label: {
                Label("Ask TinyMe", systemImage: "plus")
                    .textStyle(.label, weight: .semibold)
                    .foregroundStyle(Palette.onBrand)
                    .padding(.horizontal, Space.s4)
                    .frame(height: 42)
                    .background(Palette.brand, in: Capsule())
            }
            .buttonStyle(.pressable)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, Space.s6)
        .padding(.horizontal, Space.s4)
        .background(Palette.surfaceRaised, in: RoundedRectangle(cornerRadius: Radius.lg, style: .continuous))
    }

    private func loadError(_ message: String) -> some View {
        VStack(spacing: Space.s3) {
            Text(message).textStyle(.body).foregroundStyle(Palette.inkMuted).multilineTextAlignment(.center)
            Button("Try again") { model.load() }
                .textStyle(.label, weight: .semibold)
                .buttonStyle(.pressable)
        }
        .frame(maxWidth: .infinity)
        .padding(Space.s6)
        .background(Palette.surfaceRaised, in: RoundedRectangle(cornerRadius: Radius.lg, style: .continuous))
    }

    private var selectedDayTitle: String {
        let today = model.upcoming?.today ?? .today()
        if model.selectedDay == today { return "Today" }
        if let date = model.selectedDay.date() { return date.formatted(.dateTime.weekday(.wide).month(.abbreviated).day()) }
        return "Plans"
    }

    private func rowDetail(_ item: OccurrenceDTO) -> String? {
        let details = [item.place, item.people.isEmpty ? nil : item.people.map(\.name).joined(separator: ", "), item.repeatLabel]
            .compactMap { $0 }
            .filter { !$0.isEmpty }
        return details.isEmpty ? nil : details.joined(separator: " · ")
    }

    private func habitSymbol(_ habit: String?) -> String {
        switch habit?.lowercased() {
        case "workout", "gym": "dumbbell.fill"
        case "brush_teeth", "teeth": "sparkles"
        case "walk", "walking": "figure.walk"
        default: "checkmark"
        }
    }

    private static func sortedDayItems(_ items: [OccurrenceDTO]) -> [OccurrenceDTO] {
        items.sorted {
            let left = ($0.startTime == nil ? 0 : 1, $0.startTime ?? "", $0.title)
            let right = ($1.startTime == nil ? 0 : 1, $1.startTime ?? "", $1.title)
            return left < right
        }
    }

    private static func nowMarkerIndex(_ items: [OccurrenceDTO], now: Date?, today: LocalDay?, day: LocalDay) -> Int? {
        guard day == today, let now else { return nil }
        return items.firstIndex { item in
            guard let startsAt = item.startsAt else { return false }
            return startsAt >= now
        } ?? items.count
    }

    private static func relativeTime(_ start: Date?, now: Date) -> String {
        guard let start else { return "Soon" }
        if start <= now { return "Happening now" }
        let minutes = max(1, Int(start.timeIntervalSince(now) / 60))
        if minutes < 60 { return "in \(minutes) min" }
        let hours = minutes / 60
        let remaining = minutes % 60
        return remaining < 15 ? "in \(hours) hr" : "in \(hours) hr \(remaining) min"
    }

    private static func mapsURL(_ place: String) -> URL? {
        var components = URLComponents(string: "https://maps.apple.com/")
        components?.queryItems = [URLQueryItem(name: "q", value: place)]
        return components?.url
    }

    private static func adding(_ days: Int, to day: LocalDay) -> LocalDay? {
        guard let date = day.date(), let value = Calendar.current.date(byAdding: .day, value: days, to: date) else { return nil }
        return LocalDay(date: value)
    }

    private static func weekday(_ day: LocalDay) -> String {
        day.date()?.formatted(.dateTime.weekday(.narrow)) ?? "·"
    }

    private static func fullDate(_ day: LocalDay) -> String {
        day.date()?.formatted(.dateTime.weekday(.wide).month(.abbreviated).day()) ?? day.description
    }
}

private struct SectionTitle: View {
    let title: String
    let subtitle: String?

    var body: some View {
        HStack(alignment: .firstTextBaseline) {
            Text(title).textStyle(.title, weight: .semibold)
            Spacer()
            if let subtitle {
                Text(subtitle).textStyle(.caption).foregroundStyle(Palette.inkMuted)
            }
        }
        .accessibilityAddTraits(.isHeader)
    }
}

private struct NowMarker: View {
    var body: some View {
        HStack(spacing: 7) {
            Text("NOW")
                .font(.system(size: 9, weight: .bold))
                .tracking(0.8)
            Rectangle().fill(Palette.accent).frame(height: 1.5)
        }
        .foregroundStyle(Palette.accent)
        .padding(.leading, 4)
        .padding(.vertical, Space.s2)
    }
}
