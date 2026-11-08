import PhotosUI
import SwiftUI

struct SettingsScreen: View {
    /// An area of the settings, each on its own screen.
    private enum Area: Hashable {
        case account, relay, identity, catalog, playback, notifications, storage, about
    }

    private enum TestResult {
        case success(String)
        case warning(String)
        case failure(String)
    }

    @Environment(ProfileStore.self) private var profile
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    @Environment(\.verticalSizeClass) private var verticalSizeClass
    /// The area beside the list, with two columns.
    @State private var selectedArea: Area?
    @Environment(DownloadStore.self) private var downloads
    @AppStorage(DownloadStore.autoDeleteKey) private var autoDeleteDownloads = true
    @AppStorage(DeviceSettings.translateSynopsisKey) private var translateSynopsis = false
    @AppStorage(ReleaseNotifier.enabledKey) private var releaseNotifications = false
    @Environment(ReleaseNotifier.self) private var notifier
    @State private var notificationsDenied = false
    @State private var showDeleteDownloads = false
    @State private var scheme: RelayScheme = .https
    @State private var host = ""
    @State private var testing = false
    @State private var testResult: TestResult?
    @State private var preferences = UserPreferences()
    @State private var showProvisioning = false
    @State private var showImport = false
    @State private var showScanner = false
    /// A code read by the scanner, opened once the scanner has closed.
    @State private var scannedCode: ScannedCode?
    @State private var pendingScan: ScannedCode?
    @State private var showBackup = false
    @State private var showProfileCode = false
    @State private var showAccount = false
    @State private var showSetup = false
    @State private var showChangeWarning = false
    @State private var tokenError: String?
    @FocusState private var hostFocused: Bool

    private var candidateURL: String? { RelayEndpoint.make(scheme: scheme, host: host) }
    private var candidateIsActive: Bool { profile.isRelayConfigured && candidateURL == profile.relayURL }

    /// Where both size classes are regular (iPad, the unfolded iPhone Duo),
    /// the list with the chosen area beside it, as in Recientes; otherwise one
    /// column. An iPhone in landscape keeps one column.
    private var twoColumns: Bool { horizontalSizeClass == .regular && verticalSizeClass == .regular }

    /// As in WhatsApp: the profile on top, then one row per area, each with
    /// its own screen; explanations sit behind (i) buttons.
    var body: some View {
        Group {
            if twoColumns {
                GeometryReader { proxy in
                    let columns = AdaptiveColumns.split(in: proxy)
                    HStack(spacing: 0) {
                        NavigationStack { root }
                            // In landscape the floating tab bar stands at the
                            // trailing edge and its inset reached this column too,
                            // which never touches that edge: the list stopped short.
                            .ignoresSafeArea(.container, edges: .trailing)
                            .frame(width: columns.list)
                        if columns.gap > 0 {
                            Color(uiColor: .systemGroupedBackground)
                                .frame(width: columns.gap)
                                .ignoresSafeArea()
                        } else {
                            Divider()
                                .ignoresSafeArea()
                        }
                        NavigationStack {
                            if let selectedArea {
                                detail(selectedArea)
                                    .id(selectedArea)
                            } else {
                                // Nothing chosen yet, as in Recientes.
                                ContentUnavailableView("Elige un ajuste", systemImage: "gearshape", description: Text("Se abre aquí, junto a la lista."))
                            }
                        }
                        .frame(maxWidth: .infinity)
                    }
                }
            } else {
                NavigationStack {
                    root
                        .navigationDestination(for: Area.self) { detail($0) }
                        .navigationDestination(isPresented: $showAccount) { AccountEditor() }
                }
            }
        }
        .onAppear(perform: loadFields)
        .onChange(of: host) { _, value in
            // Only the host is typed here; a pasted scheme moves to the picker.
            let stripped = RelayEndpoint.strippingScheme(value)
            if let pastedScheme = stripped.scheme {
                scheme = pastedScheme
                host = stripped.host
            }
        }
        .onChange(of: profile.preferences) { _, value in preferences = value }
        // The connection test said an identity was missing; once one exists
        // (created, restored, imported or from a code) that is no longer true.
        .onChange(of: profile.hasToken) { _, hasToken in
            if hasToken, case .warning? = testResult {
                testResult = .success(String(localized: "Identidad configurada. Buscar, Recientes y Continuar ya están disponibles."))
            }
        }
        .onChange(of: preferences) { _, value in profile.setPreferences(value) }
        .sheet(isPresented: $showChangeWarning) {
            IdentityChangeGuardSheet { action in presentAfterChangeWarning(action) }
        }
        .sheet(isPresented: $showProvisioning) {
            TokenProvisioningSheet(relayBaseURL: profile.relayURL) { token in try install(token: token) }
        }
        .sheet(isPresented: $showScanner, onDismiss: {
            scannedCode = pendingScan
            pendingScan = nil
        }) {
            CodeScannerSheet { pendingScan = $0 }
        }
        .sheet(item: $scannedCode) { code in
            switch code {
            case .identity(let request): IdentityImportSheet(request: request)
            case .pairing(let request): TVPairingSheet(request: request)
            case .relaySetup(let request): RelaySetupSheet(request: request)
            }
        }
        .sheet(isPresented: $showImport) {
            TokenImportSheet { token in try install(token: token) }
        }
        .sheet(isPresented: $showBackup) {
            TokenBackupSheet(token: SecureTokenStore.currentToken(for: profile.relayURL))
        }
        .fullScreenCover(isPresented: $showSetup) {
            SetupAssistant { showSetup = false }
        }
        .sheet(isPresented: $showProfileCode) {
            ProfileCodeSheet(relay: profile.relayURL, token: SecureTokenStore.currentToken(for: profile.relayURL), profileName: profile.account?.displayName)
        }
    }

    /// The list of areas.
    private var root: some View {
        Form {
            accountHeader
            Section {
                row(.relay) {
                    SettingsRow("Relay", systemImage: "server.rack", detail: relayDetail)
                }
                row(.identity) {
                    SettingsRow("Cuenta", systemImage: "lock.shield", detail: profile.hasToken ? String(localized: "Configurada") : String(localized: "Pendiente"))
                }
            }
            Section {
                // Relaycast (App Store) has no catalog, nor its alerts.
                row(.catalog) {
                    SettingsRow("Catálogo", systemImage: "square.grid.2x2")
                }
                row(.playback) {
                    SettingsRow("Reproducción", systemImage: "play.rectangle")
                }
                row(.notifications) {
                    SettingsRow("Notificaciones", systemImage: "bell.badge", detail: releaseNotifications ? String(localized: "Activadas") : String(localized: "Desactivadas"))
                }
                row(.storage) {
                    SettingsRow("Almacenamiento y descargas", systemImage: "internaldrive", detail: ByteCountFormatter.string(fromByteCount: downloads.totalBytes, countStyle: .file))
                }
            }
            Section {
                row(.about) {
                    SettingsRow("Acerca de", systemImage: "info.circle", detail: AppInfo.version)
                }
            }
        }
        .navigationTitle("Ajustes")
        .modifier(AdaptableTabRootTitleStyle())
        .toolbar {
            // As in WhatsApp: the codes, and the pencil to edit the profile.
            ToolbarItemGroup(placement: .topBarTrailing) {
                Button("Escanear código", systemImage: "qrcode.viewfinder") { showScanner = true }
                if profile.hasToken {
                    Button("Mostrar código del perfil", systemImage: "qrcode") { showProfileCode = true }
                    Button("Editar perfil", systemImage: "pencil") {
                        if twoColumns { selectedArea = .account } else { showAccount = true }
                    }
                }
            }
        }
    }

    /// A row that opens its area: pushed in one column, beside the list
    /// (highlighted while shown) with two.
    @ViewBuilder
    private func row<Label: View>(_ area: Area, @ViewBuilder label: () -> Label) -> some View {
        if twoColumns {
            Button { selectedArea = area } label: {
                HStack {
                    label()
                    Image(systemName: "chevron.right")
                        .font(.footnote.weight(.semibold))
                        .foregroundStyle(.tertiary)
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .listRowBackground(selectedArea == area ? Color.accentColor.opacity(0.18) : nil)
        } else {
            NavigationLink(value: area, label: label)
        }
    }

    @ViewBuilder
    private func detail(_ area: Area) -> some View {
        switch area {
        case .account: AccountEditor()
        case .relay: page("Relay") { relaySection }
        case .identity: page("Cuenta") { identitySection }
        case .catalog: page("Catálogo") { catalogSection; translationSection }
        case .playback: page("Reproducción") { playbackSection }
        case .notifications: page("Notificaciones") { notificationsSection }
        case .storage: page("Almacenamiento y descargas") { storageSection }
        case .about: page("Acerca de") { aboutSection }
        }
    }

    // MARK: Profile

    private var accountHeader: some View {
        Section {
            Button {
                if twoColumns { selectedArea = .account } else { showAccount = true }
            } label: {
                VStack(spacing: 8) {
                    AvatarView(account: profile.account, size: 96)
                    Text(profile.account?.displayName ?? String(localized: "Tu perfil"))
                        .font(.title2.bold())
                        .foregroundStyle(.primary)
                    if let subtitle = accountSubtitle {
                        Text(subtitle)
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                            .multilineTextAlignment(.center)
                    }
                }
                .frame(maxWidth: .infinity)
                .padding(.vertical, 4)
            }
            .buttonStyle(.plain)
            .disabled(!profile.hasToken)
            .listRowBackground(Color.clear)
        }
    }

    private var accountSubtitle: String? {
        guard profile.hasToken else { return String(localized: "Configura el relay y la cuenta para tener un perfil") }
        if let email = profile.account?.email { return email }
        if profile.account?.name == nil { return String(localized: "Toca para añadir tu nombre y tu foto") }
        return nil
    }

    private var relayDetail: String {
        profile.isRelayConfigured ? URL(string: profile.relayURL)?.host() ?? profile.relayURL : String(localized: "Pendiente")
    }

    /// One area of the settings on its own screen.
    private func page<Content: View>(_ title: LocalizedStringKey, @ViewBuilder content: () -> Content) -> some View {
        Form { content() }
            .navigationTitle(title)
            .navigationBarTitleDisplayMode(.inline)
            .scrollDismissesKeyboard(.interactively)
    }

    // MARK: Relay

    private var relaySection: some View {
        Section {
            if profile.isRelayConfigured {
                LabeledContent {
                    Text(profile.relayURL)
                        .lineLimit(1)
                        .truncationMode(.middle)
                } label: {
                    Label("Activo", systemImage: "checkmark.circle.fill")
                        .foregroundStyle(.green)
                }
            }
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
                    .focused($hostFocused)
                    .submitLabel(.go)
                    .onSubmit(testRelay)
            }
            Button(action: testRelay) {
                if testing {
                    HStack(spacing: 10) {
                        ProgressView()
                        Text("Probando conexión…")
                    }
                } else {
                    Label(candidateIsActive ? String(localized: "Probar conexión") : String(localized: "Probar y guardar"), systemImage: "network")
                }
            }
            .disabled(testing || candidateURL == nil)
            if let testResult {
                resultView(testResult)
            }
            // The guided setup again (relay, account, profile, alerts).
            Button("Asistente de configuración inicial", systemImage: "wand.and.stars") { showSetup = true }
        } footer: {
            Text("Por ejemplo relay.midominio.com o 192.168.1.20:8787. HTTP sólo funciona en tu red local.")
        }
    }

    @ViewBuilder
    private func resultView(_ result: TestResult) -> some View {
        switch result {
        case .success(let message):
            Label(message, systemImage: "checkmark.circle.fill").foregroundStyle(.green).font(.footnote)
        case .warning(let message):
            Label(message, systemImage: "lock.trianglebadge.exclamationmark").foregroundStyle(.orange).font(.footnote)
        case .failure(let message):
            ErrorNotice(message: message)
        }
    }

    private func loadFields() {
        preferences = profile.preferences
        guard host.isEmpty else { return }
        let source: String? = profile.isRelayConfigured
            ? profile.relayURL
            : profile.savedRelayAddress.flatMap(RelayEndpoint.canonicalURL)
        if let source {
            let fields = RelayEndpoint.fields(from: source)
            scheme = fields.scheme
            host = fields.host
        }
    }

    private func testRelay() {
        guard let candidate = candidateURL, !testing else { return }
        hostFocused = false
        Task {
            testing = true
            testResult = nil
            defer { testing = false }
            do {
                let health: HealthResponse = try await RelayClient.get(base: candidate, path: "/health", timeoutInterval: 10)
                guard health.ok else {
                    testResult = .failure(String(localized: "El relay respondió de forma inesperada."))
                    return
                }
                let migrated = try SecureTokenStore.migrateLegacyToken(to: candidate)
                let madeSynchronizable = try SecureTokenStore.makeCurrentTokenSynchronizable(for: candidate)
                profile.useRelay(candidate)
                let fields = RelayEndpoint.fields(from: profile.relayURL)
                scheme = fields.scheme
                host = fields.host
                let connected = health.summary.map { String(localized: "Conectado (\($0)).") } ?? String(localized: "Conectado.")
                if !profile.hasToken, await RelayClient.requiresIdentity(base: candidate) {
                    testResult = .warning(String(localized: "\(connected) Este relay requiere una cuenta: créala, restáurala o impórtala en Cuenta."))
                } else if migrated || madeSynchronizable {
                    testResult = .success(String(localized: "\(connected) La identidad se guardó como sincronizable en el Llavero de iCloud."))
                } else {
                    testResult = .success(String(localized: "\(connected) Buscar, Recientes y Continuar ya están disponibles."))
                }
                await profile.sync(force: true)
            } catch is CancellationError {
                return
            } catch {
                testResult = .failure(error.localizedDescription)
            }
        }
    }

    // MARK: Identity

    private var identitySection: some View {
        Section {
            if !profile.isRelayConfigured {
                Label("Primero prueba y guarda la dirección del relay", systemImage: "exclamationmark.triangle.fill")
                    .foregroundStyle(.orange)
            } else if profile.hasToken {
                Label("Cuenta configurada", systemImage: "lock.fill")
                    .foregroundStyle(.green)
                // The same QR a TV shows, for another phone.
                Button("Mostrar código del perfil…", systemImage: "qrcode") { showProfileCode = true }
                Button("Respaldar token…") { showBackup = true }
                Button("Cambiar de cuenta…", role: .destructive) { showChangeWarning = true }
            } else {
                Button("Crear cuenta…") { showProvisioning = true }
                Button("Restaurar desde Llavero de iCloud…", action: restoreFromICloud)
                Button("Importar token manualmente…") { showImport = true }
            }
            // A profile from a TV or another phone, a TV pairing code, or a
            // relay's setup code.
            Button("Escanear código…", systemImage: "qrcode.viewfinder") { showScanner = true }
            if let tokenError {
                ErrorNotice(message: tokenError)
            }
        } footer: {
            Text("El token es la llave de tu cuenta: respáldalo y compártelo sólo con tus dispositivos.")
        }
    }

    private func install(token: String) throws {
        guard profile.isRelayConfigured else {
            throw RelayRequestError(message: String(localized: "Primero prueba la conexión del relay."))
        }
        try SecureTokenStore.save(token, for: profile.relayURL)
        tokenError = nil
        profile.reloadIdentity()
        Task { await profile.sync(force: true) }
    }

    private func restoreFromICloud() {
        do {
            try install(token: SecureTokenStore.restoreFromICloud(for: profile.relayURL))
            testResult = .success(String(localized: "Identidad restaurada desde el Llavero de iCloud."))
        } catch {
            tokenError = error.localizedDescription
        }
    }

    private func presentAfterChangeWarning(_ action: IdentityChangeAction) {
        showChangeWarning = false
        Task {
            // Let the warning sheet finish dismissing before presenting the next one.
            try? await Task.sleep(for: .milliseconds(350))
            switch action {
            case .backup: showBackup = true
            case .create: showProvisioning = true
            case .importExisting: showImport = true
            }
        }
    }

    // MARK: Preferences

    private var catalogSection: some View {
        Section {
            Picker(selection: $preferences.titleLanguage) {
                ForEach(TitleLanguage.allCases) { Text($0.displayName).tag($0) }
            } label: {
                InfoLabel("Idioma de los títulos", info: "En japonés, Continuar viendo muestra también el título en inglés.")
            }
            Toggle(isOn: $preferences.hideAdult) {
                InfoLabel("Ocultar series +18", info: "Las quita de Recientes y Buscar (clasificación R+ y Rx).")
            }
        }
        .disabled(!profile.isRelayConfigured)
    }

    private var playbackSection: some View {
        Section {
            Picker(selection: $preferences.defaultTrack) {
                ForEach(StreamKind.allCases) { Text($0.displayName).tag($0) }
            } label: {
                InfoLabel("Servidores por defecto", info: "Sub: audio japonés con subtítulos. Dub: audio en inglés.")
            }
            Picker("Subtítulos en Sub", selection: $preferences.subtitleLanguage) {
                ForEach(subtitleOptions, id: \.tag) { option in
                    Text(option.label).tag(option.tag)
                }
            }
            Toggle("Reproducir el siguiente episodio", isOn: $preferences.autoNext)
            Toggle("Saltar intro automáticamente", isOn: $preferences.autoSkipIntro)
            Toggle("Saltar outro automáticamente", isOn: $preferences.autoSkipOutro)
        } footer: {
            Text("Se sincroniza con tus otros dispositivos.")
        }
        .disabled(!profile.isRelayConfigured)
    }

    /// Downloads live on this device.
    private var storageSection: some View {
        Section {
            Toggle(isOn: $autoDeleteDownloads) {
                InfoLabel("Borrar descargas ya vistas", info: "Al terminar el episodio, aquí o en otro dispositivo de tu cuenta.")
            }
            LabeledContent("Espacio usado", value: ByteCountFormatter.string(fromByteCount: downloads.totalBytes, countStyle: .file))
            Button("Borrar todas las descargas", role: .destructive) { showDeleteDownloads = true }
                .disabled(downloads.records.isEmpty)
                .confirmationDialog("¿Borrar \(downloads.records.count) descargas?", isPresented: $showDeleteDownloads, titleVisibility: .visible) {
                    Button("Borrar todas", role: .destructive) { downloads.removeAll() }
                }
        }
    }

    /// The translation depends on the languages installed on this device.
    private var translationSection: some View {
        Section {
            Toggle(isOn: $translateSynopsis) {
                InfoLabel("Traducir sinopsis automáticamente", info: "Con la traducción de iOS, al idioma del dispositivo.")
            }
        }
    }

    private var notificationsSection: some View {
        Section {
            Toggle(isOn: $releaseNotifications) {
                InfoLabel("Avisar de nuevos episodios", info: "Cuando sale un episodio nuevo de una serie de Continuar viendo, en Sub o Dub según la sigas.")
            }
                .disabled(!profile.isRelayConfigured)
                .onChange(of: releaseNotifications) { _, enabled in
                    Task {
                        if enabled, !(await notifier.requestAuthorization()) {
                            notificationsDenied = true
                            releaseNotifications = false
                            return
                        }
                        notificationsDenied = false
                        await notifier.refresh(profile: profile)
                    }
                }
            if notificationsDenied {
                Text("iOS no permite avisos de esta app. Actívalos en Ajustes → Notificaciones.")
                    .font(.footnote)
                    .foregroundStyle(.red)
            }
        }
    }

    private var subtitleOptions: [(tag: String, label: String)] {
        var options: [(tag: String, label: String)] = [("", String(localized: "Automático (idioma del dispositivo)"))]
        options += ["en", "es", "pt", "fr", "de", "it"].map { ($0, languageName($0)) }
        options.append((UserPreferences.subtitlesOff, String(localized: "Desactivados")))
        let current = preferences.subtitleLanguage
        if !options.contains(where: { $0.tag == current }) {
            options.insert((current, languageName(current)), at: 1)
        }
        return options
    }

    /// The language's name in the app's language ("Inglés", "English").
    private func languageName(_ tag: String) -> String {
        let locale = Locale.current
        return locale.localizedString(forIdentifier: tag)?.capitalized(with: locale) ?? tag
    }

    // MARK: About

    @ViewBuilder
    private var aboutSection: some View {
        Section {
            LabeledContent("Versión instalada", value: AppInfo.version)
        } header: {
            Text("Acerca de")
        }
    }
}

enum IdentityChangeAction {
    case backup
    case create
    case importExisting
}

private struct IdentityChangeGuardSheet: View {
    let onChoose: (IdentityChangeAction) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var acknowledgedCurrentToken = false

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Text("Cambiar el token te convierte en otro usuario. El historial y las preferencias actuales seguirán en el servidor, pero este iPhone o iPad no podrá abrirlos de nuevo sin el token actual.")
                    Toggle("Ya guardé el token actual de forma segura", isOn: $acknowledgedCurrentToken)
                    Button("Respaldar token actual…") { onChoose(.backup) }
                } header: {
                    Text("Antes de cambiar")
                } footer: {
                    Text("No continúes si no tienes el token guardado: no existe recuperación de cuenta ni un listado de otros usuarios.")
                }

                Section {
                    Button("Crear una identidad nueva", role: .destructive) { onChoose(.create) }
                    Button("Usar un token existente", role: .destructive) { onChoose(.importExisting) }
                } footer: {
                    Text("Estas acciones permanecen bloqueadas hasta que confirmes que resguardaste el token actual.")
                }
                .disabled(!acknowledgedCurrentToken)
            }
            .navigationTitle("Cambiar identidad")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancelar", role: .cancel) { dismiss() }
                }
            }
        }
    }
}

struct TokenProvisioningSheet: View {
    let relayBaseURL: String
    let onSaved: (String) throws -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var administratorToken: String

    /// `administratorToken` comes filled in from a relay's setup code.
    init(relayBaseURL: String, administratorToken: String = "", onSaved: @escaping (String) throws -> Void) {
        self.relayBaseURL = relayBaseURL
        self.onSaved = onSaved
        _administratorToken = State(initialValue: administratorToken)
    }

    @State private var label = ""
    @State private var generatedToken = ""
    @State private var confirmedBackup = false
    @State private var registering = false
    @State private var errorMessage: String?

    var body: some View {
        NavigationStack {
            Form {
                if generatedToken.isEmpty {
                    Section {
                        SecureField("Token administrador del relay", text: $administratorToken)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                        TextField("Nombre del perfil (opcional), por ejemplo Ana", text: $label)
                    } header: {
                        Text("Aprovisionamiento")
                    } footer: {
                        Text("El token administrador se usa una sola vez para registrar esta identidad; no se guarda en el dispositivo.")
                    }
                    Section {
                        Button("Generar token seguro") {
                            do { generatedToken = try TokenGenerator.make() } catch { errorMessage = error.localizedDescription }
                        }
                        .disabled(administratorToken.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                    } footer: {
                        Text("Este paso crea un secreto de 256 bits. No podrás recuperar el token si lo pierdes.")
                    }
                } else {
                    Section {
                        Text(generatedToken)
                            .font(.system(.footnote, design: .monospaced))
                            .textSelection(.enabled)
                        ShareLink(item: generatedToken) {
                            Label("Copiar o compartir de forma segura", systemImage: "square.and.arrow.up")
                        }
                        Toggle("Ya guardé este token en un lugar seguro", isOn: $confirmedBackup)
                    } header: {
                        Text("Respalda este token antes de continuar")
                    } footer: {
                        Text("Será la única credencial para acceder a este historial y sus preferencias. Puedes usarlo después en otro dispositivo para compartirlos.")
                    }
                    Section {
                        Button(action: registerAndSave) {
                            if registering {
                                HStack(spacing: 10) {
                                    ProgressView()
                                    Text("Registrando…")
                                }
                            } else {
                                Label("Registrar y usar este token", systemImage: "lock.badge.checkmark")
                            }
                        }
                        .disabled(!confirmedBackup || registering)
                    }
                }

                if let errorMessage {
                    Section { ErrorNotice(message: errorMessage) }
                }
            }
            .navigationTitle("Nueva cuenta")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancelar", role: .cancel) { dismiss() }
                        .disabled(registering)
                }
            }
        }
        .interactiveDismissDisabled(registering)
    }

    private func registerAndSave() {
        Task {
            registering = true
            errorMessage = nil
            defer { registering = false }
            do {
                let _: AdminUserResponse = try await RelayClient.provisionUser(
                    base: relayBaseURL,
                    administratorToken: administratorToken,
                    body: AdminUserRequest(token: generatedToken, label: label)
                )
                try onSaved(generatedToken)
                dismiss()
            } catch {
                errorMessage = error.localizedDescription
            }
        }
    }
}

struct TokenImportSheet: View {
    /// False when there is no identity to replace (the setup assistant).
    var replacesIdentity = true
    let onSaved: (String) throws -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var token = ""
    @State private var confirmedReplacement = false
    @State private var errorMessage: String?

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    SecureField("Token de 64 caracteres", text: $token)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    if replacesIdentity {
                        Toggle("Entiendo que esto reemplaza la identidad actual", isOn: $confirmedReplacement)
                        Button("Guardar y cambiar identidad", role: .destructive, action: save)
                            .disabled(!confirmedReplacement || token.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                    } else {
                        Button("Guardar", action: save)
                            .disabled(token.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                    }
                } header: {
                    Text("Token existente")
                } footer: {
                    Text("Importa el token de otro dispositivo sólo si quieres acceder exactamente al mismo historial y a las mismas preferencias.")
                }
                if let errorMessage {
                    Section { ErrorNotice(message: errorMessage) }
                }
            }
            .navigationTitle("Importar token")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancelar", role: .cancel) { dismiss() }
                }
            }
        }
    }

    private func save() {
        let candidate = token.trimmingCharacters(in: .whitespacesAndNewlines)
        guard candidate.range(of: "^[A-Fa-f0-9]{64}$", options: .regularExpression) != nil else {
            errorMessage = String(localized: "El token debe contener 64 caracteres hexadecimales.")
            return
        }
        do {
            try onSaved(candidate)
            dismiss()
        } catch {
            errorMessage = error.localizedDescription
        }
    }
}

private struct TokenBackupSheet: View {
    let token: String
    @Environment(\.dismiss) private var dismiss
    @State private var acknowledgedRisk = false

    var body: some View {
        NavigationStack {
            Form {
                Section("Advertencia") {
                    Text("Quien tenga este token puede acceder al historial y las preferencias asociados. No lo envíes por mensajes, correo ni capturas sin protección.")
                    Toggle("Entiendo el riesgo", isOn: $acknowledgedRisk)
                }
                if acknowledgedRisk {
                    Section("Token") {
                        Text(token)
                            .font(.system(.footnote, design: .monospaced))
                            .textSelection(.enabled)
                        ShareLink(item: token) {
                            Label("Copiar o compartir de forma segura", systemImage: "square.and.arrow.up")
                        }
                    }
                }
            }
            .navigationTitle("Respaldar token")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cerrar") { dismiss() }
                }
            }
        }
    }
}

/// A pairing QR shown by a TV app: the relay's setup page
/// (`https://<relay>/setup?code=<code>`, scanned in the app) or the app link
/// `relaycast://pair?bridge=<url>&code=<code>` (older TV apps, and the page's
/// "Abrir en la app" button).
struct TVPairingRequest: Identifiable {
    let code: String
    let relay: String
    var id: String { code }

    init?(url: URL) {
        guard let items = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems,
              let code = items.first(where: { $0.name == "code" })?.value, !code.isEmpty else { return nil }
        let scheme = url.scheme?.lowercased()
        if scheme == "relaycast", url.host?.lowercased() == "pair",
           let relay = items.first(where: { $0.name == "bridge" })?.value, !relay.isEmpty {
            self.relay = relay
        } else if scheme == "https" || scheme == "http", url.path == "/setup",
                  let relay = RelayEndpoint.canonicalURL(url.absoluteString) {
            self.relay = relay
        } else {
            return nil
        }
        self.code = code
    }
}

/// Approves a TV pairing with the active identity: the TV gets this
/// identity's token, so it shares Continue and the preferences.
struct TVPairingSheet: View {
    private enum Phase: Equatable {
        case idle
        case working
        case done
        case failed(String)
    }

    private struct ApproveRequest: Encodable {
        let code: String
        let label: String
    }

    private struct ApproveResponse: Decodable {
        let ok: Bool
    }

    let request: TVPairingRequest
    @Environment(ProfileStore.self) private var profile
    @Environment(\.dismiss) private var dismiss
    @State private var label = ""
    @State private var phase: Phase = .idle

    private var matchesRelay: Bool {
        profile.isRelayConfigured && RelayEndpoint.canonicalURL(request.relay) == profile.relayURL
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Label("Vincular una TV", systemImage: "tv")
                        .font(.headline)
                    Text("La TV usará la identidad activa de este dispositivo: el mismo historial de Continuar viendo y las mismas preferencias. En la TV aparecerá como un perfil.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
                if !matchesRelay {
                    Section {
                        ErrorNotice(message: String(localized: "La TV usa el relay \(request.relay), que no es el configurado aquí. Configura y prueba ese mismo relay en Ajustes y vuelve a escanear el código."))
                    }
                } else if phase == .done {
                    Section {
                        Label("Listo: la TV ya tiene el perfil «\(profileName)».", systemImage: "checkmark.circle.fill")
                            .foregroundStyle(.tint)
                    }
                } else {
                    // The TV shows the profile's name; asked for only when it has none yet.
                    if accountName == nil {
                        Section("Nombre del perfil en la TV") {
                            TextField("Por ejemplo, tu nombre", text: $label)
                                .textInputAutocapitalization(.words)
                        }
                    }
                    Section {
                        Button {
                            approve()
                        } label: {
                            if phase == .working {
                                ProgressView()
                            } else {
                                Text("Vincular TV")
                            }
                        }
                        .disabled(phase == .working)
                    } footer: {
                        if case .failed(let message) = phase {
                            ErrorNotice(message: message)
                        }
                    }
                }
            }
            .navigationTitle("Vincular TV")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button(phase == .done ? String(localized: "Listo") : String(localized: "Cerrar")) { dismiss() }
                }
            }
        }
        .presentationDetents([.medium, .large])
    }

    private var accountName: String? {
        profile.account?.displayName.flatMap { $0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? nil : $0 }
    }

    private var profileName: String {
        if let accountName { return accountName }
        let value = label.trimmingCharacters(in: .whitespacesAndNewlines)
        return value.isEmpty ? String(localized: "Perfil") : value
    }

    private func approve() {
        phase = .working
        Task {
            do {
                let _: ApproveResponse = try await RelayClient.post(base: profile.relayURL, path: "/api/pair/approve", body: ApproveRequest(code: request.code, label: profileName))
                phase = .done
            } catch {
                phase = .failed(error.localizedDescription)
            }
        }
    }
}

/// A settings row: icon and title, and a value on the right.
struct SettingsRow: View {
    let title: LocalizedStringKey
    let systemImage: String
    var detail: String?

    init(_ title: LocalizedStringKey, systemImage: String, detail: String? = nil) {
        self.title = title
        self.systemImage = systemImage
        self.detail = detail
    }

    var body: some View {
        LabeledContent {
            if let detail {
                Text(detail).lineLimit(1)
            }
        } label: {
            Label(title, systemImage: systemImage)
        }
    }
}

/// An option's title with a short explanation behind an (i) next to it.
struct InfoLabel: View {
    let title: LocalizedStringKey
    let info: LocalizedStringKey

    init(_ title: LocalizedStringKey, info: LocalizedStringKey) {
        self.title = title
        self.info = info
    }

    var body: some View {
        HStack(spacing: 6) {
            Text(title)
            InfoButton(info)
        }
    }
}

/// A small (i) that shows one or two sentences in a popover.
struct InfoButton: View {
    let info: LocalizedStringKey
    @State private var showing = false

    init(_ info: LocalizedStringKey) {
        self.info = info
    }

    var body: some View {
        Button { showing = true } label: {
            Image(systemName: "info.circle")
                .font(.footnote)
                .foregroundStyle(.secondary)
        }
        .buttonStyle(.borderless)
        .accessibilityLabel("Más información")
        .popover(isPresented: $showing) {
            Text(info)
                .font(.callout)
                .padding()
                .frame(idealWidth: 300)
                .fixedSize(horizontal: false, vertical: true)
                .presentationCompactAdaptation(.popover)
        }
    }
}

/// The profile picture: the photo, or the color with the emoji or the
/// name's initial.
struct AvatarView: View {
    let account: AccountProfile?
    var size: CGFloat = 40

    var body: some View {
        if let photo = account?.avatar?.photo, let data = Data(base64Encoded: photo), let image = UIImage(data: data) {
            Image(uiImage: image)
                .resizable()
                .scaledToFill()
                .frame(width: size, height: size)
                .clipShape(Circle())
                .accessibilityHidden(true)
        } else {
            ZStack {
                Circle().fill(Color(avatarHex: account?.avatar?.color))
                if let symbol {
                    Text(symbol)
                        .font(.system(size: size * 0.45, weight: .semibold))
                        .foregroundStyle(.white)
                } else {
                    Image(systemName: "person.fill")
                        .font(.system(size: size * 0.45))
                        .foregroundStyle(.white)
                }
            }
            .frame(width: size, height: size)
            .accessibilityHidden(true)
        }
    }

    private var symbol: String? {
        if let emoji = account?.avatar?.emoji, !emoji.isEmpty { return emoji }
        return account?.displayName?.first.map { String($0).uppercased() }
    }
}

extension Color {
    /// "#RRGGBB" from the relay's palette; the app's indigo otherwise.
    init(avatarHex hex: String?) {
        guard let hex, hex.count == 7, hex.hasPrefix("#"), let value = UInt32(hex.dropFirst(), radix: 16) else {
            self = Color(red: 0x4B / 255, green: 0x49 / 255, blue: 0xD6 / 255)
            return
        }
        self = Color(red: Double((value >> 16) & 0xFF) / 255, green: Double((value >> 8) & 0xFF) / 255, blue: Double(value & 0xFF) / 255)
    }
}

/// Edits the name, the recovery email and the picture of the identity; they
/// are kept on the relay, so every phone and TV of the identity shows them.
struct AccountEditor: View {
    /// After saving, instead of going back (the setup assistant goes on).
    var onSaved: (() -> Void)? = nil
    @Environment(ProfileStore.self) private var profile
    @Environment(\.dismiss) private var dismiss
    @State private var name = ""
    @State private var email = ""
    @State private var color = AccountProfile.colors[0]
    @State private var emoji: String?
    @State private var photo: String?
    @State private var photoChanged = false
    @State private var photoItem: PhotosPickerItem?
    @State private var saving = false
    @State private var loaded = false
    @State private var errorMessage: String?

    private var preview: AccountProfile {
        AccountProfile(name: name.isEmpty ? nil : name, email: nil, avatar: .init(color: color, emoji: emoji, photo: photo), label: profile.account?.label)
    }

    var body: some View {
        Form {
            Section {
                VStack(spacing: 12) {
                    AvatarView(account: preview, size: 120)
                    HStack {
                        PhotosPicker(selection: $photoItem, matching: .images) {
                            Label("Elegir foto", systemImage: "photo")
                        }
                        if photo != nil {
                            Button("Quitar foto", role: .destructive) {
                                photo = nil
                                photoChanged = true
                            }
                        }
                    }
                    .buttonStyle(.bordered)
                }
                .frame(maxWidth: .infinity)
            }
            .listRowBackground(Color.clear)
            Section {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 10) {
                        ForEach(AccountProfile.colors, id: \.self) { option in
                            Button { color = option } label: {
                                Circle()
                                    .fill(Color(avatarHex: option))
                                    .frame(width: 36, height: 36)
                                    .overlay { if option == color { Image(systemName: "checkmark").foregroundStyle(.white) } }
                            }
                            .buttonStyle(.plain)
                        }
                    }
                    .padding(.vertical, 4)
                }
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 10) {
                        Button { emoji = nil } label: {
                            Text(preview.displayName?.first.map { String($0).uppercased() } ?? "A")
                                .font(.title3.bold())
                                .frame(width: 40, height: 40)
                                .background(emoji == nil ? Color.accentColor.opacity(0.25) : Color.clear, in: Circle())
                        }
                        .buttonStyle(.plain)
                        .accessibilityLabel("Inicial del nombre")
                        ForEach(AccountProfile.emojis, id: \.self) { option in
                            Button { emoji = option } label: {
                                Text(option)
                                    .font(.title2)
                                    .frame(width: 40, height: 40)
                                    .background(emoji == option ? Color.accentColor.opacity(0.25) : Color.clear, in: Circle())
                            }
                            .buttonStyle(.plain)
                        }
                    }
                    .padding(.vertical, 4)
                }
            } header: {
                Text("Avatar")
            }
            Section {
                TextField("Nombre", text: $name)
                    .textContentType(.nickname)
                HStack {
                    TextField("Correo de recuperación", text: $email)
                        .keyboardType(.emailAddress)
                        .textContentType(.emailAddress)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    InfoButton("Para recuperar la cuenta más adelante. Por ahora no se envían correos.")
                }
            } header: {
                Text("Perfil")
            } footer: {
                Text("Se ven en tus teléfonos y en las TVs de tu cuenta.")
            }
            if let errorMessage {
                Section { ErrorNotice(message: errorMessage) }
            }
        }
        .navigationTitle("Perfil")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .confirmationAction) {
                Button(saving ? String(localized: "Guardando…") : String(localized: "Guardar")) {
                    Task { await save() }
                }
                .disabled(saving)
            }
        }
        .onAppear {
            guard !loaded else { return }
            loaded = true
            let account = profile.account
            name = account?.name ?? account?.label ?? ""
            email = account?.email ?? ""
            color = account?.avatar?.color ?? AccountProfile.colors[0]
            emoji = account?.avatar?.emoji
            photo = account?.avatar?.photo
        }
        .onChange(of: photoItem) { _, item in
            guard let item else { return }
            Task {
                guard let data = try? await item.loadTransferable(type: Data.self), let reduced = Self.avatarPhoto(from: data) else {
                    errorMessage = String(localized: "No se pudo usar esa imagen.")
                    return
                }
                photo = reduced
                photoChanged = true
                errorMessage = nil
            }
        }
    }

    private func save() async {
        saving = true
        errorMessage = nil
        defer { saving = false }
        let update = AccountUpdate(
            name: name.trimmingCharacters(in: .whitespacesAndNewlines),
            email: email.trimmingCharacters(in: .whitespacesAndNewlines),
            avatar: .init(color: color, emoji: emoji ?? "", photo: photoChanged ? photo ?? "" : nil)
        )
        do {
            try await profile.saveAccount(update)
            if let onSaved { onSaved() } else { dismiss() }
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    /// The photo's center as a 256 × 256 JPEG, Base64 (the relay keeps it
    /// under 64 KB).
    static func avatarPhoto(from data: Data) -> String? {
        guard let image = UIImage(data: data), image.size.width > 0, image.size.height > 0 else { return nil }
        let side = min(image.size.width, image.size.height)
        let scale = 256 / side
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        let output = UIGraphicsImageRenderer(size: CGSize(width: 256, height: 256), format: format).image { _ in
            image.draw(in: CGRect(
                x: -(image.size.width - side) / 2 * scale,
                y: -(image.size.height - side) / 2 * scale,
                width: image.size.width * scale,
                height: image.size.height * scale
            ))
        }
        return output.jpegData(compressionQuality: 0.7)?.base64EncodedString()
    }
}

/// What the app says about itself.
enum AppInfo {
    static var version: String {
        let info = Bundle.main.infoDictionary
        let version = info?["CFBundleShortVersionString"] as? String ?? "?"
        let build = info?["CFBundleVersion"] as? String
        return build.map { "\(version) (\($0))" } ?? version
    }
}
