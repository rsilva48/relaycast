import SwiftUI

/// Asistente de configuración inicial: the relay (its setup code or its
/// address), the account (new, from iCloud Keychain, from another device's
/// profile code or a token), the profile's name and picture, and new-episode
/// alerts. It reuses the sheets of Settings and of scanned codes, shows by
/// itself only on a device without a relay, and every step can be left for
/// later. A device already set up only sees it when opened from Settings.
struct SetupAssistant: View {
    enum Step: Hashable { case relay, account, profile, notifications, done }

    @Environment(ProfileStore.self) private var profile
    let onFinish: () -> Void
    @State private var path: [Step] = []
    /// Fetching the account's profile from the relay after identifying.
    @State private var loadingProfile = false

    var body: some View {
        NavigationStack(path: $path) {
            SetupPage(
                systemImage: "play.tv.fill",
                title: Self.welcomeTitle,
                text: "En unos pasos: conecta tu relay, configura tu cuenta y tu perfil. Puedes dejar cada paso para después."
            ) {
                Button("Empezar") { path.append(.relay) }
                    .buttonStyle(.glassProminent)
                Button("Configurar más tarde", action: onFinish)
                    .buttonStyle(.glass)
            }
            .navigationTitle("Asistente de configuración inicial")
            .navigationBarTitleDisplayMode(.inline)
            .navigationDestination(for: Step.self) { step in
                switch step {
                case .relay:
                    SetupRelayStep(onDone: afterRelay, onLater: onFinish)
                case .account:
                    SetupAccountStep(onDone: continueAfterIdentity, onSkip: { path.append(Self.afterProfile) })
                case .profile:
                    AccountEditor(onSaved: { path.append(Self.afterProfile) })
                        .toolbar {
                            ToolbarItem(placement: .bottomBar) {
                                Button("Omitir") { path.append(Self.afterProfile) }
                            }
                        }
                case .notifications:
                    SetupNotificationsStep { path.append(.done) }
                case .done:
                    SetupPage(
                        systemImage: "checkmark.circle.fill",
                        title: "Todo listo",
                        text: profile.isRelayConfigured
                            ? Self.readyText
                            : "Puedes conectar tu Relay Server cuando quieras desde Fuentes o Ajustes."
                    ) {
                        Button("Empezar a ver", action: onFinish)
                            .buttonStyle(.glassProminent)
                    }
                    .navigationBarBackButtonHidden()
                }
            }
        }
        .overlay {
            if loadingProfile { LoadingOverlay(message: String(localized: "Cargando tu perfil…")) }
        }
    }

    private static let welcomeTitle: LocalizedStringKey = "Te damos la bienvenida a Relaycast"
    private static let readyText: LocalizedStringKey = "Tu servidor está conectado. Si ofrece su biblioteca, la verás en Fuentes. Puedes cambiar todo en Ajustes."
    private static let afterProfile = Step.notifications

    /// After the relay: the account if it has none yet, else the profile.
    private func afterRelay() {
        if profile.hasToken { continueAfterIdentity() } else { path.append(.account) }
    }

    /// With an account: its profile from the relay first. One that already
    /// has a name (set on another device) keeps it and its picture, so the
    /// profile step is skipped and nothing can blank it; otherwise the
    /// editor opens with whatever the account has.
    private func continueAfterIdentity() {
        loadingProfile = true
        Task {
            await profile.refreshAccount()
            loadingProfile = false
            let named = !(profile.account?.name ?? "").trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            path.append(named ? Self.afterProfile : .profile)
        }
    }
}

/// A full page of the assistant: a symbol, a title, a line and its buttons
/// at the bottom, centered and width-limited (iPad, the unfolded iPhone Duo).
private struct SetupPage<Buttons: View>: View {
    let systemImage: String
    let title: LocalizedStringKey
    let text: LocalizedStringKey
    @ViewBuilder let buttons: () -> Buttons

    var body: some View {
        ScrollView {
            VStack(spacing: 16) {
                Image(systemName: systemImage)
                    .font(.system(size: 64, weight: .semibold))
                    .foregroundStyle(.tint)
                    .padding(.top, 48)
                    .accessibilityHidden(true)
                Text(title)
                    .font(.largeTitle.bold())
                    .multilineTextAlignment(.center)
                Text(text)
                    .font(.body)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
            }
            .padding(.horizontal, 24)
            .frame(maxWidth: 520)
            .frame(maxWidth: .infinity)
        }
        .safeAreaInset(edge: .bottom) {
            VStack(spacing: 10) {
                buttons()
                    .controlSize(.large)
                    .frame(maxWidth: .infinity)
            }
            .padding(.horizontal, 24)
            .padding(.bottom, 12)
            .frame(maxWidth: 520)
        }
    }
}

/// The relay: its setup code (address, and the administrator token to
/// create the account) or the address typed and tested, as in Settings.
private struct SetupRelayStep: View {
    let onDone: () -> Void
    let onLater: () -> Void
    @Environment(ProfileStore.self) private var profile
    @State private var scheme: RelayScheme = .https
    @State private var host = ""
    @State private var testing = false
    @State private var errorMessage: String?
    @State private var showScanner = false
    @State private var pendingScan: ScannedCode?
    @State private var relaySetup: RelaySetupRequest?
    @State private var identity: IdentityTransferRequest?
    /// The relay and account before a code's sheet, to tell whether it set them.
    @State private var before: (relay: String, token: Bool)?

    private var candidateURL: String? { RelayEndpoint.make(scheme: scheme, host: host) }

    var body: some View {
        Form {
            Section {
                Button("Escanear código de configuración", systemImage: "qrcode.viewfinder") { showScanner = true }
            } footer: {
                Text("El administrador del relay te lo puede dar: trae la dirección y, después, te identificas con tu cuenta o creas una. Si el código está en este dispositivo, elígelo desde Fotos en el escáner.")
            }
            Section {
                HStack(spacing: 4) {
                    Picker("Protocolo", selection: $scheme) {
                        ForEach(RelayScheme.allCases) { Text("\($0.rawValue)://").tag($0) }
                    }
                    .labelsHidden()
                    .pickerStyle(.menu)
                    .fixedSize()
                    TextField("relay.midominio.com", text: $host)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .keyboardType(.URL)
                        .textContentType(.URL)
                        .submitLabel(.go)
                        .onSubmit(test)
                }
                Button(action: test) {
                    if testing {
                        HStack(spacing: 10) {
                            ProgressView()
                            Text("Probando conexión…")
                        }
                    } else {
                        Label("Probar y continuar", systemImage: "network")
                    }
                }
                .disabled(testing || candidateURL == nil)
            } header: {
                Text("O escribe la dirección")
            } footer: {
                Text("Por ejemplo relay.midominio.com o 192.168.1.20:8787. HTTP sólo funciona en tu red local.")
            }
            if let errorMessage {
                Section { ErrorNotice(message: errorMessage) }
            }
            Section {
                Button("Configurar más tarde", action: onLater)
            }
        }
        .navigationTitle("Conecta tu relay")
        .navigationBarTitleDisplayMode(.inline)
        .scrollDismissesKeyboard(.interactively)
        .onAppear {
            guard host.isEmpty else { return }
            let source = profile.isRelayConfigured ? profile.relayURL : profile.savedRelayAddress.flatMap(RelayEndpoint.canonicalURL)
            if let source {
                let fields = RelayEndpoint.fields(from: source)
                scheme = fields.scheme
                host = fields.host
            }
        }
        .onChange(of: host) { _, value in
            // Only the host is typed here; a pasted scheme moves to the picker.
            let stripped = RelayEndpoint.strippingScheme(value)
            if let pastedScheme = stripped.scheme {
                scheme = pastedScheme
                host = stripped.host
            }
        }
        .sheet(isPresented: $showScanner, onDismiss: openScanned) {
            CodeScannerSheet { pendingScan = $0 }
        }
        .sheet(item: $relaySetup, onDismiss: advanceIfConfigured) { request in
            RelaySetupSheet(request: request)
        }
        .sheet(item: $identity, onDismiss: advanceIfConfigured) { request in
            IdentityImportSheet(request: request)
        }
    }

    private func openScanned() {
        guard let code = pendingScan else { return }
        pendingScan = nil
        errorMessage = nil
        before = (profile.relayURL, profile.hasToken)
        switch code {
        case .relaySetup(let request): relaySetup = request
        case .identity(let request): identity = request
        case .pairing: errorMessage = String(localized: "Ese código es para vincular una TV. Aquí escanea el código de configuración del relay o un código de perfil.")
        }
    }

    private func advanceIfConfigured() {
        guard let before else { return }
        self.before = nil
        if profile.isRelayConfigured, profile.relayURL != before.relay || profile.hasToken != before.token {
            onDone()
        }
    }

    private func test() {
        guard let candidate = candidateURL, !testing else { return }
        Task {
            testing = true
            errorMessage = nil
            defer { testing = false }
            do {
                let health: HealthResponse = try await RelayClient.get(base: candidate, path: "/health", timeoutInterval: 10)
                guard health.ok else {
                    errorMessage = String(localized: "El relay respondió de forma inesperada.")
                    return
                }
                // As in Settings: an identity saved before keeps working.
                _ = try? SecureTokenStore.migrateLegacyToken(to: candidate)
                _ = try? SecureTokenStore.makeCurrentTokenSynchronizable(for: candidate)
                profile.useRelay(candidate)
                Task { await profile.sync(force: true) }
                onDone()
            } catch is CancellationError {
                return
            } catch {
                errorMessage = error.localizedDescription
            }
        }
    }
}

/// The account: first an existing one (another device's profile code, by
/// camera or from Photos, iCloud Keychain, or a token typed in), then a new
/// one (administrator token).
private struct SetupAccountStep: View {
    let onDone: () -> Void
    let onSkip: () -> Void
    @Environment(ProfileStore.self) private var profile
    @State private var showCreate = false
    @State private var showImport = false
    @State private var showScanner = false
    @State private var pendingScan: ScannedCode?
    @State private var identity: IdentityTransferRequest?
    @State private var relaySetup: RelaySetupRequest?
    @State private var errorMessage: String?

    var body: some View {
        Form {
            Section {
                Button("Escanear el código de mi perfil", systemImage: "qrcode.viewfinder") { showScanner = true }
                Button("Restaurar desde Llavero de iCloud…", systemImage: "icloud", action: restore)
                Button("Importar token manualmente…", systemImage: "key") { showImport = true }
            } header: {
                Text("Ya tengo una cuenta")
            } footer: {
                Text("Recomendado: en un dispositivo con tu cuenta abre Ajustes › Mostrar código del perfil (o en la TV, mantén OK sobre tu perfil › Llevar al teléfono) y escanéalo, o elige una captura del código desde Fotos.")
            }
            Section {
                Button("Crear una cuenta nueva…", systemImage: "person.badge.plus") { showCreate = true }
            } header: {
                Text("No tengo cuenta")
            } footer: {
                Text("Guarda tu Continuar viendo y tus preferencias en el relay. El token es la llave de tu cuenta: respáldalo y compártelo sólo con tus dispositivos.")
            }
            if let errorMessage {
                Section { ErrorNotice(message: errorMessage) }
            }
            Section {
                Button("Omitir", action: onSkip)
            }
        }
        .navigationTitle("Tu cuenta")
        .navigationBarTitleDisplayMode(.inline)
        .sheet(isPresented: $showCreate, onDismiss: advanceIfReady) {
            TokenProvisioningSheet(relayBaseURL: profile.relayURL) { token in try install(token) }
        }
        .sheet(isPresented: $showImport, onDismiss: advanceIfReady) {
            TokenImportSheet(replacesIdentity: false) { token in try install(token) }
        }
        .sheet(isPresented: $showScanner, onDismiss: openScanned) {
            CodeScannerSheet { pendingScan = $0 }
        }
        .sheet(item: $identity, onDismiss: advanceIfReady) { request in
            IdentityImportSheet(request: request)
        }
        .sheet(item: $relaySetup, onDismiss: advanceIfReady) { request in
            RelaySetupSheet(request: request)
        }
    }

    private func openScanned() {
        guard let code = pendingScan else { return }
        pendingScan = nil
        errorMessage = nil
        switch code {
        case .identity(let request): identity = request
        case .relaySetup(let request): relaySetup = request
        case .pairing: errorMessage = String(localized: "Ese código es para vincular una TV. Aquí escanea el código de configuración del relay o un código de perfil.")
        }
    }

    private func advanceIfReady() {
        if profile.hasToken { onDone() }
    }

    private func install(_ token: String) throws {
        try SecureTokenStore.save(token, for: profile.relayURL)
        profile.reloadIdentity()
        Task { await profile.sync(force: true) }
    }

    private func restore() {
        do {
            try install(SecureTokenStore.restoreFromICloud(for: profile.relayURL))
            onDone()
        } catch {
            errorMessage = error.localizedDescription
        }
    }
}

/// New-episode alerts; the app does not ask again afterwards.
private struct SetupNotificationsStep: View {
    let onDone: () -> Void
    @Environment(ProfileStore.self) private var profile
    @Environment(ReleaseNotifier.self) private var notifier
    @AppStorage(ReleaseNotifier.promptedKey) private var promptedNotifications = false
    @State private var working = false

    var body: some View {
        SetupPage(
            systemImage: "bell.badge.fill",
            title: "¿Avisarte de nuevos episodios?",
            text: "La app puede avisarte cuando salga un episodio nuevo de las series que sigues en Continuar viendo. Puedes cambiarlo cuando quieras en Ajustes."
        ) {
            Button("Activar avisos") {
                working = true
                Task {
                    await notifier.enableFromPrompt(profile: profile)
                    promptedNotifications = true
                    working = false
                    onDone()
                }
            }
            .buttonStyle(.glassProminent)
            .disabled(working)
            Button("Ahora no") {
                promptedNotifications = true
                onDone()
            }
            .buttonStyle(.glass)
        }
        .navigationTitle("Avisos")
        .navigationBarTitleDisplayMode(.inline)
    }
}
