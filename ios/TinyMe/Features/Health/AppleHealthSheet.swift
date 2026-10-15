import SwiftUI

/// Settings → Apple Health: what TinyMe may read, follow-up questions, how far back to import, and the
/// Connect button that shows iOS's permission sheet. TinyMe only reads Health; it never writes to it.
struct AppleHealthSheet: View {
    let api: APIClient
    let server: URL
    let store: HealthSettingsStore
    let sync: HealthSync
    var access = HealthAccess()

    @Environment(\.dismiss) private var dismiss
    @State private var draft = HealthSettingsStore.Settings()
    @State private var isConnecting = false
    @State private var error: String?
    @State private var notice: String?
    @State private var status: HealthStatusDTO?
    @State private var confirmsDisconnect = false
    @State private var isDisconnecting = false
    /// Set when the screen itself resets the switches, so the line it just showed stays.
    @State private var keepsMessage = false

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

                if store.isConnected {
                    syncedSection
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
                } else if let line = sync.importLine, !hasChanges {
                    HStack(spacing: Space.s2) {
                        ProgressView().tint(Palette.brandStrong)
                        Text(line).textStyle(.label, weight: .semibold)
                    }
                    .padding(.horizontal, 4)
                } else if case .failed(let message) = sync.phase, !hasChanges {
                    Label(message, systemImage: "exclamationmark.circle")
                        .textStyle(.label)
                        .foregroundStyle(Palette.inkMuted)
                        .padding(.horizontal, 4)
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

                if store.isConnected {
                    Button(role: .destructive) { confirmsDisconnect = true } label: {
                        HStack(spacing: 10) {
                            if isDisconnecting { ProgressView().tint(Palette.danger) }
                            Text(isDisconnecting ? "Disconnecting…" : "Disconnect and remove imported data")
                                .textStyle(.label, weight: .semibold)
                        }
                        .foregroundStyle(Palette.danger)
                        .frame(maxWidth: .infinity)
                        .frame(height: 48)
                        .overlay(Capsule().strokeBorder(Palette.danger, lineWidth: 1.5))
                        .contentShape(Capsule())
                    }
                    .buttonStyle(.pressable)
                    .disabled(isConnecting || isDisconnecting)
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
        }
        .task {
            await store.pushIfNeeded(api: api)
            await loadStatus()
        }
        .onChange(of: sync.lastSyncedAt) { Task { await loadStatus() } }
        .confirmationDialog("Disconnect Apple Health?", isPresented: $confirmsDisconnect, titleVisibility: .visible) {
            Button("Disconnect and remove data", role: .destructive, action: disconnect)
            Button("Cancel", role: .cancel) { }
        } message: {
            Text("TinyMe stops reading Health and removes the entries it imported. Your notes, workouts you told it and anything you added stay. iOS keeps its permission until you change it in Health → Sharing → Apps → TinyMe.")
        }
        .onChange(of: draft.choices) {
            if keepsMessage { keepsMessage = false; return }
            error = nil
            notice = nil
        }
    }

    // MARK: - Pieces

    /// "Steps · synced 5 min ago · 31 days", per kind of each switched-on group, with a hint for a group
    /// that has sent nothing in a week (read permission is private, so this is the only sign of a denied type).
    @ViewBuilder
    private var syncedSection: some View {
        let groups = HealthGroup.allCases.filter(store.saved.groups.contains)
        if !groups.isEmpty {
            section("Synced", note: [quietGroupsHint(groups), backgroundLine].compactMap { $0 }.joined(separator: "\n\n")) {
                VStack(spacing: 0) {
                    let metrics = HealthCatalog.metrics.filter { groups.contains($0.group) }
                    ForEach(Array(metrics.enumerated()), id: \.element.kind) { index, metric in
                        if index > 0 { divider }
                        HStack(spacing: Space.s3) {
                            Text(HealthCatalog.title(of: metric.kind)).textStyle(.label, weight: .semibold)
                            Spacer(minLength: Space.s2)
                            Text(syncedLine(metric.kind))
                                .textStyle(.caption)
                                .foregroundStyle(Palette.inkMuted)
                                .lineLimit(1)
                        }
                        .padding(.horizontal, 18)
                        .padding(.vertical, 12)
                        .accessibilityElement(children: .combine)
                    }
                }
                .background(card)
            }
        }
    }

    /// Whether iOS is waking TinyMe in the background: the last wake-up, or why iOS refused.
    private var backgroundLine: String {
        if let refusal = HealthBackgroundLog.lastRefusal() { return "Background updates: iOS refused (\(refusal))" }
        guard let (date, kind) = HealthBackgroundLog.lastWake() else {
            return "Background updates: iOS hasn't woken TinyMe yet. Leave it in the background; don't swipe it away."
        }
        return "Background updates: last \(date.formatted(.relative(presentation: .named))) (\(HealthCatalog.title(of: kind)))"
    }

    private func syncedLine(_ kind: String) -> String {
        guard let status else { return "…" }
        guard let row = status.kinds.first(where: { $0.kind == kind }), row.itemsTotal > 0 else { return "No data yet" }
        let ago = row.lastSyncAt.formatted(.relative(presentation: .named, unitsStyle: .abbreviated))
        let count = kind == "workout" ? "\(row.itemsTotal) workouts" : kind == "sleep" ? "\(row.itemsTotal) nights"
            : row.itemsTotal == 1 ? "1 entry" : "\(row.itemsTotal) days"
        return "synced \(ago) · \(count)"
    }

    private func quietGroupsHint(_ groups: [HealthGroup]) -> String? {
        guard let status else { return nil }
        let weekAgo = HealthImportPlan.adding(-7, to: .today())
        let quiet = groups.filter { group in
            !HealthCatalog.metrics.filter { $0.group == group }.contains { metric in
                status.kinds.contains { $0.kind == metric.kind && ($0.lastDay ?? weekAgo) > weekAgo }
            }
        }
        guard !quiet.isEmpty else { return nil }
        return "No data yet for \(quiet.map(\.title).formatted(.list(type: .and))). If that's unexpected: Health → Sharing → Apps → TinyMe."
    }

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

    // MARK: - Actions

    private func loadStatus() async {
        guard store.isConnected else { status = nil; return }
        status = try? await api.get(.healthStatus)
    }

    private func disconnect() {
        isDisconnecting = true
        error = nil
        notice = nil
        Task {
            defer { isDisconnecting = false }
            do {
                let reply: HealthDisconnectReply = try await api.send(.deleteHealthData, body: HealthDisconnectBody())
                store.reset()
                sync.disconnected()
                keepsMessage = draft.choices != store.saved.choices
                draft = store.saved
                status = nil
                notice = reply.removed == 0 ? "Disconnected." : "Disconnected. \(reply.removed) imported entries removed."
            } catch {
                self.error = error is URLError
                    ? "Couldn't reach your server, so nothing was removed. Try again."
                    : "Your server couldn't remove the Health data. Please try again."
            }
        }
    }

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
                    try await access.request(groups: wanted.groups)
                } catch {
                    self.error = "iOS didn't open the Health permissions. Please try again."
                    return
                }
            }
            var next = wanted
            next.askedGroups.formUnion(wanted.groups)
            // Stopping everything still counts as connected, so the screen keeps offering to switch back on.
            if next.askedGroups.isEmpty { next.askedGroups = store.saved.askedGroups }
            do {
                // Kept on this phone first: if the server misses it, it's sent again on the next foreground.
                try await store.save(next, sendingWith: api)
                draft = store.saved
                sync.start(api: api)
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
