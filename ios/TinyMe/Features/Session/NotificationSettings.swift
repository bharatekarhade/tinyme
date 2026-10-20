import SwiftUI
import UIKit

/// The Notifications part of Settings: a switch per kind, and when the journal nudge comes.
/// Turning the first one on asks iOS for permission.
struct NotificationSettingsSection: View {
    var notifier: Notifier

    @AppStorage(Notifier.Key.plans) private var plans = false
    @AppStorage(Notifier.Key.mail) private var mail = false
    @AppStorage(Notifier.Key.journal) private var journal = false
    @AppStorage(Notifier.Key.journalMinutes) private var journalMinutes = Notifier.defaultJournalMinutes
    @State private var isDenied = false
    @State private var testSent = false

    var body: some View {
        VStack(alignment: .leading, spacing: Space.s2) {
            Text("Notifications")
                .textStyle(.label, weight: .semibold)
                .foregroundStyle(Palette.inkMuted)
                .padding(.horizontal, 4)
            VStack(spacing: 0) {
                toggle("Meetings and tasks", detail: "Before each one starts, and today's tasks at 9:00.", isOn: $plans)
                divider
                toggle("Found in Gmail", detail: "New payments, plans and jobs to review.", isOn: $mail)
                divider
                toggle("Journal nudge", detail: "Skipped on days you already wrote something.", isOn: $journal)
                if journal {
                    divider
                    DatePicker(selection: journalTime, displayedComponents: .hourAndMinute) {
                        Text("Nudge me at")
                            .textStyle(.label, weight: .regular)
                            .foregroundStyle(Palette.inkMuted)
                    }
                    .tint(Palette.brand)
                    .padding(.horizontal, 18)
                    .padding(.vertical, 10)
                }
            }
            .background(
                RoundedRectangle(cornerRadius: Radius.lg, style: .continuous)
                    .fill(Palette.surfaceRaised)
                    .cardShadow()
            )
            if !isDenied, plans || mail || journal {
                status
            }
            if isDenied {
                Button {
                    if let settings = URL(string: UIApplication.openSettingsURLString) {
                        UIApplication.shared.open(settings)
                    }
                } label: {
                    Text("Notifications are off for TinyMe. **Turn them on in iPhone Settings.**")
                        .textStyle(.caption)
                        .foregroundStyle(Palette.inkMuted)
                        .multilineTextAlignment(.leading)
                }
                .buttonStyle(.plain)
                .padding(.horizontal, 4)
            }
        }
        .animation(.snappy(duration: 0.25), value: journal)
        .task {
            await notifier.checkPermission()
            isDenied = notifier.authorization == .denied && (plans || mail || journal)
            await notifier.refresh()   // so the line below shows what is really waiting
        }
    }

    /// What will ring next, so it's clear the switches did something, and a test to check this phone shows them.
    private var status: some View {
        HStack(alignment: .firstTextBaseline, spacing: Space.s2) {
            Text(statusLine)
                .textStyle(.caption)
                .foregroundStyle(notifier.problem == nil ? Palette.inkMuted : Palette.accent)
                .frame(maxWidth: .infinity, alignment: .leading)
            Button(testSent ? "Sent" : "Send a test") {
                Task {
                    testSent = await notifier.sendTest()
                    isDenied = !testSent && notifier.authorization == .denied
                    try? await Task.sleep(for: .seconds(6))
                    testSent = false
                }
            }
            .textStyle(.caption, weight: .semibold)
            .foregroundStyle(Palette.brandStrong)
            .disabled(testSent)
        }
        .padding(.horizontal, 4)
    }

    private var statusLine: String {
        if let problem = notifier.problem { return problem }
        guard let next = notifier.scheduled.first else {
            return plans ? "Nothing to ring yet. Plans with a time in the next 7 days get a reminder."
                         : "Nothing to ring yet."
        }
        let when = Calendar.current.isDateInToday(next.fireDate)
            ? next.fireDate.formatted(.dateTime.hour().minute())
            : next.fireDate.formatted(.dateTime.weekday(.abbreviated).hour().minute())
        let count = notifier.scheduled.count
        return "Next: \(next.title) at \(when)" + (count > 1 ? " · \(count) waiting" : "")
    }

    private func toggle(_ title: String, detail: String, isOn: Binding<Bool>) -> some View {
        Toggle(isOn: Binding(get: { isOn.wrappedValue }, set: { set(isOn, to: $0) })) {
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .textStyle(.label, weight: .semibold)
                Text(detail)
                    .textStyle(.caption)
                    .foregroundStyle(Palette.inkMuted)
            }
        }
        .tint(Palette.brand)
        .padding(.horizontal, 18)
        .padding(.vertical, 14)
    }

    private func set(_ value: Binding<Bool>, to on: Bool) {
        guard on else {
            value.wrappedValue = false
            Task { await notifier.settingsChanged() }
            return
        }
        value.wrappedValue = true
        Task {
            if await notifier.requestPermission() {
                isDenied = false
            } else {
                value.wrappedValue = false
                isDenied = true
            }
            await notifier.settingsChanged()
        }
    }

    private var journalTime: Binding<Date> {
        Binding(
            get: {
                Calendar.current.date(bySettingHour: journalMinutes / 60, minute: journalMinutes % 60, second: 0,
                                      of: .now) ?? .now
            },
            set: { date in
                let parts = Calendar.current.dateComponents([.hour, .minute], from: date)
                journalMinutes = (parts.hour ?? 21) * 60 + (parts.minute ?? 0)
                Task { await notifier.settingsChanged() }
            }
        )
    }

    private var divider: some View {
        Rectangle()
            .fill(Palette.line)
            .frame(height: 1)
    }
}
