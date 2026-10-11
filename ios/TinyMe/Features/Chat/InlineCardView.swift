import SwiftUI

/// A card inside the chat: a draft offers Add to Home and Change goal; an active one says it's on Home.
struct InlineCardView: View {
    let model: InlineCardModel
    var onChangeGoal: (CardDTO) -> Void

    var body: some View {
        Group {
            switch model.state {
            case .loaded(let card):
                VStack(alignment: .leading, spacing: Space.s2) {
                    CardView(card: card, range: .week, isPreview: card.status == "draft")
                    if card.status == "draft" {
                        draftActions(for: card)
                    } else {
                        ActionChip(action: ActionDTO(
                            tool: "",
                            summary: model.justAdded ? "Added to Home" : "On Home",
                            isError: false,
                            ref: nil
                        ))
                    }
                    if let error = model.error {
                        Text(error)
                            .textStyle(.caption)
                            .foregroundStyle(Palette.danger)
                    }
                }
                .transition(.opacity)
            case .loading:
                RoundedRectangle(cornerRadius: Radius.lg, style: .continuous)
                    .fill(Palette.surfaceSunken)
                    .frame(height: 120)
                    .accessibilityLabel("Loading the card")
            case .idle:
                // Something has to be on screen for `.task` below to start the load.
                Color.clear
                    .frame(height: 1)
                    .accessibilityHidden(true)
            case .hidden:
                EmptyView()
            }
        }
        .animation(.snappy(duration: 0.25), value: model.state)
        .task {
            await model.load()
        }
    }

    private func draftActions(for card: CardDTO) -> some View {
        HStack(spacing: Space.s2) {
            Button {
                Task { await model.activate() }
            } label: {
                HStack(spacing: Space.s2) {
                    if model.isActivating {
                        ProgressView()
                            .tint(Palette.onInk)
                    }
                    Text("Add to Home")
                        .textStyle(.label, weight: .semibold)
                }
                .foregroundStyle(Palette.onInk)
                .frame(maxWidth: .infinity)
                .frame(height: 44)
                .background(Palette.ink, in: Capsule())
            }
            .disabled(model.isActivating)

            Button {
                onChangeGoal(card)
            } label: {
                Text("Change goal")
                    .textStyle(.label)
                    .foregroundStyle(Palette.ink)
                    .padding(.horizontal, Space.s4)
                    .frame(height: 44)
                    .background(Palette.surfaceSunken, in: Capsule())
            }
        }
        .buttonStyle(.pressable)
    }
}
