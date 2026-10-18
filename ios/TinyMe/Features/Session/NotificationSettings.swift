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
        }
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
