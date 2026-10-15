import SwiftUI
import UIKit

/// One chat row: your message on the right in mint, TinyMe's on the left in white beside its face.
struct MessageBubble: View {
    let item: ChatItem
    var api: APIClient? = nil
    var onRetry: () -> Void = {}
    var onDelete: () -> Void = {}
    /// Gives the model for a card a reply refers to. Nil: cards aren't shown inline.
    var inlineCard: ((String) -> InlineCardModel)?
    var onChangeGoal: (CardDTO) -> Void = { _ in }
    var onOpenPlan: ((String) -> Void)?
    var onOpenPlace: ((String) -> Void)?
    var onOpenPlaceCandidate: ((PlaceCandidateDTO) -> Void)?

    var body: some View {
        switch item {
        case .user(_, let text, let photoData, let photo, let status, _):
            userRow(text: text, photoData: photoData, photo: photo, status: status)
        case .assistant(_, let text, let actions, let status, _):
            assistantRow(text: text, actions: actions, status: status)
        }
    }

    // MARK: - You

    private func userRow(text: String, photoData: Data?, photo: ChatPhotoDTO?, status: ChatItem.UserStatus) -> some View {
        let isFailed: Bool
        let failureMessage: String?
        switch status {
        case .failed(let message):
            isFailed = true
            failureMessage = message
        default:
            isFailed = false
            failureMessage = nil
        }
        return HStack {
            Spacer(minLength: 56)
            VStack(alignment: .trailing, spacing: 4) {
                VStack(alignment: .trailing, spacing: Space.s2) {
                    if let photoData {
                        ChatPhotoPreview(data: photoData)
                    } else if let photo, let api {
                        ChatPhotoPreview(api: api, photo: photo)
                    }
                    if !text.isEmpty {
                        Text(text)
                            .textStyle(.body)
                            .foregroundStyle(isFailed ? Palette.onAccentSoft : Palette.onBrand)
                            .accessibilityLabel(Self.userLabel(text, status))
                    }
                }
                .padding(.horizontal, Space.s4)
                .padding(.vertical, Space.s3)
                .background(isFailed ? Palette.accentSoft : Palette.brand, in: Self.shape(tail: .trailing))
                .opacity(status == .sending || status == .waiting ? 0.6 : 1)
                if status == .waiting {
                    Label("Waiting for connection", systemImage: "clock")
                        .textStyle(.caption)
                        .foregroundStyle(Palette.inkMuted)
                        .accessibilityHidden(true)   // the bubble says it
                } else if isFailed {
                    HStack(spacing: Space.s2) {
                        Text("Not sent")
                            .textStyle(.caption)
                            .foregroundStyle(Palette.onAccentSoft)
                            .accessibilityHidden(true)
                        failedAction("Retry", action: onRetry)
                        failedAction("Delete", action: onDelete)
                    }
                    if let failureMessage, !failureMessage.isEmpty {
                        Text(failureMessage)
                            .textStyle(.caption)
                            .foregroundStyle(Palette.danger)
                            .multilineTextAlignment(.trailing)
                            .frame(maxWidth: 280, alignment: .trailing)
                    }
                }
            }
        }
    }

    private func failedAction(_ title: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(title)
                .textStyle(.caption, weight: .semibold)
                .foregroundStyle(Palette.onAccentSoft)
                .padding(.horizontal, Space.s3)
                .frame(height: 32)
                .background(Palette.accentSoft, in: Capsule())
                .frame(minHeight: 44)
        }
        .buttonStyle(.pressable)
    }

    private static func userLabel(_ text: String, _ status: ChatItem.UserStatus) -> String {
        switch status {
        case .waiting: "You, waiting for connection: \(text)"
        case .failed(let message): "You, not sent: \(text). \(message ?? "")"
        case .sending, .sent: "You: \(text)"
        }
    }

    // MARK: - TinyMe

    private func assistantRow(text: String, actions: [ActionDTO], status: ChatItem.AssistantStatus) -> some View {
        HStack(alignment: .bottom, spacing: Space.s2) {
            // Only the reply being written moves; a column of blinking faces would be too much.
            TinyMeFace(size: 28, mood: status == .streaming ? .thinking : .awake, isAlive: status == .streaming)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: Space.s2) {
                let visibleActions = actions.filter { $0.tool != "places_search" }
                if !visibleActions.isEmpty {
                    FlowLayout(spacing: 6) {
                        ForEach(Array(visibleActions.enumerated()), id: \.offset) { _, action in
                            ActionChip(action: action, onOpenPlan: onOpenPlan, onOpenPlace: onOpenPlace)
                        }
                    }
                }
                let candidateGroups = Self.placeCandidateGroups(in: actions)
                if !text.isEmpty {
                    Text(Self.formatted(text))
                        .textStyle(.body)
                        .foregroundStyle(Palette.ink)
                        .padding(.horizontal, Space.s4)
                        .padding(.vertical, Space.s3)
                        .background {
                            Self.shape(tail: .leading)
                                .fill(Palette.surfaceRaised)
                                .cardShadow()
                        }
                        .accessibilityLabel("TinyMe: \(text)")
                } else if status == .streaming {
                    ThinkingDots()
                        .padding(.horizontal, Space.s4)
                        .padding(.vertical, 18)
                        .background {
                            Self.shape(tail: .leading)
                                .fill(Palette.surfaceRaised)
                                .cardShadow()
                        }
                        .accessibilityLabel("TinyMe is thinking")
                }
                if !candidateGroups.isEmpty {
                    VStack(alignment: .leading, spacing: Space.s3) {
                        ForEach(candidateGroups) { group in
                            VStack(alignment: .leading, spacing: Space.s2) {
                                Text(group.label ?? "Places found")
                                    .textStyle(.caption, weight: .semibold)
                                    .foregroundStyle(Palette.inkMuted)
                                ForEach(group.candidates) { candidate in
                                    Button { onOpenPlaceCandidate?(candidate) } label: {
                                        HStack(spacing: Space.s3) {
                                            Image(systemName: "mappin.and.ellipse")
                                                .font(.system(size: 16, weight: .semibold))
                                                .foregroundStyle(Palette.brandStrong)
                                                .frame(width: 34, height: 34)
                                                .background(Palette.brandSoft, in: Circle())
                                            VStack(alignment: .leading, spacing: 2) {
                                                Text(candidate.name)
                                                    .textStyle(.label, weight: .semibold)
                                                    .foregroundStyle(Palette.ink)
                                                    .lineLimit(1)
                                                Text([candidate.address, candidate.distanceLabel].compactMap { $0 }.joined(separator: " · "))
                                                    .textStyle(.caption)
                                                    .foregroundStyle(Palette.inkMuted)
                                                    .lineLimit(1)
                                            }
                                            Spacer(minLength: 0)
                                            Image(systemName: "chevron.right")
                                                .font(.system(size: 12, weight: .semibold))
                                                .foregroundStyle(Palette.inkMuted)
                                        }
                                        .padding(.horizontal, Space.s3)
                                        .padding(.vertical, Space.s2)
                                        .background(Palette.surfaceRaised, in: RoundedRectangle(cornerRadius: Radius.md, style: .continuous))
                                        .cardShadow()
                                        .contentShape(RoundedRectangle(cornerRadius: Radius.md, style: .continuous))
                                    }
                                    .buttonStyle(.plain)
                                    .accessibilityHint("Open place details")
                                }
                            }
                        }
                    }
                    .padding(.top, Space.s2)
                }
                if let inlineCard {
                    ForEach(Self.cardIds(in: actions), id: \.self) { cardId in
                        InlineCardView(model: inlineCard(cardId), onChangeGoal: onChangeGoal)
                    }
                }
                if case .failed(let message, let retryable) = status {
                    failure(message: message, retryable: retryable)
                }
            }
            Spacer(minLength: 32)
        }
    }

    private func failure(message: String, retryable: Bool) -> some View {
        VStack(alignment: .leading, spacing: Space.s2) {
            Label(message, systemImage: "exclamationmark.circle")
                .textStyle(.label)
                .foregroundStyle(Palette.onAccentSoft)
            if retryable {
                Button(action: onRetry) {
                    Text("Try again")
                        .textStyle(.label, weight: .semibold)
                        .foregroundStyle(Palette.onInk)
                        .padding(.horizontal, Space.s4)
                        .frame(height: 36)
                        .background(Palette.ink, in: Capsule())
                        .frame(minHeight: 44)
                }
                .buttonStyle(.pressable)
            }
        }
        .padding(.horizontal, Space.s4)
        .padding(.vertical, Space.s3)
        .background(Palette.accentSoft, in: Self.shape(tail: .leading))
    }

    /// Each card the reply touched, once, in the order it came up.
    private static func cardIds(in actions: [ActionDTO]) -> [String] {
        var seen = Set<String>()
        return actions.compactMap(\.cardId).filter { seen.insert($0).inserted }
    }

    private static func placeCandidateGroups(in actions: [ActionDTO]) -> [PlaceCandidateGroup] {
        actions.compactMap { action in
            let candidates = action.placeCandidates
            guard !candidates.isEmpty else { return nil }
            return PlaceCandidateGroup(id: candidates[0].id, label: candidates[0].searchArea, candidates: candidates)
        }
    }

    // MARK: - Shared

    /// radius-md corners, with a small one where the bubble points at its speaker.
    private static func shape(tail: HorizontalEdge) -> UnevenRoundedRectangle {
        UnevenRoundedRectangle(
            topLeadingRadius: Radius.md,
            bottomLeadingRadius: tail == .leading ? 6 : Radius.md,
            bottomTrailingRadius: tail == .trailing ? 6 : Radius.md,
            topTrailingRadius: Radius.md,
            style: .continuous
        )
    }

    /// Replies may use **bold**, _italics_ and links; anything else stays as typed.
    private static func formatted(_ text: String) -> AttributedString {
        let options = AttributedString.MarkdownParsingOptions(interpretedSyntax: .inlineOnlyPreservingWhitespace)
        return (try? AttributedString(markdown: text, options: options)) ?? AttributedString(text)
    }
}

private struct PlaceCandidateGroup: Identifiable {
    let id: String
    let label: String?
    let candidates: [PlaceCandidateDTO]
}

private struct ChatPhotoPreview: View {
    var data: Data?
    var api: APIClient?
    var photo: ChatPhotoDTO?
    @State private var loadedData: Data?

    init(data: Data) {
        self.data = data
    }

    init(api: APIClient, photo: ChatPhotoDTO) {
        self.api = api
        self.photo = photo
    }

    var body: some View {
        Group {
            if let imageData = data ?? loadedData, let image = UIImage(data: imageData) {
                Image(uiImage: image)
                    .resizable()
                    .scaledToFit()
            } else {
                Rectangle()
                    .fill(Palette.surfaceSunken)
                    .overlay { ProgressView().tint(Palette.inkMuted) }
            }
        }
        .frame(maxWidth: 240, maxHeight: 260)
        .clipShape(RoundedRectangle(cornerRadius: Radius.md, style: .continuous))
        .task(id: photo?.id) {
            guard data == nil, loadedData == nil, let api, let photo else { return }
            loadedData = try? await api.getData(.chatPhotoContent(photo.id))
        }
        .accessibilityLabel("Photo attachment")
    }
}

/// Three bobbing dots, the middle one mint, while TinyMe works on a reply.
private struct ThinkingDots: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var bobbing = false

    var body: some View {
        HStack(spacing: 5) {
            ForEach(0..<3, id: \.self) { index in
                Circle()
                    .fill(index == 1 ? Palette.brand : Palette.ink)
                    .frame(width: 7, height: 7)
                    .offset(y: bobbing && !reduceMotion ? -4 : 0)
                    .animation(
                        .easeInOut(duration: 0.5).repeatForever(autoreverses: true).delay(Double(index) * 0.15),
                        value: bobbing
                    )
            }
        }
        .onAppear { bobbing = true }
    }
}
