import SwiftUI
#if canImport(UIKit)
import UIKit
#endif

private struct EventPlacement: Identifiable {
    let item: OccurrenceDTO
    let column: Int
    let columns: Int
    let startMinute: Int
    let endMinute: Int
    var id: String { item.id }
}

struct DayTimeline: View {
    let day: LocalDay
    let today: LocalDay
    let now: Date?
    let items: [OccurrenceDTO]
    let onSelect: (Int) -> Void
    let onTap: (OccurrenceDTO) -> Void
    let onMove: (OccurrenceDTO, String, @escaping @MainActor @Sendable (Bool) -> Void) -> Void

    @State private var drag: DragPreview?
    @State private var optimisticMinutes: [String: Int] = [:]
    private let laneWidth: CGFloat = 58
    private let pointsPerMinute: CGFloat = 1.05

    private struct DragPreview {
        let item: OccurrenceDTO
        let initialMinute: Int
        let offsetMinutes: Int
    }

    private var placements: [EventPlacement] { Self.place(items) }
    private var startHour: Int {
        let firstEvent = placements.map { $0.startMinute / 60 }.min() ?? 7
        let currentHour = day == today ? Calendar.current.component(.hour, from: now ?? .now) : 7
        return max(0, min(7, firstEvent, currentHour))
    }
    private var endHour: Int {
        let lastEvent = ((placements.map(\.endMinute).max() ?? 20 * 60) + 59) / 60 + 1
        let currentHour = day == today ? Calendar.current.component(.hour, from: now ?? .now) + 2 : 22
        return min(24, max(22, lastEvent, currentHour))
    }
    private var timelineHeight: CGFloat { CGFloat((endHour - startHour) * 60) * pointsPerMinute }

    var body: some View {
        timeline
            .accessibilityElement(children: .contain)
    }

    private var timeline: some View {
        ScrollViewReader { proxy in
            ScrollView(.vertical) {
                GeometryReader { geometry in
                    ZStack(alignment: .topLeading) {
                        ForEach(startHour..<endHour, id: \.self) { hour in
                            let y = CGFloat((hour - startHour) * 60) * pointsPerMinute
                            Text(Self.hourLabel(hour))
                                .font(.system(size: 11, weight: .medium))
                                .foregroundStyle(Palette.inkMuted)
                                .frame(width: laneWidth - 8, alignment: .trailing)
                                .position(x: (laneWidth - 8) / 2, y: y + 8)
                                .id("hour-\(hour)")
                            Rectangle()
                                .fill(Palette.line)
                                .frame(width: max(0, geometry.size.width - laneWidth), height: 1)
                                .position(x: laneWidth + max(0, geometry.size.width - laneWidth) / 2, y: y)
                        }

                        ForEach(placements) { placement in
                            eventBlock(placement, availableWidth: geometry.size.width - laneWidth)
                        }

                        if day == today, let now {
                            nowLine(now, width: geometry.size.width)
                        }
                    }
                    .frame(width: geometry.size.width, height: timelineHeight, alignment: .topLeading)
                }
                .frame(height: timelineHeight)
            }
            .scrollIndicators(.hidden)
            .contentShape(Rectangle())
            .simultaneousGesture(DragGesture(minimumDistance: 38).onEnded { value in
                guard abs(value.translation.width) > abs(value.translation.height) * 1.35 else { return }
                onSelect(value.translation.width < 0 ? 1 : -1)
            })
            .onAppear { scrollToOpeningHour(proxy) }
            .accessibilityHint("Swipe left or right to change the selected day")
        }
        .padding(.top, 6)
    }

    private func eventBlock(_ placement: EventPlacement, availableWidth: CGFloat) -> some View {
        let minute = drag?.item.id == placement.item.id
            ? placement.startMinute + (drag?.offsetMinutes ?? 0)
            : (optimisticMinutes[placement.item.id] ?? placement.startMinute)
        let top = CGFloat(minute - startHour * 60) * pointsPerMinute
        let duration = max(35, placement.endMinute - placement.startMinute)
        let height = max(42, CGFloat(duration) * pointsPerMinute)
        let gap: CGFloat = 4
        let columnWidth = max(50, (availableWidth - CGFloat(placement.columns - 1) * gap - 4) / CGFloat(placement.columns))
        let x = laneWidth + 3 + CGFloat(placement.column) * (columnWidth + gap)
        let isHabit = placement.item.isHabit
        let done = placement.item.status == "done"

        return EventBlock(item: placement.item, isHabit: isHabit, isDone: done, isDragging: drag?.item.id == placement.item.id)
            .frame(width: columnWidth, height: height)
            .position(x: x + columnWidth / 2, y: top + height / 2)
            .zIndex(drag?.item.id == placement.item.id ? 4 : 1)
            .onTapGesture { onTap(placement.item) }
            .simultaneousGesture(dragGesture(for: placement))
    }

    private func dragGesture(for placement: EventPlacement) -> some Gesture {
        LongPressGesture(minimumDuration: 0.3)
            .sequenced(before: DragGesture(minimumDistance: 0))
            .onChanged { value in
                guard placement.item.editable, placement.item.source != "people" else { return }
                if case .second(true, let movement?) = value {
                    let start = drag?.item.id == placement.item.id ? drag!.initialMinute : placement.startMinute
                    let raw = Int((movement.translation.height / pointsPerMinute).rounded())
                    let snapped = Int((Double(raw) / 15.0).rounded()) * 15
                    if drag?.item.id == placement.item.id, drag?.offsetMinutes != snapped {
                        #if canImport(UIKit)
                        UISelectionFeedbackGenerator().selectionChanged()
                        #endif
                    }
                    drag = DragPreview(item: placement.item, initialMinute: start, offsetMinutes: snapped)
                } else if case .first(true) = value, drag?.item.id != placement.item.id {
                    drag = DragPreview(item: placement.item, initialMinute: placement.startMinute, offsetMinutes: 0)
                }
            }
            .onEnded { value in
                defer { drag = nil }
                guard placement.item.editable, placement.item.source != "people",
                      case .second(true, let movement?) = value else { return }
                let raw = Int((movement.translation.height / pointsPerMinute).rounded())
                let snapped = Int((Double(raw) / 15.0).rounded()) * 15
                let target = min(24 * 60 - 15, max(0, placement.startMinute + snapped))
                guard target != placement.startMinute else { return }
                let time = String(format: "%02d:%02d", target / 60, target % 60)
                let targetDate = Calendar.current.date(bySettingHour: target / 60, minute: target % 60, second: 0, of: day.date() ?? .now)
                // A repeating plan can take an earlier time for every day; the choice that follows decides.
                guard let targetDate, targetDate >= .now || placement.item.isRepeating else { return }
                optimisticMinutes[placement.item.id] = target
                onMove(placement.item, time) { success in
                    optimisticMinutes.removeValue(forKey: placement.item.id)
                    if !success {
                        #if canImport(UIKit)
                        UIImpactFeedbackGenerator(style: .light).impactOccurred()
                        #endif
                    }
                }
            }
    }

    private func nowLine(_ now: Date, width: CGFloat) -> some View {
        let components = Calendar.current.dateComponents([.hour, .minute], from: now)
        let minute = (components.hour ?? 0) * 60 + (components.minute ?? 0)
        let y = CGFloat(minute - startHour * 60) * pointsPerMinute
        return HStack(spacing: 5) {
            Text(now.formatted(.dateTime.hour().minute()))
                .font(.system(size: 10, weight: .bold)).foregroundStyle(Palette.accent)
                .frame(width: laneWidth - 4, alignment: .trailing)
            Circle().fill(Palette.accent).frame(width: 7, height: 7)
            Rectangle().fill(Palette.accent).frame(height: 1.5)
        }
        .frame(width: width, alignment: .leading)
        .position(x: width / 2, y: y)
        .zIndex(5)
        .accessibilityLabel("Current time")
    }

    private func scrollToOpeningHour(_ proxy: ScrollViewProxy) {
        let initial = day == today ? max(startHour, (Calendar.current.component(.hour, from: now ?? .now) - 1)) : startHour
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.12) {
            withAnimation(.easeOut(duration: 0.25)) { proxy.scrollTo("hour-\(initial)", anchor: .top) }
        }
    }

    private static func hourLabel(_ hour: Int) -> String {
        var components = DateComponents()
        components.hour = hour
        return Calendar.current.date(from: components)?.formatted(.dateTime.hour()) ?? "\(hour)"
    }

    private static func minuteOfDay(_ date: Date) -> Int {
        let parts = Calendar.current.dateComponents([.hour, .minute], from: date)
        return (parts.hour ?? 0) * 60 + (parts.minute ?? 0)
    }

    private static func place(_ items: [OccurrenceDTO]) -> [EventPlacement] {
        let timed = items.compactMap { item -> (OccurrenceDTO, Int, Int)? in
            guard let start = item.startsAt else { return nil }
            let startMinute = minuteOfDay(start)
            let duration = item.endsAt.map { max(30, Int($0.timeIntervalSince(start) / 60)) } ?? 60
            return (item, startMinute, startMinute + duration)
        }.sorted { $0.1 == $1.1 ? $0.2 < $1.2 : $0.1 < $1.1 }

        var result: [EventPlacement] = []
        var cluster: [(OccurrenceDTO, Int, Int)] = []
        var clusterEnd = -1
        func flush(_ events: [(OccurrenceDTO, Int, Int)], into output: inout [EventPlacement]) {
            guard !events.isEmpty else { return }
            var columnEnds: [Int] = []
            var assigned: [(OccurrenceDTO, Int, Int, Int)] = []
            for event in events {
                let column = columnEnds.firstIndex(where: { $0 <= event.1 }) ?? columnEnds.count
                if column == columnEnds.count { columnEnds.append(event.2) }
                else { columnEnds[column] = event.2 }
                assigned.append((event.0, event.1, event.2, column))
            }
            let count = max(1, columnEnds.count)
            output.append(contentsOf: assigned.map { EventPlacement(item: $0.0, column: $0.3, columns: count, startMinute: $0.1, endMinute: $0.2) })
        }
        for event in timed {
            if !cluster.isEmpty && event.1 >= clusterEnd {
                flush(cluster, into: &result)
                cluster = []
            }
            cluster.append(event)
            clusterEnd = max(clusterEnd, event.2)
        }
        flush(cluster, into: &result)
        return result
    }
}

private struct EventBlock: View {
    let item: OccurrenceDTO
    let isHabit: Bool
    let isDone: Bool
    let isDragging: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 3) {
            HStack(spacing: 5) {
                Image(systemName: item.symbol)
                    .font(.system(size: 13, weight: .semibold))
                Text(timeRange).font(.system(size: 11, weight: .medium)).lineLimit(1)
                Spacer(minLength: 0)
                if isDone { Image(systemName: "checkmark").font(.system(size: 11, weight: .bold)) }
            }
            Text(item.title).font(.system(size: 14, weight: .semibold)).lineLimit(1)
            if let place = item.place, !place.isEmpty, heightAllowsDetail {
                Text(place).font(.system(size: 11)).lineLimit(1).foregroundStyle(textColor.opacity(0.76))
            }
        }
        .padding(.horizontal, 9).padding(.vertical, 7)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .foregroundStyle(textColor)
        .background(background, in: RoundedRectangle(cornerRadius: 11, style: .continuous))
        .overlay {
            if isHabit && !isDone {
                RoundedRectangle(cornerRadius: 11, style: .continuous)
                    .strokeBorder(textColor.opacity(0.8), style: StrokeStyle(lineWidth: 1.5, dash: [5, 3]))
            } else if !isHabit {
                HStack {
                    Capsule().fill(Palette.brandStrong).frame(width: 3).padding(.vertical, 5)
                    Spacer(minLength: 0)
                }
                .padding(.leading, 1)
                .clipShape(RoundedRectangle(cornerRadius: 11, style: .continuous))
            }
        }
        .opacity(isDragging ? 0.84 : 1)
        .shadow(color: .black.opacity(isDragging ? 0.2 : 0.04), radius: isDragging ? 8 : 1, y: isDragging ? 4 : 1)
        .accessibilityElement(children: .combine)
        .accessibilityLabel("\(item.title), \(timeRange)\(isHabit ? ", habit" : "")")
        .accessibilityHint(item.editable ? "Tap for details. Touch and hold to move." : "Tap for details")
    }

    private var heightAllowsDetail: Bool { (item.endsAt?.timeIntervalSince(item.startsAt ?? .now) ?? 0) >= 45 * 60 }
    private var timeRange: String {
        guard let start = item.startsAt else { return "" }
        let first = start.formatted(.dateTime.hour().minute())
        guard let end = item.endsAt else { return first }
        return "\(first) – \(end.formatted(.dateTime.hour().minute()))"
    }
    private var background: Color {
        if isDone { return Palette.brandStrong }
        return isHabit ? Palette.brandSoft.opacity(0.65) : Palette.surfaceRaised
    }
    private var textColor: Color { isDone ? Palette.onBrand : Palette.ink }
}
