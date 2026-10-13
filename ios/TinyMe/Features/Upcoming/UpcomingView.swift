import SwiftUI

struct UpcomingView: View {
    let model: UpcomingModel
    var onOpenChat: (String) -> Void = { _ in }

    @Environment(\.scenePhase) private var scenePhase
    @State private var selectedEvent: OccurrenceDTO?
    @State private var planToDelete: OccurrenceDTO?
    @State private var dayMoveTarget: OccurrenceDTO?
    @State private var dayMoveDate = Date.now
    @State private var pendingMove: PendingMove?
    @State private var selectedTask: OccurrenceDTO?

    private struct PendingMove: Identifiable {
        let item: OccurrenceDTO
        let day: LocalDay
        let time: String
        let completion: (@MainActor @Sendable (Bool) -> Void)?
        var id: String { item.id }
    }

    var body: some View {
        @Bindable var model = model

        ScrollView {
            VStack(alignment: .leading, spacing: Space.s4) {
                header
                weekStrip
                if model.selectedDay == (model.upcoming?.today ?? .today()),
                   let next = model.upcoming?.nextUp {
                    nextUp(next)
                }
                if model.phase == .loading, model.upcoming == nil {
                    ProgressView().tint(Palette.brandStrong).frame(maxWidth: .infinity).padding(.vertical, Space.s6)
                } else if case .failed(let message) = model.phase, model.upcoming == nil {
                    loadError(message)
                } else {
                    if let error = model.errorMessage {
                        Label(error, systemImage: "exclamationmark.circle")
                            .textStyle(.caption).foregroundStyle(Palette.danger)
                    }
                    allDayAndTasks
                    DayTimeline(
                        day: model.selectedDay,
                        today: model.upcoming?.today ?? .today(),
                        now: model.upcoming?.now,
                        items: model.timedItems,
                        onSelect: model.moveDay,
                        onTap: { selectedEvent = $0 },
                        onMove: { item, time, completion in beginMove(item, to: item.day, time: time, completion: completion) }
                    )
                    .id("timeline-\(model.selectedDay.description)")
                    if model.allDayItems.isEmpty && model.timedItems.isEmpty {
                        Button { onOpenChat("Plan something for \(model.selectedDay.description)") } label: {
                            Label("Ask TinyMe to plan something", systemImage: "plus")
                                .textStyle(.label, weight: .semibold)
                                .foregroundStyle(Palette.onBrand)
                                .frame(maxWidth: .infinity).frame(height: 46)
                                .background(Palette.brand, in: Capsule())
                        }
                        .buttonStyle(.plain)
                    }
                }
            }
            .padding(.horizontal, Space.s4)
            .padding(.top, Space.s3)
            .padding(.bottom, 120)
        }
        .scrollIndicators(.hidden)
        .refreshable { await model.refresh() }
        .background(Palette.surface.ignoresSafeArea())
        .foregroundStyle(Palette.ink)
        .overlay(alignment: .bottom) {
            if let deleted = model.deletedPlan, deleted.canUndo {
                HStack(spacing: Space.s3) {
                    Text("Deleted \(deleted.title)").textStyle(.label).lineLimit(1)
                    Spacer(minLength: 0)
                    Button("Undo") { model.undoDelete() }
                        .textStyle(.label, weight: .semibold)
                        .foregroundStyle(Palette.brand)
                        .accessibilityHint("Restore the deleted plan")
                }
                .padding(.horizontal, Space.s4)
                .frame(height: 52)
                .background(Palette.ink, in: Capsule())
                .foregroundStyle(Palette.onInk)
                .padding(.horizontal, Space.s4)
                .padding(.bottom, 92)
                .transition(.move(edge: .bottom).combined(with: .opacity))
            }
        }
        .animation(.snappy(duration: 0.22), value: model.deletedPlan?.id)
        .sheet(item: $selectedEvent) { item in
            PlanEventDetailSheet(
                item: item,
                onMove: {
                    dayMoveDate = item.day.date() ?? .now
                    selectedEvent = nil
                    dayMoveTarget = item
                },
                onAsk: { draft in
                    selectedEvent = nil
                    onOpenChat(draft)
                },
                onDelete: { requestDelete(item) },
                onDone: { model.markDone(item) },
                onSkip: { model.skip(item) }
            )
        }
        .sheet(item: $dayMoveTarget) { item in
            moveDaySheet(item)
                .presentationDetents([.medium])
                .presentationDragIndicator(.visible)
        }
        .sheet(item: $selectedTask) { item in
            PlanTaskDetailSheet(
                item: item,
                onDone: { model.markDone(item); selectedTask = nil },
                onSkip: { model.skip(item); selectedTask = nil },
                onDelete: { selectedTask = nil; requestDelete(item) }
            )
        }
        .sheet(item: $pendingMove, onDismiss: {
            if let pendingMove {
                pendingMove.completion?(false)
                self.pendingMove = nil
            }
        }) { move in
            RepeatMoveChoiceSheet(
                item: move.item,
                day: move.day,
                onChoose: { allDays in commitMove(allDays: allDays) },
                onCancel: { pendingMove?.completion?(false); pendingMove = nil }
            )
            .presentationDetents([.medium])
            .presentationDragIndicator(.visible)
            .presentationBackground(Palette.surface)
        }
        .confirmationDialog(
            "Delete \(planToDelete?.title ?? "this plan")?",
            isPresented: Binding(get: { planToDelete != nil }, set: { if !$0 { planToDelete = nil } }),
            titleVisibility: .visible
        ) {
            Button("Delete series", role: .destructive) {
                if let item = planToDelete { model.delete(item) }
                planToDelete = nil
            }
            Button("Cancel", role: .cancel) { planToDelete = nil }
        } message: {
            Text("This removes every occurrence in the series.")
        }
        .onAppear { model.load() }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active { model.invalidate() }
        }
    }

    private var header: some View {
        HStack(alignment: .center) {
            Text("TinyMe")
                .textStyle(.heading, weight: .bold)
                .accessibilityAddTraits(.isHeader)
            Spacer()
            Button { onOpenChat("Plan something for \(model.selectedDay.description)") } label: {
                Image(systemName: "plus")
                    .font(.system(size: 17, weight: .semibold))
                    .foregroundStyle(Palette.onBrand)
                    .frame(width: 38, height: 38)
                    .background(Palette.brand, in: Circle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Add a plan")
        }
        .padding(.top, Space.s2)
    }

    private var weekStrip: some View {
        HStack(spacing: 4) {
            ForEach(0..<7, id: \.self) { offset in
                if let day = Self.adding(offset, to: model.weekStart) {
                    let summary = model.weekDays.first { $0.day == day }
                    let selected = day == model.selectedDay
                    let today = day == (model.upcoming?.today ?? .today())
                    Button { model.select(day) } label: {
                        VStack(spacing: 6) {
                            Text(Self.weekday(day)).font(.system(size: 11, weight: .semibold))
                                .foregroundStyle(selected ? Palette.onBrand : Palette.inkMuted)
                            Text("\(day.day)")
                                .font(.system(size: 16, weight: .semibold))
                                .foregroundStyle(selected ? Palette.onBrand : Palette.ink)
                                .frame(width: 38, height: 38)
                                .background { if selected { Circle().fill(Palette.brand) } }
                            Circle().fill((summary?.count ?? 0) > 0 ? (selected ? Palette.onBrand : Palette.brandStrong) : .clear)
                                .frame(width: 4, height: 4)
                        }
                        .frame(maxWidth: .infinity).frame(height: 70)
                        .overlay(alignment: .bottom) {
                            if today && !selected { Capsule().fill(Palette.brandStrong).frame(width: 16, height: 2).offset(y: 5) }
                        }
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("\(Self.fullDate(day)), \(summary?.count ?? 0) plans")
                    .accessibilityAddTraits(selected ? .isSelected : [])
                }
            }
        }
        .padding(.vertical, 2)
        .simultaneousGesture(DragGesture(minimumDistance: 28).onEnded { value in
            if value.translation.width < -50 { model.moveWeek(by: 1) }
            else if value.translation.width > 50, !model.isCurrentWeek { model.moveWeek(by: -1) }
        })
        .accessibilityHint("Swipe left or right to change week")
    }

    @ViewBuilder
    private func nextUp(_ item: OccurrenceDTO) -> some View {
        VStack(alignment: .leading, spacing: Space.s2) {
            HStack {
                Label("NEXT UP", systemImage: "sparkle").font(.system(size: 11, weight: .bold)).tracking(1)
                Spacer()
                if let start = item.startsAt { Text(start.formatted(.dateTime.hour().minute())).textStyle(.caption, weight: .semibold) }
            }
            .foregroundStyle(Palette.onInk.opacity(0.75))
            HStack {
                Text(item.title).textStyle(.heading, weight: .semibold).foregroundStyle(Palette.onInk)
                Spacer()
                if let place = item.place, !place.isEmpty {
                    Image(systemName: "mappin.and.ellipse").foregroundStyle(Palette.brand)
                }
            }
            if let place = item.place, !place.isEmpty {
                Text(place).textStyle(.caption).foregroundStyle(Palette.onInk.opacity(0.7))
            }
        }
        .padding(Space.s4)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Palette.ink, in: RoundedRectangle(cornerRadius: Radius.lg, style: .continuous))
    }

    private var allDayAndTasks: some View {
        let items = model.allDayItems
        return Group {
            if !items.isEmpty {
                VStack(alignment: .leading, spacing: Space.s2) {
                    ScrollView(.horizontal) {
                        HStack(spacing: Space.s2) {
                            Text("All-day")
                                .font(.system(size: 11, weight: .semibold))
                                .foregroundStyle(Palette.inkMuted)
                                .padding(.horizontal, 10)
                                .frame(height: 36)
                                .background(Palette.surfaceRaised, in: Capsule())
                            ForEach(items) { item in allDayChip(item) }
                        }
                        .padding(.vertical, 1)
                    }
                    .scrollIndicators(.hidden)
                }
            }
        }
    }

    private func allDayChip(_ item: OccurrenceDTO) -> some View {
        HStack(spacing: 8) {
            if item.isTask {
                Button { model.markDone(item) } label: {
                    Image(systemName: item.isDone ? "checkmark.circle.fill" : "circle")
                        .foregroundStyle(item.isDone ? Palette.brandStrong : Palette.inkMuted)
                }
                .buttonStyle(.plain)
                .disabled(model.workingIds.contains(item.id))
            } else {
                Image(systemName: item.source == "people" ? "birthday.cake" : "sun.max")
                    .foregroundStyle(item.source == "people" ? Palette.sunny : Palette.brandStrong)
            }
            Button {
                if item.isTask {
                    selectedTask = item
                } else {
                    selectedEvent = item
                }
            } label: {
                Text(item.title)
                    .textStyle(.caption, weight: .semibold)
                    .lineLimit(1)
                    .foregroundStyle(Palette.ink)
            }
            .buttonStyle(.plain)
        }
        .padding(.horizontal, 11)
        .frame(height: 36)
        .background(Palette.surfaceRaised, in: Capsule())
    }

    private func moveDaySheet(_ item: OccurrenceDTO) -> some View {
        NavigationStack {
            VStack(spacing: Space.s4) {
                DatePicker("Move to", selection: $dayMoveDate, displayedComponents: .date)
                    .datePickerStyle(.graphical)
                    .tint(Palette.brandStrong)
                Button("Continue") {
                    guard let day = LocalDay(date: dayMoveDate), let time = item.startTime else { return }
                    dayMoveTarget = nil
                    beginMove(item, to: day, time: time)
                }
                .buttonStyle(.borderedProminent).tint(Palette.brand)
                .disabled(item.startTime == nil)
            }
            .padding(Space.s4)
            .navigationTitle("Move event")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dayMoveTarget = nil } } }
        }
    }

    private func beginMove(_ item: OccurrenceDTO, to day: LocalDay, time: String,
                           completion: (@MainActor @Sendable (Bool) -> Void)? = nil) {
        pendingMove = PendingMove(item: item, day: day, time: time, completion: completion)
        if !item.isRepeating { commitMove(allDays: true) }
    }

    private func commitMove(allDays: Bool) {
        guard let pendingMove else { return }
        if let date = pendingMove.day.date(), let time = Self.parseTime(pendingMove.time) {
            let target = Calendar.current.date(bySettingHour: time.0, minute: time.1, second: 0, of: date)
            if let target, target < .now {
                model.announceError("An event can’t be moved into the past.")
                self.pendingMove = nil
                return
            }
        }
        model.move(pendingMove.item, to: pendingMove.day, time: pendingMove.time, allDays: allDays,
                   completion: pendingMove.completion)
        self.pendingMove = nil
    }

    private func requestDelete(_ item: OccurrenceDTO) {
        selectedEvent = nil
        if item.isRepeating { planToDelete = item }
        else { model.delete(item) }
    }

    private func loadError(_ message: String) -> some View {
        VStack(spacing: Space.s3) {
            Text(message).textStyle(.body).foregroundStyle(Palette.inkMuted).multilineTextAlignment(.center)
            Button("Try again") { model.load() }.textStyle(.label, weight: .semibold)
        }
        .frame(maxWidth: .infinity).padding(Space.s6)
        .background(Palette.surfaceRaised, in: RoundedRectangle(cornerRadius: Radius.lg, style: .continuous))
    }

    private static func adding(_ days: Int, to day: LocalDay) -> LocalDay? {
        guard let date = day.date(), let shifted = Calendar.current.date(byAdding: .day, value: days, to: date) else { return nil }
        return LocalDay(date: shifted)
    }

    private static func weekday(_ day: LocalDay) -> String { day.date()?.formatted(.dateTime.weekday(.narrow)) ?? "·" }
    private static func fullDate(_ day: LocalDay) -> String { day.date()?.formatted(.dateTime.weekday(.wide).month(.abbreviated).day()) ?? day.description }
    private static func parseTime(_ value: String) -> (Int, Int)? {
        let parts = value.split(separator: ":").compactMap { Int($0) }
        guard parts.count == 2 else { return nil }
        return (parts[0], parts[1])
    }
}

private struct PlanEventDetailSheet: View {
    let item: OccurrenceDTO
    let onMove: () -> Void
    /// Opens chat with a draft: the details are changed by asking TinyMe.
    let onAsk: (String) -> Void
    let onDelete: () -> Void
    let onDone: () -> Void
    let onSkip: () -> Void
    @Environment(\.openURL) private var openURL
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        VStack(alignment: .leading, spacing: Space.s4) {
            PlanSheetHeader(title: item.title, subtitle: subtitle, onClose: { dismiss() }) {
                Image(systemName: item.symbol)
                    .font(.system(size: 28, weight: .medium))
                    .foregroundStyle(Palette.ink)
                    .frame(width: 40, height: 44)
            }
            PlanSheetRows(rows: rows)
            if item.editable {
                VStack(spacing: Space.s2) {
                    if item.isTask {
                        Button(action: onDone) {
                            Label(item.isDone ? "Undo done" : "Done", systemImage: item.isDone ? "arrow.uturn.backward" : "checkmark")
                        }
                        .buttonStyle(PlanSheetButtonStyle(kind: .primary))
                        if item.isRepeating {
                            Button(action: onSkip) { Label("Skip today", systemImage: "forward.end.fill") }
                                .buttonStyle(PlanSheetButtonStyle(kind: .quiet))
                        }
                    } else {
                        if let place {
                            Button { if let url = Self.mapsURL(place) { openURL(url) } } label: {
                                Label("Directions", systemImage: "location.fill")
                            }
                            .buttonStyle(PlanSheetButtonStyle(kind: .primary))
                        }
                        Button { onAsk("I'm running late for \(item.title)") } label: {
                            Label("Running late", systemImage: "clock")
                        }
                        .buttonStyle(PlanSheetButtonStyle(kind: .quiet))
                    }
                    Button(action: onDelete) { Label("Delete", systemImage: "trash") }
                        .buttonStyle(PlanSheetButtonStyle(kind: .destructive))
                }
            }
        }
        .planSheet()
    }

    private var place: String? { item.place?.nilIfEmpty }

    private var subtitle: String {
        let date = (item.startsAt ?? item.day.date())?.formatted(.dateTime.weekday(.abbreviated).day().month(.abbreviated))
            ?? item.day.description
        guard let start = item.startsAt else { return "\(date) · All day" }
        let first = start.formatted(.dateTime.hour().minute())
        guard let end = item.endsAt else { return "\(date) · \(first)" }
        return "\(date) · \(first) – \(end.formatted(.dateTime.hour().minute()))"
    }

    /// Place, people and repeat always show on a plan you can change, so there is somewhere to tap to set them.
    private var rows: [PlanSheetRow] {
        let ask: (String) -> (() -> Void)? = { draft in item.editable ? { onAsk(draft) } : nil }
        var rows: [PlanSheetRow] = []
        if let place {
            rows.append(PlanSheetRow(symbol: "mappin.and.ellipse", text: place, action: ask("Change the place for \(item.title)")))
        } else if item.editable {
            rows.append(PlanSheetRow(symbol: "mappin.and.ellipse", text: "No place", isMuted: true, action: ask("Add a place to \(item.title)")))
        }
        if !item.people.isEmpty {
            rows.append(PlanSheetRow(symbol: "person", text: item.people.map(\.name).joined(separator: ", "),
                                     action: ask("Change who's coming to \(item.title)")))
        } else if item.editable {
            rows.append(PlanSheetRow(symbol: "person", text: "Just me", action: ask("Add someone to \(item.title)")))
        }
        if let repeatLabel = item.repeatLabel {
            rows.append(PlanSheetRow(symbol: "arrow.triangle.2.circlepath", text: repeatLabel,
                                     action: ask("Change how often \(item.title) repeats")))
        } else if item.editable {
            rows.append(PlanSheetRow(symbol: "arrow.triangle.2.circlepath", text: "Doesn't repeat", isMuted: true,
                                     action: ask("Make \(item.title) repeat")))
        }
        if let notes = item.notes?.nilIfEmpty {
            rows.append(PlanSheetRow(symbol: "text.alignleft", text: notes))
        }
        if item.editable, !item.isTask, item.startTime != nil {
            rows.append(PlanSheetRow(symbol: "calendar", text: "Move to another day", action: onMove))
        }
        return rows
    }

    private static func mapsURL(_ place: String) -> URL? {
        var components = URLComponents(string: "https://maps.apple.com/")
        components?.queryItems = [URLQueryItem(name: "q", value: place)]
        return components?.url
    }
}

private extension String {
    var nilIfEmpty: String? { isEmpty ? nil : self }
}

private struct RepeatMoveChoiceSheet: View {
    let item: OccurrenceDTO
    let day: LocalDay
    let onChoose: (Bool) -> Void
    let onCancel: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: Space.s3) {
            Text("Move repeating event")
                .textStyle(.heading, weight: .semibold)
            Text("Move \(item.title) just this occurrence or every future one?")
                .textStyle(.caption)
                .foregroundStyle(Palette.inkMuted)
            choice(title: "This day only", detail: "Move only \(day.date()?.formatted(.dateTime.weekday(.abbreviated).day().month(.abbreviated)) ?? day.description)", symbol: "calendar", allDays: false)
            choice(title: "All days", detail: "Move this and all future events", symbol: "repeat", allDays: true)
            Button("Cancel", action: onCancel)
                .textStyle(.label, weight: .semibold)
                .frame(maxWidth: .infinity)
                .frame(height: 46)
                .background(Palette.surfaceSunken, in: Capsule())
                .buttonStyle(.plain)
        }
        .padding(.horizontal, Space.s4)
        .padding(.top, Space.s4)
        .padding(.bottom, Space.s3)
    }

    private func choice(title: String, detail: String, symbol: String, allDays: Bool) -> some View {
        Button { onChoose(allDays) } label: {
            HStack(spacing: Space.s3) {
                Image(systemName: symbol).font(.system(size: 19, weight: .medium)).foregroundStyle(Palette.brandStrong)
                    .frame(width: 28)
                VStack(alignment: .leading, spacing: 3) {
                    Text(title).textStyle(.label, weight: .semibold)
                    Text(detail).textStyle(.caption).foregroundStyle(Palette.inkMuted)
                }
                Spacer()
                Image(systemName: "chevron.right").font(.system(size: 12, weight: .semibold)).foregroundStyle(Palette.inkMuted)
            }
            .padding(.horizontal, Space.s3)
            .frame(maxWidth: .infinity, minHeight: 58, alignment: .leading)
            .background(Palette.surfaceRaised, in: RoundedRectangle(cornerRadius: Radius.md, style: .continuous))
        }
        .buttonStyle(.plain)
    }
}

private struct PlanTaskDetailSheet: View {
    let item: OccurrenceDTO
    let onDone: () -> Void
    let onSkip: () -> Void
    let onDelete: () -> Void
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        VStack(alignment: .leading, spacing: Space.s4) {
            PlanSheetHeader(title: item.title, subtitle: nil, onClose: { dismiss() }) {
                Image(systemName: item.isDone ? "checkmark.circle.fill" : "circle")
                    .font(.system(size: 30, weight: .regular))
                    .foregroundStyle(item.isDone ? Palette.brand : Palette.ink)
                    .frame(width: 40, height: 44)
            }
            PlanSheetRows(rows: rows)
            if item.editable {
                VStack(spacing: Space.s2) {
                    Button(action: onDone) {
                        Label(item.isDone ? "Undo done" : "Done", systemImage: item.isDone ? "arrow.uturn.backward" : "checkmark")
                    }
                    .buttonStyle(PlanSheetButtonStyle(kind: .primary))
                    if item.isRepeating {
                        Button(action: onSkip) { Label("Skip today", systemImage: "forward.end.fill") }
                            .buttonStyle(PlanSheetButtonStyle(kind: .quiet))
                    }
                    Button(action: onDelete) { Label("Delete", systemImage: "trash") }
                        .buttonStyle(PlanSheetButtonStyle(kind: .destructive))
                }
            }
        }
        .planSheet()
    }

    private var rows: [PlanSheetRow] {
        var rows: [PlanSheetRow] = []
        if let notes = item.notes?.nilIfEmpty { rows.append(PlanSheetRow(symbol: "list.bullet", text: notes)) }
        if let repeatLabel = item.repeatLabel {
            rows.append(PlanSheetRow(symbol: "arrow.triangle.2.circlepath", text: repeatLabel))
        }
        return rows
    }
}

// MARK: - Shared sheet pieces

private struct PlanSheetHeader<Icon: View>: View {
    let title: String
    let subtitle: String?
    let onClose: () -> Void
    @ViewBuilder let icon: Icon

    var body: some View {
        HStack(alignment: .center, spacing: Space.s3) {
            icon.accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 2) {
                Text(title).textStyle(.heading, weight: .semibold).lineLimit(2)
                if let subtitle {
                    Text(subtitle).textStyle(.label).foregroundStyle(Palette.inkMuted)
                }
            }
            .accessibilityElement(children: .combine)
            .accessibilityAddTraits(.isHeader)
            Spacer(minLength: Space.s2)
            Button(action: onClose) {
                Image(systemName: "xmark")
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(Palette.ink)
                    .frame(width: 32, height: 32)
                    .background(Palette.surfaceSunken, in: Circle())
                    .frame(width: 44, height: 44)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Close")
        }
    }
}

private struct PlanSheetRow {
    let symbol: String
    let text: String
    var isMuted = false
    var action: (() -> Void)?
}

/// The grouped list under the header: one bordered block, a hairline between rows, a chevron on rows that do something.
private struct PlanSheetRows: View {
    let rows: [PlanSheetRow]

    var body: some View {
        if !rows.isEmpty {
            VStack(spacing: 0) {
                ForEach(Array(rows.enumerated()), id: \.offset) { index, row in
                    if index > 0 {
                        Rectangle().fill(Palette.line).frame(height: 1).padding(.horizontal, Space.s4)
                    }
                    if let action = row.action {
                        Button(action: action) { label(row, chevron: true) }.buttonStyle(.plain)
                    } else {
                        label(row, chevron: false)
                    }
                }
            }
            .background(Palette.surfaceRaised, in: RoundedRectangle(cornerRadius: Radius.md, style: .continuous))
            .overlay { RoundedRectangle(cornerRadius: Radius.md, style: .continuous).strokeBorder(Palette.line) }
        }
    }

    private func label(_ row: PlanSheetRow, chevron: Bool) -> some View {
        HStack(spacing: Space.s3) {
            Image(systemName: row.symbol)
                .font(.system(size: 17, weight: .regular))
                .foregroundStyle(Palette.ink)
                .frame(width: 24)
            Text(row.text)
                .textStyle(.body)
                .foregroundStyle(row.isMuted ? Palette.inkMuted : Palette.ink)
                .lineLimit(4)
                .multilineTextAlignment(.leading)
            Spacer(minLength: Space.s2)
            if chevron {
                Image(systemName: "chevron.right")
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(Palette.inkMuted)
            }
        }
        .padding(.horizontal, Space.s4)
        .padding(.vertical, Space.s3)
        .frame(maxWidth: .infinity, minHeight: 48, alignment: .leading)
        .contentShape(Rectangle())
    }
}

private struct PlanSheetButtonStyle: ButtonStyle {
    enum Kind {
        case primary, quiet, destructive
    }

    let kind: Kind

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .textStyle(.body, weight: .semibold)
            .foregroundStyle(foreground)
            .frame(maxWidth: .infinity)
            .frame(height: 50)
            .background(background, in: Capsule())
            .opacity(configuration.isPressed ? 0.8 : 1)
            .contentShape(Capsule())
    }

    private var foreground: Color {
        switch kind {
        case .primary: Palette.onBrand
        case .quiet: Palette.ink
        case .destructive: Palette.onInk
        }
    }

    private var background: Color {
        switch kind {
        case .primary: Palette.brand
        case .quiet: Palette.surfaceSunken
        case .destructive: Palette.danger
        }
    }
}

private struct PlanSheetChrome: ViewModifier {
    @State private var height: CGFloat = 420

    func body(content: Content) -> some View {
        content
            .padding(.horizontal, Space.s4)
            .padding(.top, Space.s6)
            .padding(.bottom, Space.s2)
            .fixedSize(horizontal: false, vertical: true)
            .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { height = $0 }
            .frame(maxHeight: .infinity, alignment: .top)
            .foregroundStyle(Palette.ink)
            .presentationDetents([.height(height)])
            .presentationDragIndicator(.visible)
            .presentationBackground(Palette.surface)
            .presentationCornerRadius(Radius.lg)
    }
}

private extension View {
    /// A plan sheet is as tall as what it holds.
    func planSheet() -> some View { modifier(PlanSheetChrome()) }
}
