import SwiftUI
#if canImport(UIKit)
import UIKit
#endif

/// Opens from the profile button on Home: who is signed in, which server, and sign out.
/// A bottom panel drawn by the app, so it sits flush with the screen edges on every iOS version.
struct SettingsSheet: View {
    @Binding var isPresented: Bool
    var session: SessionModel
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
                SettingsPanel(session: session, onClose: close, onDataDeleted: onDataDeleted)
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
    var onDataDeleted: () -> Void

    @AppStorage(Appearance.storageKey) private var appearance: Appearance = .system
    @AppStorage("voice.sendOnRelease") private var sendOnRelease = false
    @State private var isConfirming = false
    @State private var isSigningOut = false
    @State private var isExporting = false
    @State private var exportError: String?
    @State private var exportNotice: String?
    @State private var exportFile: DownloadedArchive?
    @State private var showsDeleteAll = false
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
        .task { await checkServer() }
        .sheet(item: $exportFile, onDismiss: removeExportFile) { file in
            ActivityShareSheet(items: [file.url])
                .presentationDetents([.medium, .large])
                .onDisappear { removeExportFile(file) }
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
                serverSection(server: account.server)
                appearanceSection
                voiceSection
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
