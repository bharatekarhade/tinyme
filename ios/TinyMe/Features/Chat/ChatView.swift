import SwiftUI

/// Today's conversation with TinyMe. Opens full screen from the + button on Home.
struct ChatView: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.scenePhase) private var scenePhase
    @State private var model: ChatModel
    @State private var speech = SpeechService()
    /// What was in the composer when the mic was pressed; spoken words are added after it.
    @State private var textBeforeVoice = ""
    @AppStorage("voice.sendOnRelease") private var sendOnRelease = false
    @FocusState private var composerFocused: Bool

    private let serverName: String
    private let bottomID = "chat-bottom"

    /// - Parameters:
    ///   - draft: text to put in the composer (a tapped suggestion). It is not sent.
    ///   - onDataChanged: called after a turn that changed entries or cards.
    init(
        api: APIClient,
        stream: StreamClient,
        draft: String = "",
        serverName: String,
        onDataChanged: @escaping @MainActor () -> Void = {}
    ) {
        _model = State(initialValue: ChatModel(api: api, stream: stream, draft: draft, onDataChanged: onDataChanged))
        self.serverName = serverName
    }

    var body: some View {
        @Bindable var model = model

        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(spacing: 10) {
                    if model.items.isEmpty {
                        emptyState
                    }
                    ForEach(Array(model.items.enumerated()), id: \.element.id) { index, item in
                        if startsNewRun(at: index) {
                            Text(Self.timeLabel(item.createdAt))
                                .textStyle(.caption)
                                .foregroundStyle(Palette.inkMuted)
                                .padding(.top, index == 0 ? 0 : Space.s2)
                        }
                        MessageBubble(
                            item: item,
                            onRetry: { model.retry(item.id) },
                            inlineCard: model.inlineCard(for:),
                            onChangeGoal: { card in
                                model.draftGoalChange(for: card)
                                composerFocused = true
                            }
                        )
                    }
                    Color.clear
                        .frame(height: 1)
                        .id(bottomID)
                }
                .padding(.horizontal, Space.s4)
                .padding(.vertical, Space.s3)
            }
            .defaultScrollAnchor(.bottom)
            .scrollDismissesKeyboard(.interactively)
            .refreshable {
                await model.loadHistory()
            }
            .onChange(of: model.items) {
                withAnimation(.snappy(duration: 0.25)) {
                    proxy.scrollTo(bottomID, anchor: .bottom)
                }
            }
            .onChange(of: composerFocused) {
                proxy.scrollTo(bottomID, anchor: .bottom)
            }
        }
        .background(Palette.surface.ignoresSafeArea())
        .safeAreaInset(edge: .top, spacing: 0) {
            topBar
        }
        .safeAreaInset(edge: .bottom, spacing: 0) {
            VStack(spacing: 0) {
                if let notice = speech.notice {
                    Text(notice)
                        .textStyle(.caption)
                        .foregroundStyle(Palette.inkMuted)
                        .multilineTextAlignment(.center)
                        .padding(.horizontal, Space.s6)
                        .transition(.opacity)
                }
                Composer(
                    text: $model.composer,
                    canSend: model.canSend,
                    isBusy: model.isTurnRunning,
                    isListening: speech.isListening,
                    isFocused: $composerFocused,
                    onSend: model.send,
                    onMicPress: {
                        textBeforeVoice = model.composer.trimmingCharacters(in: .whitespacesAndNewlines)
                        speech.press()
                    },
                    onMicRelease: speech.release
                )
            }
            .animation(.snappy(duration: 0.2), value: speech.notice)
        }
        .onChange(of: speech.transcript) { _, heard in
            // Live words go straight into the composer, after anything already typed.
            if !heard.isEmpty {
                model.composer = composed(with: heard)
            }
        }
        .onChange(of: speech.finishedCount) {
            model.composer = composed(with: speech.transcript)
            if sendOnRelease {
                model.send()
            }
        }
        .onDisappear {
            speech.release()
        }
        .task {
            await model.loadHistory()
            // Arriving with a suggestion: put the cursor in it, ready to edit or send.
            if !model.composer.isEmpty {
                composerFocused = true
            }
        }
        .onChange(of: scenePhase) { _, phase in
            // A turn may have finished while the app was in the background.
            if phase == .active {
                Task { await model.loadHistory() }
            }
        }
    }

    // MARK: - Pieces

    private var topBar: some View {
        HStack(spacing: 10) {
            Button {
                dismiss()
            } label: {
                Image(systemName: "chevron.left")
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundStyle(Palette.ink)
                    .frame(width: 40, height: 40)
                    .background(Palette.surfaceRaised.opacity(0.7), in: Circle())
                    .frame(width: 44, height: 44)
            }
            .buttonStyle(.pressable)
            .accessibilityLabel("Back to Home")

            TinyMeFace(size: 36)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 0) {
                Text("TinyMe")
                    .textStyle(.heading)
                    .foregroundStyle(Palette.ink)
                HStack(spacing: 6) {
                    Circle()
                        .fill(Palette.brand)
                        .frame(width: 6, height: 6)
                    Text(model.isTurnRunning ? "Thinking…" : "On \(serverName)")
                        .textStyle(.caption)
                        .foregroundStyle(Palette.inkMuted)
                        .lineLimit(1)
                }
            }
            .accessibilityElement(children: .combine)
            Spacer(minLength: 0)
        }
        .padding(.horizontal, Space.s3)
        .padding(.vertical, Space.s2)
        .background(.ultraThinMaterial)
        .overlay(alignment: .bottom) {
            Rectangle()
                .fill(Palette.line)
                .frame(height: 1)
        }
    }

    @ViewBuilder
    private var emptyState: some View {
        VStack(spacing: Space.s3) {
            if model.isLoadingHistory {
                ProgressView()
                    .tint(Palette.inkMuted)
            } else if let error = model.historyError {
                Text("Couldn't load today's chat")
                    .textStyle(.heading)
                    .foregroundStyle(Palette.ink)
                Text("\(error) Pull down to try again.")
                    .textStyle(.label, weight: .regular)
                    .foregroundStyle(Palette.inkMuted)
            } else {
                TinyMeFace(size: 56)
                    .accessibilityHidden(true)
                Text("What's on your mind?")
                    .textStyle(.title)
                    .foregroundStyle(Palette.ink)
                Text("Tell me what you did, ate or spent, or ask me to track something new.")
                    .textStyle(.body)
                    .foregroundStyle(Palette.inkMuted)
            }
        }
        .multilineTextAlignment(.center)
        .padding(.horizontal, Space.s6)
        .padding(.vertical, 80)
        .frame(maxWidth: .infinity)
    }

    private func composed(with heard: String) -> String {
        textBeforeVoice.isEmpty ? heard : textBeforeVoice + " " + heard
    }

    // MARK: - Timestamps

    /// One time label per run of messages, not per bubble: shown at the start and after a 10-minute gap.
    private func startsNewRun(at index: Int) -> Bool {
        guard index > 0 else { return true }
        return model.items[index].createdAt.timeIntervalSince(model.items[index - 1].createdAt) > 600
    }

    private static func timeLabel(_ date: Date) -> String {
        "Today · " + date.formatted(.dateTime.hour().minute())
    }
}
