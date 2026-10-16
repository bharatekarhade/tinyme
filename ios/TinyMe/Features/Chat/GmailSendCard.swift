import SwiftUI

/// A durable, explicit confirmation for a Google account side effect.
struct GoogleConfirmationCard: View {
    let confirmation: GooglePendingConfirmationDTO
    let isSending: Bool
    let error: String?
    let status: String?
    let onSend: () -> Void
    let onCancel: () -> Void
    @State private var showingFullEmail = false

    var body: some View {
        VStack(alignment: .leading, spacing: Space.s3) {
            Label(isCalendar ? "Reply to this invitation?" : "Send this email?",
                  systemImage: isCalendar ? "calendar" : "envelope")
                .textStyle(.label, weight: .semibold)
                .foregroundStyle(Palette.ink)
            VStack(alignment: .leading, spacing: Space.s2) {
                if isCalendar {
                    Text(confirmation.title ?? "Calendar invitation")
                        .fontWeight(.semibold)
                    if let startsAt = confirmation.startsAt, !startsAt.isEmpty {
                        Text(startsAt)
                    }
                    Text("Your response: \(responseLabel)")
                } else {
                    Text("To: \(confirmation.to)")
                    if !confirmation.cc.isEmpty { Text("Cc: \(confirmation.cc)") }
                    if !confirmation.bcc.isEmpty { Text("Bcc: \(confirmation.bcc)") }
                    Text("Subject: \(confirmation.subject.isEmpty ? "(no subject)" : confirmation.subject)")
                        .fontWeight(.semibold)
                    if !confirmation.attachments.isEmpty {
                        Text("Attachments: \(confirmation.attachments.joined(separator: ", "))")
                            .foregroundStyle(Palette.inkMuted)
                    }
                }
                if !isCalendar && !confirmation.preview.isEmpty {
                    Text(confirmation.preview)
                        .lineLimit(4)
                        .foregroundStyle(Palette.inkMuted)
                }
                if !isCalendar && !confirmation.body.isEmpty {
                    Button(showingFullEmail ? "Full email" : "Show full email") {
                        showingFullEmail = true
                    }
                    .textStyle(.caption, weight: .semibold)
                    .foregroundStyle(Palette.brandStrong)
                    .disabled(showingFullEmail)
                    if showingFullEmail {
                        ScrollView {
                            Text(confirmation.body)
                                .frame(maxWidth: .infinity, alignment: .leading)
                                .textSelection(.enabled)
                        }
                        .frame(maxHeight: 220)
                        .padding(Space.s2)
                        .background(Palette.surfaceSunken, in: RoundedRectangle(cornerRadius: Radius.sm))
                    }
                }
            }
            .textStyle(.caption)
            .foregroundStyle(Palette.ink)

            if let error, !error.isEmpty {
                Text(error)
                    .textStyle(.caption)
                    .foregroundStyle(Palette.danger)
            }

            if let status {
                Label(completionLabel,
                      systemImage: isComplete ? "checkmark.circle.fill" : "xmark.circle")
                    .textStyle(.label, weight: .semibold)
                    .foregroundStyle(isComplete ? Palette.brandStrong : Palette.inkMuted)
            } else {
                HStack(spacing: Space.s2) {
                    Button(action: onCancel) {
                        Text("Cancel")
                            .textStyle(.label, weight: .semibold)
                            .foregroundStyle(Palette.ink)
                            .frame(maxWidth: .infinity, minHeight: 44)
                            .background(Palette.surfaceSunken, in: Capsule())
                    }
                    .disabled(isSending)
                    .buttonStyle(.pressable)

                    Button(action: onSend) {
                        HStack(spacing: Space.s2) {
                            if isSending { ProgressView().tint(Palette.onBrand) }
                            Text(isSending ? (isCalendar ? "Sending…" : "Sending…") : (isCalendar ? "Confirm response" : "Send"))
                                .textStyle(.label, weight: .semibold)
                        }
                        .foregroundStyle(Palette.onBrand)
                        .frame(maxWidth: .infinity, minHeight: 44)
                        .background(Palette.brand, in: Capsule())
                    }
                    .disabled(isSending || needsFullReview)
                    .buttonStyle(.pressable)
                }
            }
        }
        .padding(Space.s3)
        .frame(maxWidth: 340, alignment: .leading)
        .background(Palette.surfaceRaised, in: RoundedRectangle(cornerRadius: Radius.md, style: .continuous))
        .cardShadow()
        .accessibilityElement(children: .contain)
    }

    private var isCalendar: Bool { confirmation.kind == "calendar_invite" }
    private var needsFullReview: Bool { !isCalendar && !confirmation.body.isEmpty && !showingFullEmail }
    private var isComplete: Bool { isCalendar ? status == "responded" : status == "sent" }
    private var responseLabel: String {
        switch confirmation.response {
        case "accepted": "Accept"
        case "declined": "Decline"
        case "tentative": "Maybe"
        default: "Respond"
        }
    }
    private var completionLabel: String {
        if status == "rejected" { return isCalendar ? "Response canceled" : "Email canceled" }
        if status == "expired" { return "Draft changed — review it again" }
        return isCalendar ? "Invitation response sent" : "Email sent"
    }
}
