import SwiftUI

struct LoginView: View {
    var session: SessionModel

    @State private var server = ""
    @State private var email = ""
    @State private var password = ""
    @State private var revealPassword = false
    @FocusState private var focus: Field?

    private enum Field {
        case server, email, password
    }

    private var isConnecting: Bool { session.state == .signingIn }

    private var isReady: Bool {
        !server.trimmingCharacters(in: .whitespaces).isEmpty
            && !email.trimmingCharacters(in: .whitespaces).isEmpty
            && !password.isEmpty
    }

    var body: some View {
        GeometryReader { geometry in
            ScrollView {
                VStack(alignment: .leading, spacing: 28) {
                    header
                    VStack(spacing: Space.s4) {
                        fields
                        if let error = session.loginError {
                            errorBanner(error)
                                .transition(.opacity.combined(with: .move(edge: .top)))
                        }
                        connectButton
                    }
                    .animation(.snappy(duration: 0.25), value: session.loginError)
                    Spacer(minLength: Space.s6)
                    footnote
                }
                .padding(.horizontal, Space.s4)
                .padding(.top, 32)
                .padding(.bottom, Space.s4)
                .frame(minHeight: geometry.size.height)
            }
            .scrollDismissesKeyboard(.interactively)
            .scrollBounceBehavior(.basedOnSize)
        }
        .foregroundStyle(Palette.ink)
        .background(Palette.surface.ignoresSafeArea())
        .onAppear(perform: prefill)
        .onChange(of: server) { session.clearLoginError() }
        .onChange(of: email) { session.clearLoginError() }
        .onChange(of: password) { session.clearLoginError() }
    }

    // MARK: - Pieces

    private var header: some View {
        VStack(alignment: .leading, spacing: 14) {
            TinyMeFace(size: 64)
                .padding(10)
                .background(Palette.brand.opacity(0.16), in: Circle())
                .padding(.leading, -10)
                .accessibilityHidden(true)
            Text("Welcome to TinyMe")
                .textStyle(.display)
                .accessibilityAddTraits(.isHeader)
            Text("Connect to your own TinyMe server. Everything you tell me stays there.")
                .textStyle(.body)
                .foregroundStyle(Palette.inkMuted)
        }
    }

    private var fields: some View {
        VStack(spacing: 0) {
            field("Server") {
                TextField("Server", text: $server, prompt: prompt("https://tinyme.your-tailnet.ts.net"))
                    .textContentType(.URL)
                    .keyboardType(.URL)
                    .submitLabel(.next)
                    .focused($focus, equals: .server)
                    .onSubmit { focus = .email }
            }
            divider
            field("Email") {
                TextField("Email", text: $email, prompt: prompt("you@example.com"))
                    .textContentType(.username)
                    .keyboardType(.emailAddress)
                    .submitLabel(.next)
                    .focused($focus, equals: .email)
                    .onSubmit { focus = .password }
            }
            divider
            HStack(alignment: .bottom, spacing: Space.s2) {
                field("Password") {
                    Group {
                        if revealPassword {
                            TextField("Password", text: $password, prompt: prompt("Your password"))
                        } else {
                            SecureField("Password", text: $password, prompt: prompt("Your password"))
                        }
                    }
                    .textContentType(.password)
                    .submitLabel(.go)
                    .focused($focus, equals: .password)
                    .onSubmit(connect)
                }
                Button {
                    revealPassword.toggle()
                } label: {
                    Image(systemName: revealPassword ? "eye.slash" : "eye")
                        .font(.system(size: 17))
                        .frame(width: 44, height: 44)
                        .background(Palette.surfaceSunken, in: Circle())
                }
                .buttonStyle(.pressable)
                .accessibilityLabel(revealPassword ? "Hide password" : "Show password")
                .padding(.trailing, 12)
                .padding(.bottom, 10)
            }
        }
        .textInputAutocapitalization(.never)
        .autocorrectionDisabled()
        .disabled(isConnecting)
        .background {
            RoundedRectangle(cornerRadius: Radius.lg, style: .continuous)
                .fill(Palette.surfaceRaised)
                .cardShadow()
        }
    }

    private func field(_ label: String, @ViewBuilder input: () -> some View) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(label)
                .textStyle(.caption, weight: .semibold)
                .foregroundStyle(Palette.inkMuted)
                .accessibilityHidden(true)   // the input carries the same label
            input()
                .textStyle(.body, weight: .medium)
                .frame(minHeight: 28)
        }
        .padding(.horizontal, 20)
        .padding(.top, 14)
        .padding(.bottom, 12)
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private var divider: some View {
        Rectangle()
            .fill(Palette.line)
            .frame(height: 1)
    }

    private func prompt(_ text: String) -> Text {
        Text(text).foregroundStyle(Palette.inkMuted.opacity(0.7))
    }

    private func errorBanner(_ message: String) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: 10) {
            Image(systemName: "exclamationmark.circle")
                .font(.system(size: 16, weight: .semibold))
                .accessibilityHidden(true)
            Text(message)
                .textStyle(.label)
            Spacer(minLength: 0)
        }
        .foregroundStyle(Palette.onAccentSoft)
        .padding(.horizontal, Space.s4)
        .padding(.vertical, Space.s3)
        .background(Palette.accentSoft, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
    }

    private var connectButton: some View {
        let isActive = isReady || isConnecting
        return Button(action: connect) {
            HStack(spacing: 10) {
                if isConnecting {
                    ProgressView()
                        .tint(Palette.onBrand)
                }
                Text(isConnecting ? "Connecting…" : "Connect")
                    .textStyle(.heading)
            }
            .foregroundStyle(isActive ? Palette.onBrand : Palette.inkMuted)
            .frame(maxWidth: .infinity)
            .frame(height: 56)
            .background(isActive ? Palette.brand : Palette.surfaceSunken, in: Capsule())
            // shadow-press: the hard edge under TinyMe's tactile control
            .shadow(color: isActive ? Palette.pressEdge : .clear, radius: 0, y: 3)
        }
        .buttonStyle(.pressable)
        .disabled(!isReady || isConnecting)
        .animation(.snappy(duration: 0.2), value: isActive)
    }

    private var footnote: some View {
        HStack(alignment: .firstTextBaseline, spacing: Space.s2) {
            Image(systemName: "lock")
                .font(.system(size: 12, weight: .semibold))
                .accessibilityHidden(true)
            Text("No sign-up. Your email and password are set on your server, so a new phone just signs in again.")
                .textStyle(.caption)
                .multilineTextAlignment(.center)
        }
        .foregroundStyle(Palette.inkMuted)
        .padding(.horizontal, Space.s3)
        .frame(maxWidth: .infinity)
    }

    // MARK: - Actions

    private func prefill() {
        if server.isEmpty {
            server = (session.lastServer ?? AppConfig.defaultServerURL)?.absoluteString ?? ""
        }
        if email.isEmpty {
            email = session.lastEmail ?? ""
        }
    }

    private func connect() {
        guard isReady, !isConnecting else { return }
        focus = nil
        Task {
            await session.signIn(server: server, email: email, password: password)
        }
    }
}

#Preview("Empty") {
    LoginView(session: SessionModel(authStore: AuthStore(service: "app.tinyme.preview")))
}
