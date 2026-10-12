import SwiftUI

/// "Delete all data": says what goes and what stays, offers an export, then asks for the password.
struct DeleteAllSheet: View {
    let api: APIClient
    /// Closes this sheet and starts the export from Settings.
    var onExportFirst: () -> Void
    var onDeleted: () -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var password = ""
    @State private var isDeleting = false
    @State private var error: String?
    @FocusState private var passwordFocused: Bool

    private var canDelete: Bool { !password.isEmpty && !isDeleting }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: Space.s4) {
                HStack {
                    Text("Delete all data")
                        .textStyle(.title, weight: .semibold)
                        .accessibilityAddTraits(.isHeader)
                    Spacer()
                    Button {
                        dismiss()
                    } label: {
                        Image(systemName: "xmark")
                            .font(.system(size: 14, weight: .bold))
                            .frame(width: 40, height: 40)
                            .background(Palette.surfaceSunken, in: Circle())
                            .frame(width: 44, height: 44)
                    }
                    .buttonStyle(.pressable)
                    .disabled(isDeleting)
                    .accessibilityLabel("Close")
                }

                VStack(alignment: .leading, spacing: 0) {
                    fact("What's deleted", "Everything you've logged, your people, cards, chats, and everything TinyMe remembers about you.")
                    Rectangle()
                        .fill(Palette.line)
                        .frame(height: 1)
                    fact("What's kept", "Your account and this phone's sign-in.")
                }
                .background(card)

                Text("This can't be undone.")
                    .textStyle(.label, weight: .semibold)
                    .foregroundStyle(Palette.danger)
                    .padding(.horizontal, 4)

                Button(action: onExportFirst) {
                    Label("Export first", systemImage: "square.and.arrow.down")
                        .textStyle(.label, weight: .semibold)
                        .frame(maxWidth: .infinity)
                        .frame(height: 48)
                        .background(Palette.surfaceSunken, in: Capsule())
                }
                .buttonStyle(.pressable)
                .disabled(isDeleting)

                VStack(alignment: .leading, spacing: 4) {
                    Text("Your password")
                        .textStyle(.caption, weight: .semibold)
                        .foregroundStyle(Palette.inkMuted)
                        .accessibilityHidden(true)   // the field carries the same label
                    SecureField("Your password", text: $password, prompt: Text("To confirm it's you").foregroundStyle(Palette.inkMuted.opacity(0.7)))
                        .textStyle(.body, weight: .medium)
                        .textContentType(.password)
                        .submitLabel(.done)
                        .focused($passwordFocused)
                        .frame(minHeight: 28)
                }
                .padding(.horizontal, 20)
                .padding(.top, 14)
                .padding(.bottom, 12)
                .background(card)
                .disabled(isDeleting)

                if let error {
                    Label(error, systemImage: "exclamationmark.circle")
                        .textStyle(.label)
                        .foregroundStyle(Palette.onAccentSoft)
                        .padding(.horizontal, Space.s4)
                        .padding(.vertical, Space.s3)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .background(Palette.accentSoft, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
                }

                Button(action: deleteEverything) {
                    HStack(spacing: 10) {
                        if isDeleting {
                            ProgressView()
                                .tint(Palette.onInk)
                        }
                        Text(isDeleting ? "Deleting…" : "Delete everything")
                            .textStyle(.heading)
                    }
                    .foregroundStyle(canDelete || isDeleting ? Color.white : Palette.inkMuted)
                    .frame(maxWidth: .infinity)
                    .frame(height: 56)
                    .background(canDelete || isDeleting ? Palette.danger : Palette.surfaceSunken, in: Capsule())
                }
                .buttonStyle(.pressable)
                .disabled(!canDelete)
            }
            .padding(Space.s4)
            .padding(.top, Space.s2)
        }
        .scrollDismissesKeyboard(.interactively)
        .scrollBounceBehavior(.basedOnSize)
        .foregroundStyle(Palette.ink)
        .background(Palette.surface.ignoresSafeArea())
        .presentationDetents([.large])
        .interactiveDismissDisabled(isDeleting)
        .animation(.snappy(duration: 0.25), value: error)
        .onChange(of: password) { error = nil }
    }

    private func fact(_ title: String, _ text: String) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(title)
                .textStyle(.label, weight: .semibold)
            Text(text)
                .textStyle(.label, weight: .regular)
                .foregroundStyle(Palette.inkMuted)
        }
        .padding(.horizontal, 18)
        .padding(.vertical, 14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityElement(children: .combine)
    }

    private var card: some View {
        RoundedRectangle(cornerRadius: Radius.lg, style: .continuous)
            .fill(Palette.surfaceRaised)
            .cardShadow()
    }

    private func deleteEverything() {
        guard canDelete else { return }
        passwordFocused = false
        isDeleting = true
        error = nil
        Task {
            defer { isDeleting = false }
            do {
                let _: DeleteAllCounts = try await api.send(.deleteAll, body: DeleteAllRequest(password: password))
                onDeleted()
            } catch {
                self.error = Self.message(for: error)
            }
        }
    }

    static func message(for error: Error) -> String {
        switch error {
        case APIError.unauthorized:
            "That password isn't right."
        case APIError.server(status: 429, _, _), APIError.http(status: 429):
            "Too many tries. Wait a minute and try again."
        case APIError.server(status: 409, _, _), APIError.http(status: 409):
            "TinyMe is still replying to a message. Try again in a moment."
        case APIError.server(status: 502, _, _), APIError.http(status: 502):
            "Couldn't clear what TinyMe remembers. Nothing on your server was deleted, so it's safe to try again."
        case is URLError:
            "Can't reach your server. Nothing was deleted."
        default:
            "Something went wrong. Check your server before trying again."
        }
    }
}
