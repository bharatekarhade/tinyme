import SwiftUI

/// Something TinyMe did during a reply: "✓ Logged drink (2 today)".
struct ActionChip: View {
    let action: ActionDTO
    var onOpenPlan: ((String) -> Void)?

    var body: some View {
        let content = HStack(spacing: 6) {
            Image(systemName: action.isError ? "exclamationmark.circle" : "checkmark")
                .font(.system(size: 12, weight: .bold))
                .foregroundStyle(action.isError ? Palette.danger : Palette.brandStrong)
                .accessibilityHidden(true)
            Text(action.summary)
                .textStyle(.caption, weight: .semibold)
                .lineLimit(2)
        }
        .padding(.horizontal, Space.s3)
        .frame(minHeight: 30)
        .background {
            Capsule()
                .fill(Palette.surfaceRaised)
                .cardShadow()
        }
        .accessibilityElement(children: .combine)
        .accessibilityLabel(action.isError ? "Couldn't do it: \(action.summary)" : action.summary)

        if let planId = action.planId, let onOpenPlan {
            Button { onOpenPlan(planId) } label: { content }
                .buttonStyle(.plain)
                .accessibilityHint("Open this plan in Upcoming")
        } else {
            content
        }
    }
}
