import SwiftUI
#if canImport(UIKit)
import UIKit
#endif

/// Opens from the profile button on Home: who is signed in, which server, and sign out.
/// A bottom panel drawn by the app, so it sits flush with the screen edges on every iOS version.
struct SettingsSheet: View {
    @Binding var isPresented: Bool
    var session: SessionModel
    /// Opens History, your whole log, over the tabs.
    var onOpenHistory: () -> Void = {}
    /// Called after "Delete all data" succeeded on the server.
    var onDataDeleted: () -> Void = {}

    @State private var dragOffset: CGFloat = 0

    var body: some View {
        ZStack(alignment: .bottom) {
            if isPresented {
                Palette.scrim
                    .ignoresSafeArea()
                    .onTapGesture(perform: close)
                    .transition(.opacity)
                    .accessibilityHidden(true)
                SettingsPanel(session: session, onClose: close, onOpenHistory: onOpenHistory, onDataDeleted: onDataDeleted)
                    .offset(y: dragOffset)
                    .gesture(dragToDismiss)
                    .transition(.offset(y: 800))
                    .zIndex(1)
            }
        }
        .animation(.snappy(duration: 0.32), value: isPresented)
    }

    private var dragToDismiss: some Gesture {
        DragGesture()
            .onChanged { dragOffset = max(0, $0.translation.height) }
            .onEnded { drag in
                if drag.translation.height > 120 || drag.predictedEndTranslation.height > 320 {
                    close()
                }
                withAnimation(.snappy(duration: 0.25)) { dragOffset = 0 }
            }
    }

    private func close() {
        isPresented = false
    }
}

private struct SettingsPanel: View {
    var session: SessionModel
    var onClose: () -> Void
    var onOpenHistory: () -> Void
    var onDataDeleted: () -> Void
    /// The currency spending is kept in; nil until loaded.
    @State private var currency: String?

    @AppStorage(Appearance.storageKey) private var appearance: Appearance = .system
    @AppStorage("voice.sendOnRelease") private var sendOnRelease = false
    @State private var isConfirming = false
    @State private var isSigningOut = false
    @State private var isExporting = false
    @State private var exportError: String?
    @State private var exportNotice: String?
    @State private var exportFile: DownloadedArchive?
    @State private var showsDeleteAll = false
    @State private var showsHealth = false
    @State private var showsGoogleDisconnect = false
    @State private var google = GoogleIntegrationModel()
    @Environment(AppEnvironment.self) private var environment
    @State private var status: ServerStatus = .checking

    private enum ServerStatus {
        case checking, connected, unreachable

        var label: String {
            switch self {
            case .checking: "Checking…"
            case .connected: "Connected"
            case .unreachable: "Can't reach it"
            }
        }

        var dot: Color {
            switch self {
            case .checking: Palette.inkMuted.opacity(0.4)
            case .connected: Palette.brand
            case .unreachable: Palette.accent
            }
        }
    }

    private var account: (server: URL, email: String)? {
        if case .signedIn(let server, let email) = session.state { (server, email) } else { nil }
    }

    var body: some View {
        // As tall as its content when that fits the screen; otherwise full height with the sections scrolling.
        ViewThatFits(in: .vertical) {
            layout(scrolls: false)
            layout(scrolls: true)
        }
        .foregroundStyle(Palette.ink)
        .background {
            UnevenRoundedRectangle(topLeadingRadius: 36, topTrailingRadius: 36, style: .continuous)
                .fill(Palette.surface)
                .shadow(color: .black.opacity(0.18), radius: 20, y: -12)
                .ignoresSafeArea(edges: .bottom)
        }
        .animation(.snappy(duration: 0.25), value: isConfirming)
        .accessibilityAddTraits(.isModal)
        .task {
            await checkServer()
            if let api = session.api {
                await google.refresh(api: api)
                if let loaded: CurrencySettingDTO = try? await api.get(.currency) { currency = loaded.currency }
            }
        }
        .sheet(item: $exportFile, onDismiss: removeExportFile) { file in
            ActivityShareSheet(items: [file.url])
                .presentationDetents([.medium, .large])
                .onDisappear { removeExportFile(file) }
        }
        .sheet(isPresented: $showsHealth) {
            if let api = session.api, let account {
                AppleHealthSheet(api: api, server: account.server, store: environment.health, sync: environment.healthSync)
            }
        }
        .sheet(isPresented: $showsGoogleDisconnect) {
            GoogleDisconnectPrompt(isWorking: google.isWorking, onCancel: { showsGoogleDisconnect = false }) { password in
                showsGoogleDisconnect = false
                guard let api = session.api else { return }
                Task { await google.disconnect(api: api, password: password) }
            }
            .presentationDetents([.height(330)])
            .presentationDragIndicator(.visible)
        }
        .sheet(isPresented: $showsDeleteAll) {
            if let api = session.api {
                DeleteAllSheet(
                    api: api,
                    onExportFirst: {
                        showsDeleteAll = false
                        exportData()
                    },
                    onDeleted: {
                        showsDeleteAll = false
                        onDataDeleted()
                    }
                )
            }
        }
        .alert("Couldn't export your data", isPresented: Binding(
            get: { exportError != nil },
            set: { if !$0 { exportError = nil } }
        )) {
            Button("OK") { exportError = nil }
        } message: {
            Text(exportError ?? "Please try again.")
        }
    }

    // MARK: - Pieces

    private func layout(scrolls: Bool) -> some View {
        VStack(alignment: .leading, spacing: 18) {
            Capsule()
                .fill(Palette.inkMuted.opacity(0.3))
                .frame(width: 40, height: 5)
                .frame(maxWidth: .infinity)
                .accessibilityHidden(true)
            HStack {
                Text("Settings")
                    .textStyle(.title, weight: .semibold)
                    .accessibilityAddTraits(.isHeader)
                Spacer()
                Button(action: onClose) {
                    Image(systemName: "xmark")
                        .font(.system(size: 14, weight: .bold))
                        .frame(width: 40, height: 40)
                        .background(Palette.surfaceSunken, in: Circle())
                        .frame(width: 44, height: 44)
                }
                .buttonStyle(.pressable)
                .accessibilityLabel("Close settings")
            }

            if scrolls {
                ScrollView {
                    sections
                        .padding(.horizontal, Space.s4)   // room for the cards' shadows inside the scroll area
                        .padding(.bottom, Space.s4)
                }
                .scrollIndicators(.hidden)
                .padding(.horizontal, -Space.s4)
            } else {
                sections
                Spacer(minLength: Space.s2)
            }

            if isConfirming {
                confirmCard
                    .transition(.opacity.combined(with: .move(edge: .bottom)))
            } else {
                signOutButton
                    .transition(.opacity)
            }
        }
        .padding(.horizontal, Space.s4)
        .padding(.top, 10)
        .padding(.bottom, Space.s4)
        .frame(minHeight: scrolls ? nil : 560, alignment: .top)
        .fixedSize(horizontal: false, vertical: !scrolls)
    }

    @ViewBuilder
    private var sections: some View {
        if let account {
            VStack(alignment: .leading, spacing: 18) {
                accountCard(email: account.email)
                historySection
                serverSection(server: account.server)
                currencySection
                appearanceSection
                voiceSection
                googleSection
                if HealthAccess.isAvailable { healthSection }
                dataSection
            }
        }
    }


    private func accountCard(email: String) -> some View {
        HStack(spacing: 14) {
            Text(email.prefix(1).uppercased())
                .textStyle(.heading)
                .foregroundStyle(Palette.onInk)
                .frame(width: 52, height: 52)
                .background(Palette.ink, in: Circle())
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 2) {
                Text(email)
                    .textStyle(.body, weight: .semibold)
                    .lineLimit(1)
                    .truncationMode(.middle)
                Text("Signed in")
                    .textStyle(.label, weight: .regular)
                    .foregroundStyle(Palette.inkMuted)
            }
            Spacer(minLength: 0)
        }
        .padding(Space.s4)
        .background(card)
        .accessibilityElement(children: .combine)
    }

    private func serverSection(server: URL) -> some View {
        VStack(alignment: .leading, spacing: Space.s2) {
            Text("Your server")
                .textStyle(.label, weight: .semibold)
                .foregroundStyle(Palette.inkMuted)
                .padding(.horizontal, 4)
            VStack(spacing: 0) {
                row("Address") {
                    Text(Self.displayAddress(server))
                        .lineLimit(1)
                        .truncationMode(.middle)
                }
                divider
                row("Status") {
                    HStack(spacing: Space.s2) {
                        Circle()
                            .fill(status.dot)
                            .frame(width: 8, height: 8)
                        Text(status.label)
                    }
                }
                divider
                row("This phone") {
                    Text(Self.deviceName)
                        .lineLimit(1)
                }
            }
            .background(card)
        }
    }

    private var historySection: some View {
        Button(action: onOpenHistory) {
            HStack(spacing: Space.s3) {
                Image(systemName: "clock")
                    .font(.system(size: 18, weight: .medium))
                    .foregroundStyle(Palette.brandStrong)
                VStack(alignment: .leading, spacing: 2) {
                    Text("History")
                        .textStyle(.label, weight: .semibold)
                    Text("Everything you've logged")
                        .textStyle(.caption)
                        .foregroundStyle(Palette.inkMuted)
                }
                Spacer(minLength: Space.s2)
                Image(systemName: "chevron.right")
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(Palette.inkMuted)
            }
            .padding(.horizontal, 18)
            .padding(.vertical, 14)
            .contentShape(Rectangle())
        }
        .buttonStyle(.pressable)
        .background(card)
    }

    /// Spending is kept in this currency; other currencies are converted at the day's rate.
    private var currencySection: some View {
        VStack(alignment: .leading, spacing: Space.s2) {
            Text("Money")
                .textStyle(.label, weight: .semibold)
                .foregroundStyle(Palette.inkMuted)
                .padding(.horizontal, 4)
            Menu {
                Picker("Currency", selection: Binding(get: { currency ?? "" }, set: { setCurrency($0) })) {
                    ForEach(currencyChoices, id: \.self) { code in
                        Text(CurrencySetting.name(code)).tag(code)
                    }
                }
            } label: {
                row("Currency") {
                    HStack(spacing: 4) {
                        Text(currency ?? "…")
                        Image(systemName: "chevron.up.chevron.down")
                            .font(.system(size: 11, weight: .semibold))
                            .foregroundStyle(Palette.inkMuted)
                    }
                    .foregroundStyle(Palette.ink)
                }
                .contentShape(Rectangle())
            }
            .disabled(currency == nil)
            .background(card)
            Text("Spending in other currencies is converted at that day's rate.")
                .textStyle(.caption)
                .foregroundStyle(Palette.inkMuted)
                .padding(.horizontal, 4)
        }
    }

    private var currencyChoices: [String] {
        var codes = CurrencySetting.common
        for extra in [currency, CurrencySetting.phoneCurrency].compactMap({ $0 }) where !codes.contains(extra) {
            codes.insert(extra, at: 0)
        }
        return codes
    }

    private func setCurrency(_ code: String) {
        guard let api = session.api, !code.isEmpty, code != currency else { return }
        let previous = currency
        currency = code
        Task {
            do {
                let saved: CurrencySettingDTO = try await api.send(.setCurrency, body: CurrencyRequest(currency: code))
                currency = saved.currency
            } catch {
                currency = previous
            }
        }
    }

    private var appearanceSection: some View {
        VStack(alignment: .leading, spacing: Space.s2) {
            Text("Appearance")
                .textStyle(.label, weight: .semibold)
                .foregroundStyle(Palette.inkMuted)
                .padding(.horizontal, 4)
            PillSwitcher(options: Appearance.allCases, selection: $appearance, title: \.title, label: "Appearance")
        }
    }

    private var voiceSection: some View {
        VStack(alignment: .leading, spacing: Space.s2) {
            Text("Voice")
                .textStyle(.label, weight: .semibold)
                .foregroundStyle(Palette.inkMuted)
                .padding(.horizontal, 4)
            Toggle(isOn: $sendOnRelease) {
                VStack(alignment: .leading, spacing: 2) {
                    Text("Send when I let go")
                        .textStyle(.label, weight: .semibold)
                    Text("Off: your words stay in the box so you can fix them first.")
                        .textStyle(.caption)
                        .foregroundStyle(Palette.inkMuted)
                }
            }
            .tint(Palette.brand)
            .padding(.horizontal, 18)
            .padding(.vertical, 14)
            .background(card)
        }
    }

    private var healthSection: some View {
        VStack(alignment: .leading, spacing: Space.s2) {
            Text("Apple Health")
                .textStyle(.label, weight: .semibold)
                .foregroundStyle(Palette.inkMuted)
                .padding(.horizontal, 4)
            Button {
                showsHealth = true
            } label: {
                HStack(spacing: Space.s3) {
                    Image(systemName: "heart.text.square")
                        .font(.system(size: 18, weight: .medium))
                        .foregroundStyle(Palette.brandStrong)
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Apple Health")
                            .textStyle(.label, weight: .semibold)
                        Text(healthSummary)
                            .textStyle(.caption)
                            .foregroundStyle(Palette.inkMuted)
                            .lineLimit(1)
                    }
                    Spacer(minLength: Space.s2)
                    Image(systemName: "chevron.right")
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundStyle(Palette.inkMuted)
                }
                .padding(.horizontal, 18)
                .padding(.vertical, 14)
                .contentShape(Rectangle())
            }
            .buttonStyle(.pressable)
            .background(card)
        }
    }

    private var googleSection: some View {
        VStack(alignment: .leading, spacing: Space.s2) {
            Text("Google")
                .textStyle(.label, weight: .semibold)
                .foregroundStyle(Palette.inkMuted)
                .padding(.horizontal, 4)
            VStack(alignment: .leading, spacing: Space.s3) {
                HStack(spacing: Space.s3) {
                    Image(systemName: "g.circle.fill")
                        .font(.system(size: 28, weight: .medium))
                        .foregroundStyle(Palette.brandStrong)
                    VStack(alignment: .leading, spacing: 3) {
                        Text(google.status?.email ?? "Google account")
                            .textStyle(.label, weight: .semibold)
                            .lineLimit(1)
                            .truncationMode(.middle)
                        Text(googleStatusLine)
                            .textStyle(.caption)
                            .foregroundStyle(Palette.inkMuted)
                    }
                    Spacer(minLength: 0)
                }

                if let status = google.status, status.isConnected {
                    let gmailEnabled = status.scopes.contains("https://www.googleapis.com/auth/gmail.readonly")
                        && status.scopes.contains("https://www.googleapis.com/auth/gmail.compose")
                    let calendarEnabled = status.scopes.contains("https://www.googleapis.com/auth/calendar.events")
                    HStack(spacing: Space.s2) {
                        scopePill("Gmail", enabled: gmailEnabled)
                        scopePill("Calendar", enabled: calendarEnabled)
                    }
                    let missingScopes = [gmailEnabled ? nil : "Gmail", calendarEnabled ? nil : "Calendar"]
                        .compactMap { $0 }
                    if !missingScopes.isEmpty {
                        Label("Reconnect to allow \(missingScopes.joined(separator: " and ")).", systemImage: "arrow.clockwise")
                            .textStyle(.caption)
                            .foregroundStyle(Palette.inkMuted)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }

                if let error = google.errorMessage {
                    Text(error)
                        .textStyle(.caption)
                        .foregroundStyle(Palette.danger)
                        .fixedSize(horizontal: false, vertical: true)
                }

                Text("If you ask TinyMe to work with email, relevant message text is sent to Anthropic to answer. TinyMe never sends or replies to email without your confirmation.")
                    .textStyle(.caption)
                    .foregroundStyle(Palette.inkMuted)
                    .fixedSize(horizontal: false, vertical: true)

                HStack(spacing: Space.s3) {
                    Button {
                        guard let api = session.api else { return }
                        Task { await google.connect(api: api) }
                    } label: {
                        HStack(spacing: Space.s2) {
                            if google.isWorking { ProgressView().tint(Palette.onBrand) }
                            Text(google.status?.isConnected == true || google.status?.needsReauth == true
                                 ? "Reconnect Google" : "Connect Google")
                                .textStyle(.label, weight: .semibold)
                        }
                        .foregroundStyle(Palette.onBrand)
                        .frame(maxWidth: .infinity)
                        .frame(height: 46)
                        .background(Palette.brand, in: Capsule())
                    }
                    .buttonStyle(.pressable)
                    .disabled(google.isWorking)

                    if google.status?.isConnected == true || google.status?.needsReauth == true {
                        Button { showsGoogleDisconnect = true } label: {
                            Image(systemName: "link.badge.minus")
                                .font(.system(size: 16, weight: .semibold))
                                .foregroundStyle(Palette.danger)
                                .frame(width: 46, height: 46)
                                .background(Palette.surfaceSunken, in: Circle())
                        }
                        .buttonStyle(.pressable)
                        .accessibilityLabel("Disconnect Google")
                    }
                }
            }
            .padding(Space.s4)
            .background(card)
        }
    }

    private var googleStatusLine: String {
        guard let status = google.status else { return "Checking connection…" }
        if status.needsReauth { return "Reconnect to restore access" }
        return status.isConnected ? "Connected to TinyMe" : "Not connected"
    }

    private func scopePill(_ title: String, enabled: Bool) -> some View {
        Label(title, systemImage: enabled ? "checkmark.circle.fill" : "minus.circle")
            .font(.custom(Fonts.family, size: 12).weight(.medium))
            .foregroundStyle(enabled ? Palette.brandStrong : Palette.inkMuted)
            .padding(.horizontal, 10)
            .padding(.vertical, 6)
            .background(enabled ? Palette.brandSoft : Palette.surfaceSunken, in: Capsule())
    }

    private var healthSummary: String {
        let health = environment.health
        guard health.isConnected else { return "Not connected" }
        if let line = environment.healthSync.importLine { return line }
        let groups = HealthGroup.allCases.filter(health.saved.groups.contains).map(\.title)
        return groups.isEmpty ? "Nothing switched on" : groups.joined(separator: ", ")
    }

    private var dataSection: some View {
        VStack(alignment: .leading, spacing: Space.s2) {
            Text("Your data")
                .textStyle(.label, weight: .semibold)
                .foregroundStyle(Palette.inkMuted)
                .padding(.horizontal, 4)
            VStack(alignment: .leading, spacing: Space.s2) {
                Button(action: exportData) {
                    HStack(spacing: Space.s2) {
                        if isExporting {
                            ProgressView()
                                .tint(Palette.brand)
                        } else {
                            Image(systemName: "square.and.arrow.down")
                        }
                        Text(isExporting ? "Preparing your export…" : "Export my data")
                            .textStyle(.label, weight: .semibold)
                        Spacer()
                    }
                    .frame(maxWidth: .infinity)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.pressable)
                .disabled(isExporting)
                .accessibilityHint("Downloads a ZIP of your database and memory notes, then opens the share sheet.")

                Text("Your ZIP includes your journal and memory notes. Its manifest reports if memory notes couldn't be included.")
                    .textStyle(.caption)
                    .foregroundStyle(Palette.inkMuted)
                if let exportNotice {
                    Text(exportNotice)
                        .textStyle(.caption, weight: .semibold)
                        .foregroundStyle(Palette.accent)
                        .accessibilityAddTraits(.updatesFrequently)
                }
                divider
                    .padding(.vertical, 4)
                Button {
                    showsDeleteAll = true
                } label: {
                    HStack(spacing: Space.s2) {
                        Image(systemName: "trash")
                        Text("Delete all data")
                            .textStyle(.label, weight: .semibold)
                        Spacer()
                    }
                    .foregroundStyle(Palette.danger)
                    .frame(maxWidth: .infinity)
                    .frame(minHeight: 28)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.pressable)
                .accessibilityHint("Asks for your password first.")
            }
            .padding(.horizontal, 18)
            .padding(.vertical, 14)
            .background(card)
        }
    }

    private func row(_ label: String, @ViewBuilder value: () -> some View) -> some View {
        HStack(spacing: Space.s3) {
            Text(label)
                .textStyle(.label, weight: .regular)
                .foregroundStyle(Palette.inkMuted)
            Spacer(minLength: Space.s2)
            value()
                .textStyle(.label, weight: .semibold)
        }
        .padding(.horizontal, 18)
        .padding(.vertical, 14)
        .accessibilityElement(children: .combine)
    }

    private var divider: some View {
        Rectangle()
            .fill(Palette.line)
            .frame(height: 1)
    }

    private var card: some View {
        RoundedRectangle(cornerRadius: Radius.lg, style: .continuous)
            .fill(Palette.surfaceRaised)
            .cardShadow()
    }

    private var signOutButton: some View {
        VStack(spacing: 10) {
            Button {
                isConfirming = true
            } label: {
                Label("Sign out", systemImage: "rectangle.portrait.and.arrow.right")
                    .textStyle(.body, weight: .semibold)
                    .foregroundStyle(Palette.danger)
                    .frame(maxWidth: .infinity)
                    .frame(height: 54)
                    .background {
                        Capsule()
                            .fill(Palette.surfaceRaised)
                            .cardShadow()
                    }
            }
            .buttonStyle(.pressable)
            Text(Self.versionLine)
                .textStyle(.caption)
                .foregroundStyle(Palette.inkMuted)
        }
    }

    private var confirmCard: some View {
        VStack(alignment: .leading, spacing: Space.s3) {
            VStack(alignment: .leading, spacing: 2) {
                Text("Sign out of this phone?")
                    .textStyle(.body, weight: .semibold)
                Text("Your data stays on your server. You'll need your email and password to sign back in.")
                    .textStyle(.label, weight: .regular)
                    .foregroundStyle(Palette.inkMuted)
            }
            HStack(spacing: Space.s2) {
                Button {
                    isConfirming = false
                } label: {
                    Text("Cancel")
                        .textStyle(.label, weight: .semibold)
                        .frame(maxWidth: .infinity)
                        .frame(height: 48)
                        .background(Palette.surfaceSunken, in: Capsule())
                }
                Button(action: signOut) {
                    HStack(spacing: Space.s2) {
                        if isSigningOut {
                            ProgressView()
                                .tint(Palette.danger)
                        }
                        Text("Sign out")
                            .textStyle(.label, weight: .semibold)
                    }
                    .foregroundStyle(Palette.danger)
                    .frame(maxWidth: .infinity)
                    .frame(height: 48)
                    .overlay(Capsule().strokeBorder(Palette.danger, lineWidth: 1.5))
                    .contentShape(Capsule())
                }
            }
            .buttonStyle(.pressable)
            .disabled(isSigningOut)
        }
        .padding(Space.s4)
        .background(card)
    }

    // MARK: - Actions

    private func checkServer() async {
        guard let api = session.api else { return }
        do {
            let _: HealthDTO = try await api.get(.health)
            status = .connected
        } catch {
            status = .unreachable
        }
    }

    private func signOut() {
        isSigningOut = true
        Task {
            await session.signOut()   // RootView swaps to Login, which takes this sheet with it
        }
    }

    private func exportData() {
        guard !isExporting, let api = session.api else { return }
        isExporting = true
        Task {
            defer { isExporting = false }
            do {
                let archive = try await api.downloadArchive(.dataExport)
                exportNotice = archive.memoryWarning
                        ? "Exported, but your memory notes couldn't be included."
                        : "Export ready. Check manifest.json for memory export status."
                exportFile = archive
            } catch APIError.server(status: 409, _, _) {
                exportError = "An export is already running. Try again in a minute."
            } catch is URLError {
                exportError = "Couldn't reach your server. Check your connection and try again."
            } catch APIError.unauthorized {
                exportError = "Your sign-in expired. Sign in again to export your data."
            } catch {
                exportError = "The export couldn't be prepared. Please try again."
            }
        }
    }

    private func removeExportFile() {
        guard let exportFile else { return }
        removeExportFile(exportFile)
    }

    private func removeExportFile(_ file: DownloadedArchive) {
        try? FileManager.default.removeItem(at: file.url.deletingLastPathComponent())
        if exportFile?.id == file.id { exportFile = nil }
    }

    // MARK: - Text

    /// Host and port only: "tinyme.your-tailnet.ts.net", "192.168.3.6:8080".
    private static func displayAddress(_ server: URL) -> String {
        guard let host = server.host() else { return server.absoluteString }
        return server.port.map { "\(host):\($0)" } ?? host
    }

    private static var deviceName: String {
        #if canImport(UIKit)
        UIDevice.current.name
        #else
        Host.current().localizedName ?? "Mac"
        #endif
    }

    private static var versionLine: String {
        let info = Bundle.main.infoDictionary
        let version = info?["CFBundleShortVersionString"] as? String ?? "–"
        let build = info?["CFBundleVersion"] as? String ?? "–"
        return "TinyMe \(version) (\(build))"
    }
}

private struct GoogleDisconnectPrompt: View {
    let isWorking: Bool
    let onCancel: () -> Void
    let onConfirm: (String) -> Void
    @State private var password = ""

    var body: some View {
        VStack(alignment: .leading, spacing: Space.s4) {
            Capsule()
                .fill(Palette.inkMuted.opacity(0.3))
                .frame(width: 40, height: 5)
                .frame(maxWidth: .infinity)
                .padding(.top, 10)
            Text("Disconnect Google?")
                .textStyle(.title, weight: .semibold)
            Text("TinyMe will revoke its Google access and remove the saved connection.")
                .textStyle(.body)
                .foregroundStyle(Palette.inkMuted)
            SecureField("TinyMe password", text: $password)
                .textContentType(.password)
                .padding(.horizontal, 16)
                .frame(height: 52)
                .background(Palette.surfaceSunken, in: RoundedRectangle(cornerRadius: Radius.md))
            HStack(spacing: Space.s3) {
                Button("Cancel", action: onCancel)
                    .frame(maxWidth: .infinity)
                    .frame(height: 50)
                    .background(Palette.surfaceSunken, in: Capsule())
                Button {
                    onConfirm(password)
                } label: {
                    HStack(spacing: Space.s2) {
                        if isWorking { ProgressView().tint(Palette.onBrand) }
                        Text("Disconnect")
                    }
                    .foregroundStyle(Palette.onBrand)
                    .frame(maxWidth: .infinity)
                    .frame(height: 50)
                    .background(Palette.danger, in: Capsule())
                }
                .disabled(password.isEmpty || isWorking)
            }
            .buttonStyle(.pressable)
            Spacer(minLength: 0)
        }
        .padding(.horizontal, Space.s4)
        .padding(.bottom, Space.s4)
        .background(Palette.surface.ignoresSafeArea())
    }
}
