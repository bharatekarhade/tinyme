import SwiftUI

/// A durable, explicit confirmation for sending one Gmail draft.
struct GmailSendCard: View {
    let confirmation: GmailSendConfirmationDTO
    let isSending: Bool
    let error: String?
    let status: String?
    let onSend: () -> Void
    let onCancel: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: Space.s3) {
            Label("Send this email?", systemImage: "envelope")
                .textStyle(.label, weight: .semibold)
                .foregroundStyle(Palette.ink)
            VStack(alignment: .leading, spacing: Space.s2) {
                Text("To: \(confirmation.to)")
                Text("Subject: \(confirmation.subject.isEmpty ? "(no subject)" : confirmation.subject)")
                    .fontWeight(.semibold)
                if !confirmation.preview.isEmpty {
                    Text(confirmation.preview)
                        .lineLimit(4)
                        .foregroundStyle(Palette.inkMuted)
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
                Label(status == "sent" ? "Email sent" : "Email canceled",
                      systemImage: status == "sent" ? "checkmark.circle.fill" : "xmark.circle")
                    .textStyle(.label, weight: .semibold)
                    .foregroundStyle(status == "sent" ? Palette.brandStrong : Palette.inkMuted)
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
                            Text(isSending ? "Sending…" : "Send")
                                .textStyle(.label, weight: .semibold)
                        }
                        .foregroundStyle(Palette.onBrand)
                        .frame(maxWidth: .infinity, minHeight: 44)
                        .background(Palette.brand, in: Capsule())
                    }
                    .disabled(isSending)
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
}
