import SwiftUI

/// Settings → Apple Health: what TinyMe may read, whether it saves to Health, follow-up questions,
/// how far back to import, and the Connect button that shows iOS's permission sheet.
struct AppleHealthSheet: View {
    let api: APIClient
    let server: URL
    let store: HealthSettingsStore
    var access = HealthAccess()

    @Environment(\.dismiss) private var dismiss
    @State private var draft = HealthSettingsStore.Settings()
    @State private var isConnecting = false
    @State private var error: String?
    @State private var notice: String?
    @State private var writeStatus: HealthAccess.WriteStatus = .notAsked

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: Space.s4) {
                header
                Text("TinyMe reads these to fill your cards and answer your questions. Your data goes only to your own TinyMe server.")
                    .textStyle(.label, weight: .regular)
                    .foregroundStyle(Palette.inkMuted)
                    .padding(.horizontal, 4)

                VStack(spacing: 0) {
                    ForEach(Array(HealthGroup.allCases.enumerated()), id: \.element) { index, group in
                        if index > 0 { divider }
                        toggle(group.title, detail: group.detail, symbol: group.symbol, isOn: groupBinding(group))
                    }
                }
                .background(card)

                VStack(alignment: .leading, spacing: 0) {
                    toggle("Save to Health", detail: "Meals, water and weight you tell TinyMe",
                           symbol: "square.and.arrow.down.on.square", isOn: $draft.writeEnabled)
                    if draft.writeEnabled, let line = writeLine {
                        Text(line)
                            .textStyle(.caption)
                            .foregroundStyle(writeStatus == .allowed ? Palette.inkMuted : Palette.danger)
                            .padding(.leading, 18 + 28 + Space.s3)
                            .padding(.trailing, 18)
                            .padding(.bottom, 14)
                    }
                }
                .background(card)

                section("Follow-up questions",
                        note: "Now and then TinyMe may ask one short question when your data says something worth asking about.") {
                    VStack(spacing: 0) {
                        ForEach(Array(HealthNudgeTopic.allCases.enumerated()), id: \.element) { index, topic in
                            if index > 0 { divider }
                            toggle(topic.title, detail: nil, symbol: nil, isOn: topicBinding(topic))
                        }
                    }
                    .background(card)
                }

                if !store.isConnected {
                    section("History to import", note: nil) {
                        PillSwitcher(options: HealthImportDepth.allCases, selection: $draft.importDepth,
                                     title: \.title, label: "History to import")
                    }
                }

                if let message = error ?? Self.insecureServerMessage(server) {
                    Label(message, systemImage: "exclamationmark.circle")
                        .textStyle(.label)
                        .foregroundStyle(Palette.danger)
                        .padding(.horizontal, Space.s4)
                        .padding(.vertical, Space.s3)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .background(Palette.surfaceSunken, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
                } else if store.saved.needsPush, !hasChanges {
                    Label("Your server hasn't got these yet. TinyMe sends them when it can reach it.", systemImage: "arrow.clockwise")
                        .textStyle(.label)
                        .foregroundStyle(Palette.inkMuted)
                        .padding(.horizontal, 4)
                } else if let notice {
                    Label(notice, systemImage: "checkmark.circle")
                        .textStyle(.label)
                        .foregroundStyle(Palette.brandStrong)
                        .padding(.horizontal, 4)
                }

                connectButton
                if store.isConnected, draft.isAllOff, hasChanges {
                    Text("Imported data stays. Removing it comes with Disconnect.")
                        .textStyle(.caption)
                        .foregroundStyle(Palette.inkMuted)
                        .frame(maxWidth: .infinity)
                }

                Text("When TinyMe answers from your Health data, those numbers are sent to Anthropic as part of that chat, like everything else you tell it. iOS keeps its permissions until you change them in Health → Sharing → Apps → TinyMe.")
                    .textStyle(.caption)
                    .foregroundStyle(Palette.inkMuted)
                    .padding(.horizontal, 4)
            }
            .padding(Space.s4)
            .padding(.top, Space.s2)
        }
        .scrollBounceBehavior(.basedOnSize)
        .foregroundStyle(Palette.ink)
        .background(Palette.surface.ignoresSafeArea())
        .presentationDetents([.large])
        .interactiveDismissDisabled(isConnecting)
        .animation(.snappy(duration: 0.25), value: draft)
        .animation(.snappy(duration: 0.25), value: error)
        .onAppear {
            draft = store.saved
            writeStatus = access.writeStatus
        }
        .task { await store.pushIfNeeded(api: api) }
        .onChange(of: draft.choices) {
            error = nil
            notice = nil
        }
    }

    // MARK: - Pieces

    private var header: some View {
        HStack {
            Text("Apple Health")
                .textStyle(.title, weight: .semibold)
                .accessibilityAddTraits(.isHeader)
            Spacer()
            Button { dismiss() } label: {
                Image(systemName: "xmark")
                    .font(.system(size: 14, weight: .bold))
                    .frame(width: 40, height: 40)
                    .background(Palette.surfaceSunken, in: Circle())
                    .frame(width: 44, height: 44)
            }
            .buttonStyle(.pressable)
            .disabled(isConnecting)
            .accessibilityLabel("Close")
        }
    }

    private var connectButton: some View {
        Button(action: connect) {
            HStack(spacing: 10) {
                if isConnecting { ProgressView().tint(Palette.onBrand) }
                Text(buttonTitle).textStyle(.heading)
            }
            .foregroundStyle(canConnect ? Palette.onBrand : Palette.inkMuted)
            .frame(maxWidth: .infinity)
            .frame(height: 56)
            .background(canConnect ? Palette.brand : Palette.surfaceSunken, in: Capsule())
        }
        .buttonStyle(.pressable)
        .disabled(!canConnect)
    }

    private func toggle(_ title: String, detail: String?, symbol: String?, isOn: Binding<Bool>) -> some View {
        Toggle(isOn: isOn) {
            HStack(spacing: Space.s3) {
                if let symbol {
                    Image(systemName: symbol)
                        .font(.system(size: 17, weight: .medium))
                        .foregroundStyle(Palette.brandStrong)
                        .frame(width: 28)
                        .accessibilityHidden(true)
                }
                VStack(alignment: .leading, spacing: 2) {
                    Text(title).textStyle(.label, weight: .semibold)
                    if let detail {
                        Text(detail).textStyle(.caption).foregroundStyle(Palette.inkMuted)
                    }
                }
            }
        }
        .tint(Palette.brand)
        .disabled(isConnecting)
        .padding(.horizontal, 18)
        .padding(.vertical, 14)
    }

    private func section(_ title: String, note: String?, @ViewBuilder content: () -> some View) -> some View {
        VStack(alignment: .leading, spacing: Space.s2) {
            Text(title)
                .textStyle(.label, weight: .semibold)
                .foregroundStyle(Palette.inkMuted)
                .padding(.horizontal, 4)
            content()
            if let note {
                Text(note)
                    .textStyle(.caption)
                    .foregroundStyle(Palette.inkMuted)
                    .padding(.horizontal, 4)
            }
        }
    }

    private var divider: some View {
        Rectangle().fill(Palette.line).frame(height: 1).padding(.leading, 18)
    }

    private var card: some View {
        RoundedRectangle(cornerRadius: Radius.lg, style: .continuous)
            .fill(Palette.surfaceRaised)
            .cardShadow()
    }

    // MARK: - State

    private func groupBinding(_ group: HealthGroup) -> Binding<Bool> {
        Binding(get: { draft.groups.contains(group) },
                set: { on in if on { draft.groups.insert(group) } else { draft.groups.remove(group) } })
    }

    /// On means TinyMe may ask about it; the server stores the topics that are off.
    private func topicBinding(_ topic: HealthNudgeTopic) -> Binding<Bool> {
        Binding(get: { !draft.nudgesOff.contains(topic) },
                set: { on in if on { draft.nudgesOff.remove(topic) } else { draft.nudgesOff.insert(topic) } })
    }

    private var hasChanges: Bool { draft.choices != store.saved.choices }

    /// Connecting needs something switched on; once connected, any change can be saved, including all off.
    private var canConnect: Bool {
        guard !isConnecting, Self.insecureServerMessage(server) == nil else { return false }
        if store.isConnected { return hasChanges }
        return !draft.isAllOff
    }

    private var buttonTitle: String {
        if isConnecting { return store.isConnected ? "Saving…" : "Connecting…" }
        if !store.isConnected { return "Connect" }
        guard hasChanges else { return "Connected" }
        return draft.isAllOff ? "Stop reading Health" : "Save changes"
    }

    private var writeLine: String? {
        switch writeStatus {
        case .notAsked: nil
        case .allowed: "Allowed in Health"
        case .notAllowed: "Not allowed. Turn it on in Health → Sharing → Apps → TinyMe."
        case .partly(let missing):
            "Partly allowed: \(missing.formatted(.list(type: .and))) not allowed. Change it in Health → Sharing → Apps → TinyMe."
        }
    }

    // MARK: - Actions

    private func connect() {
        guard canConnect else { return }
        isConnecting = true
        error = nil
        notice = nil
        let wanted = draft
        let wasConnected = store.isConnected
        Task {
            defer { isConnecting = false }
            if !wanted.isAllOff {
                do {
                    // iOS only shows types it hasn't asked about, so this is quiet when nothing new is on.
                    try await access.request(groups: wanted.groups, write: wanted.writeEnabled)
                } catch {
                    self.error = "iOS didn't open the Health permissions. Please try again."
                    return
                }
            }
            var next = wanted
            next.askedGroups.formUnion(wanted.groups)
            next.askedToWrite = next.askedToWrite || wanted.writeEnabled
            // Stopping everything still counts as connected, so the screen keeps offering to switch back on.
            if next.askedGroups.isEmpty { next.askedGroups = store.saved.askedGroups }
            writeStatus = access.writeStatus
            do {
                // Kept on this phone first: if the server misses it, it's sent again on the next foreground.
                try await store.save(next, sendingWith: api)
                draft = store.saved
                notice = next.isAllOff ? "TinyMe has stopped reading Health."
                    : wasConnected ? "Saved." : "Connected. TinyMe will start reading your Health data soon."
            } catch {
                draft = store.saved
                self.error = error is URLError
                    ? "Saved on this phone, but your server couldn't be reached. TinyMe sends it when it can."
                    : "Saved on this phone, but your server didn't take it. TinyMe tries again later."
            }
        }
    }

    /// Health data only travels over HTTPS, except to a server on this phone or Mac.
    static func insecureServerMessage(_ server: URL) -> String? {
        guard server.scheme?.lowercased() == "http" else { return nil }
        let host = server.host()?.lowercased() ?? ""
        if host == "localhost" || host == "127.0.0.1" || host == "::1" { return nil }
        return "Your server uses http://, so Health data would travel unencrypted. Connect TinyMe to an https:// address first."
    }
}
