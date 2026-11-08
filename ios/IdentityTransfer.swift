import CoreImage.CIFilterBuiltins
import PhotosUI
import SwiftUI
import VisionKit

/// A profile's identity shown as a QR by a TV app ("Llevar al teléfono") or
/// by a phone (Ajustes › Mostrar código del perfil):
/// `relaycast://identity?bridge=<url>&token=<64 hex>&label=<name>`.
struct IdentityTransferRequest: Identifiable {
    let relay: String
    let token: String
    let label: String
    var id: String { token }

    init?(url: URL) {
        guard url.scheme?.lowercased() == "relaycast", url.host?.lowercased() == "identity",
              let items = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems,
              let relay = items.first(where: { $0.name == "bridge" })?.value.flatMap(RelayEndpoint.canonicalURL),
              let token = items.first(where: { $0.name == "token" })?.value?.lowercased(),
              token.range(of: "^[a-f0-9]{64}$", options: .regularExpression) != nil else { return nil }
        self.relay = relay
        self.token = token
        let label = items.first(where: { $0.name == "label" })?.value?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        self.label = label.isEmpty ? String(localized: "Perfil") : label
    }

    /// The link for a profile's QR. Values are percent-encoded (%20, not +),
    /// which both Android and iOS decode the same way.
    static func link(relay: String, token: String, label: String) -> String {
        let allowed = CharacterSet.alphanumerics.union(CharacterSet(charactersIn: "-._~"))
        func encoded(_ value: String) -> String { value.addingPercentEncoding(withAllowedCharacters: allowed) ?? "" }
        return "relaycast://identity?bridge=\(encoded(relay))&token=\(encoded(token))&label=\(encoded(label))"
    }
}

/// Shows this device's profile as the same QR a TV shows, so another phone
/// can use the same Continue and preferences. Whoever scans it gets that
/// history, so it warns first and hides the code after a minute. The name is
/// what the other phone shows ("Usar el perfil …").
struct ProfileCodeSheet: View {
    let relay: String
    let token: String
    /// The profile's name, which the other phone shows; asked for only when
    /// the profile has none yet.
    var profileName: String?
    @Environment(\.dismiss) private var dismiss
    @State private var name = ""
    @State private var showing = false

    private var hasProfileName: Bool { !(profileName ?? "").trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }

    private var link: String {
        let label = (hasProfileName ? profileName ?? "" : name).trimmingCharacters(in: .whitespacesAndNewlines)
        return IdentityTransferRequest.link(relay: relay, token: token, label: label.isEmpty ? String(localized: "Perfil") : label)
    }

    var body: some View {
        NavigationStack {
            Form {
                if showing {
                    Section {
                        if let image = Self.qrImage(link) {
                            Image(uiImage: image)
                                .interpolation(.none)
                                .resizable()
                                .scaledToFit()
                                .padding(12)
                                .background(.white, in: RoundedRectangle(cornerRadius: 12))
                                .frame(maxWidth: 280)
                                .frame(maxWidth: .infinity)
                                .accessibilityLabel("Código QR del perfil")
                        }
                    } footer: {
                        Text("En el otro teléfono, abre Relaycast › Ajustes › Escanear código, o apunta la cámara al código. El código se oculta en un minuto.")
                    }
                } else {
                    Section {
                        Text("El código contiene la identidad de este perfil: quien lo escanee tendrá su Continuar viendo y sus preferencias. Muéstralo sólo a tus dispositivos o a quien deba compartirlos.")
                        if !hasProfileName {
                            TextField("Nombre que verá el otro teléfono", text: $name)
                        }
                    }
                    Section {
                        Button("Mostrar código") { showing = true }
                    }
                }
            }
            .navigationTitle("Código del perfil")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: showing ? .confirmationAction : .cancellationAction) {
                    Button(showing ? String(localized: "Listo") : String(localized: "Cancelar")) { dismiss() }
                }
            }
            .task(id: showing) {
                guard showing else { return }
                try? await Task.sleep(for: .seconds(60))
                if !Task.isCancelled { dismiss() }
            }
        }
        .presentationDetents([.large])
    }

    /// A sharp QR image (CoreImage draws one point per module).
    private static func qrImage(_ text: String) -> UIImage? {
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(text.utf8)
        filter.correctionLevel = "M"
        guard let output = filter.outputImage?.transformed(by: CGAffineTransform(scaleX: 10, y: 10)),
              let image = CIContext().createCGImage(output, from: output.extent) else { return nil }
        return UIImage(cgImage: image)
    }
}

/// A relay's setup code, for a new device:
/// `relaycast://provision?bridge=<url>&admin=<administrator token>`. It sets
/// the relay and, with `admin`, opens "Nueva identidad" with the
/// administrator token filled in. That token is never stored; whoever has
/// the code can create identities on the relay.
struct RelaySetupRequest: Identifiable {
    let relay: String
    let administratorToken: String?
    var id: String { relay }

    init?(url: URL) {
        guard url.scheme?.lowercased() == "relaycast", url.host?.lowercased() == "provision",
              let items = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems,
              let relay = items.first(where: { $0.name == "bridge" })?.value.flatMap(RelayEndpoint.canonicalURL) else { return nil }
        self.relay = relay
        let token = items.first(where: { $0.name == "admin" })?.value?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        administratorToken = token.isEmpty || token.count > 512 || token.contains(where: \.isWhitespace) ? nil : token
    }
}

/// What a scanned code or an opened link asks for: a profile to use here,
/// a TV to pair with this device's identity, or a relay to set up.
enum ScannedCode: Identifiable {
    case identity(IdentityTransferRequest)
    case pairing(TVPairingRequest)
    case relaySetup(RelaySetupRequest)

    var id: String {
        switch self {
        case .identity(let request): "identity:\(request.id)"
        case .pairing(let request): "pairing:\(request.id)"
        case .relaySetup(let request): "setup:\(request.id)"
        }
    }

    init?(_ text: String) {
        guard let url = URL(string: text.trimmingCharacters(in: .whitespacesAndNewlines)) else { return nil }
        self.init(url: url)
    }

    init?(url: URL) {
        if let identity = IdentityTransferRequest(url: url) {
            self = .identity(identity)
        } else if let pairing = TVPairingRequest(url: url) {
            self = .pairing(pairing)
        } else if let setup = RelaySetupRequest(url: url) {
            self = .relaySetup(setup)
        } else {
            return nil
        }
    }
}

/// Sets up this device from a relay's setup code: tests the relay, then
/// asks how to identify. Recommended: an existing account, with that
/// account's profile code (camera or an image from Photos), iCloud Keychain
/// or its token; or a new account, with the code's administrator token
/// filled in (kept only in memory, never stored). A code without a token
/// sets the relay, and asks only if this device has no identity for it yet.
struct RelaySetupSheet: View {
    let request: RelaySetupRequest
    @Environment(ProfileStore.self) private var profile
    @Environment(\.dismiss) private var dismiss
    @State private var confirmedBackup = false
    @State private var working = false
    /// Relay tested: how to identify comes next.
    @State private var identifying = false
    @State private var errorMessage: String?
    @State private var creating = false
    @State private var importing = false
    @State private var scanning = false
    @State private var pendingScan: ScannedCode?
    @State private var identity: IdentityTransferRequest?
    /// An identity was installed from this sheet (new or imported).
    @State private var installed = false
    /// The relay and its token when identifying began, to tell whether a
    /// profile code's sheet changed them.
    @State private var before: (relay: String, token: String)?

    private var sameRelay: Bool { profile.isRelayConfigured && profile.relayURL == request.relay }
    private var replacesIdentity: Bool { sameRelay && profile.hasToken && request.administratorToken != nil }

    var body: some View {
        NavigationStack {
            if identifying {
                identifyForm
            } else {
                confirmForm
            }
        }
        .interactiveDismissDisabled(working)
    }

    private var confirmForm: some View {
        Form {
            Section {
                Label("Configurar con el relay \(request.relay)", systemImage: "server.rack")
                    .font(.headline)
                Text(request.administratorToken == nil
                     ? String(localized: "El código trae la dirección del relay. Después, identifícate con tu cuenta o crea una nueva.")
                     : String(localized: "El código trae la dirección del relay y su token administrador. Después, identifícate con tu cuenta (recomendado) o crea una nueva. El token administrador no se guarda en el dispositivo."))
                    .foregroundStyle(.secondary)
            }
            if profile.isRelayConfigured, !sameRelay {
                Section {
                    Text("Este dispositivo usa \(profile.relayURL). Su identidad sigue guardada para esa dirección.")
                } header: {
                    Text("Relay actual")
                }
            }
            if replacesIdentity {
                Section {
                    Toggle("Ya guardé el token actual de forma segura", isOn: $confirmedBackup)
                } header: {
                    Text("Reemplaza la identidad actual")
                } footer: {
                    Text("Si no guardaste el token de la identidad actual, este dispositivo no podrá volver a abrir su historial. Puedes respaldarlo en Ajustes › Cuenta.")
                }
            }
            Section {
                Button(working ? String(localized: "Conectando…") : String(localized: "Continuar")) {
                    Task { await connect() }
                }
                .disabled(working || (replacesIdentity && !confirmedBackup))
            }
            if let errorMessage {
                Section { ErrorNotice(message: errorMessage) }
            }
        }
        .navigationTitle("Configurar relay")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .cancellationAction) {
                Button("Cancelar", role: .cancel) { dismiss() }
                    .disabled(working)
            }
        }
    }

    /// Existing account first (the recommended way), then a new one.
    private var identifyForm: some View {
        Form {
            Section {
                Label("Relay listo: \(request.relay)", systemImage: "checkmark.circle.fill")
                    .foregroundStyle(.green)
                Text("Ahora identifica este dispositivo con tu cuenta.")
                    .foregroundStyle(.secondary)
            }
            Section {
                Button("Escanear el código de mi perfil", systemImage: "qrcode.viewfinder") { scanning = true }
                Button("Restaurar desde Llavero de iCloud", systemImage: "icloud", action: restore)
                Button("Importar token manualmente…", systemImage: "key") { importing = true }
            } header: {
                Text("Ya tengo una cuenta")
            } footer: {
                Text("Recomendado: en un dispositivo con tu cuenta abre Ajustes › Mostrar código del perfil (o en la TV, mantén OK sobre tu perfil › Llevar al teléfono) y escanéalo, o elige una captura del código desde Fotos.")
            }
            Section {
                Button("Crear una cuenta nueva…", systemImage: "person.badge.plus") { creating = true }
            } header: {
                Text("No tengo cuenta")
            } footer: {
                Text(request.administratorToken != nil
                     ? String(localized: "Usa el token administrador del código de configuración; no se guarda en el dispositivo.")
                     : String(localized: "Necesitarás el token administrador del relay."))
            }
            if let errorMessage {
                Section { ErrorNotice(message: errorMessage) }
            }
            Section {
                Button("Configurar la cuenta más tarde") {
                    adoptRelay()
                    dismiss()
                }
            }
        }
        .navigationTitle("Tu cuenta")
        .navigationBarTitleDisplayMode(.inline)
        .sheet(isPresented: $creating, onDismiss: closeIfIdentified) {
            TokenProvisioningSheet(relayBaseURL: request.relay, administratorToken: request.administratorToken ?? "") { token in
                try install(token)
            }
        }
        .sheet(isPresented: $importing, onDismiss: closeIfIdentified) {
            // The replacement was confirmed on the first screen.
            TokenImportSheet(replacesIdentity: false) { token in try install(token) }
        }
        .sheet(isPresented: $scanning, onDismiss: openScanned) {
            CodeScannerSheet { pendingScan = $0 }
        }
        .sheet(item: $identity, onDismiss: closeIfIdentified) { request in
            IdentityImportSheet(request: request)
        }
    }

    private func connect() async {
        working = true
        errorMessage = nil
        defer { working = false }
        do {
            let health: HealthResponse = try await RelayClient.get(base: request.relay, path: "/health", timeoutInterval: 10)
            guard health.ok else { throw RelayRequestError(message: String(localized: "El relay respondió de forma inesperada.")) }
            if request.administratorToken == nil {
                adoptRelay()
                Task { await profile.sync(force: true) }
                // Already identified on this relay: nothing else to do.
                if profile.hasToken {
                    dismiss()
                    return
                }
            }
            before = (profile.relayURL, SecureTokenStore.currentToken(for: profile.relayURL))
            identifying = true
        } catch is CancellationError {
            return
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    private func adoptRelay() {
        if !sameRelay { profile.useRelay(request.relay) }
    }

    /// The token in the relay's Keychain item before switching, so the
    /// identity loads with it.
    private func install(_ token: String) throws {
        try SecureTokenStore.save(token, for: request.relay)
        if sameRelay {
            profile.reloadIdentity()
        } else {
            profile.useRelay(request.relay)
        }
        Task { await profile.sync(force: true) }
        installed = true
    }

    private func restore() {
        errorMessage = nil
        do {
            try install(SecureTokenStore.restoreFromICloud(for: request.relay))
            dismiss()
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    private func openScanned() {
        guard let code = pendingScan else { return }
        pendingScan = nil
        errorMessage = nil
        switch code {
        case .identity(let request): identity = request
        case .relaySetup, .pairing:
            errorMessage = String(localized: "Ese no es un código de perfil. Escanea el código de tu perfil (Ajustes › Mostrar código del perfil, o Llevar al teléfono en la TV).")
        }
    }

    /// Done once an identity was installed here, or a profile code's sheet
    /// changed the relay or its token.
    private func closeIfIdentified() {
        if installed {
            dismiss()
            return
        }
        guard let before, profile.hasToken else { return }
        if profile.relayURL != before.relay || SecureTokenStore.currentToken(for: profile.relayURL) != before.token {
            dismiss()
        }
    }
}

/// Uses the profile from a TV's QR on this device: same Continue and
/// preferences. A different relay is tested and adopted first.
struct IdentityImportSheet: View {
    let request: IdentityTransferRequest
    @Environment(ProfileStore.self) private var profile
    @Environment(\.dismiss) private var dismiss
    @State private var confirmedBackup = false
    @State private var working = false
    @State private var errorMessage: String?

    private var sameRelay: Bool { profile.isRelayConfigured && profile.relayURL == request.relay }
    private var alreadyInUse: Bool { sameRelay && SecureTokenStore.currentToken(for: request.relay) == request.token }
    private var replacesIdentity: Bool { sameRelay && profile.hasToken && !alreadyInUse }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Label("Usar el perfil «\(request.label)»", systemImage: "person.crop.circle.badge.checkmark")
                        .font(.headline)
                    Text("Este dispositivo usará el mismo Continuar viendo y las mismas preferencias que ese perfil.")
                        .foregroundStyle(.secondary)
                }
                if alreadyInUse {
                    Section {
                        Label("Este dispositivo ya usa ese perfil.", systemImage: "checkmark.circle.fill")
                            .foregroundStyle(.green)
                    }
                } else {
                    if !sameRelay {
                        Section {
                            Text(profile.isRelayConfigured
                                 ? String(localized: "El código es del relay \(request.relay) y este dispositivo usa \(profile.relayURL). Al continuar, la app se conectará a \(request.relay).")
                                 : String(localized: "Al continuar, la app se conectará al relay \(request.relay)."))
                        } header: {
                            Text("Relay")
                        }
                    }
                    if replacesIdentity {
                        Section {
                            Toggle("Ya guardé el token actual de forma segura", isOn: $confirmedBackup)
                        } header: {
                            Text("Reemplaza la identidad actual")
                        } footer: {
                            Text("Si no guardaste el token de la identidad actual, este dispositivo no podrá volver a abrir su historial. Puedes respaldarlo en Ajustes › Cuenta.")
                        }
                    }
                    Section {
                        Button(working ? String(localized: "Conectando…") : String(localized: "Usar este perfil")) {
                            Task { await apply() }
                        }
                        .disabled(working || (replacesIdentity && !confirmedBackup))
                    }
                }
                if let errorMessage {
                    Section { ErrorNotice(message: errorMessage) }
                }
            }
            .navigationTitle("Perfil de la TV")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(alreadyInUse ? String(localized: "Cerrar") : String(localized: "Cancelar"), role: .cancel) { dismiss() }
                        .disabled(working)
                }
            }
            .interactiveDismissDisabled(working)
        }
        .presentationDetents([.medium, .large])
    }

    private func apply() async {
        working = true
        errorMessage = nil
        defer { working = false }
        do {
            if !sameRelay {
                let health: HealthResponse = try await RelayClient.get(base: request.relay, path: "/health", timeoutInterval: 10)
                guard health.ok else { throw RelayRequestError(message: String(localized: "El relay respondió de forma inesperada.")) }
            }
            // The token goes to the relay's Keychain item before the switch,
            // so the identity loads with it.
            try SecureTokenStore.save(request.token, for: request.relay)
            if sameRelay {
                profile.reloadIdentity()
            } else {
                profile.useRelay(request.relay)
            }
            Task { await profile.sync(force: true) }
            dismiss()
        } catch is CancellationError {
            return
        } catch {
            errorMessage = error.localizedDescription
        }
    }
}

/// Scans a QR in the app: a profile (from a TV's "Llevar al teléfono" or
/// another phone's profile code), a TV pairing code or a relay's setup
/// code. The system Camera app opens the same codes too. A code that is only
/// on this device (a screenshot, an image in a message) is read from Photos.
struct CodeScannerSheet: View {
    let onScan: (ScannedCode) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var notice: String?
    @State private var photo: PhotosPickerItem?
    @State private var reading = false

    var body: some View {
        NavigationStack {
            Group {
                if DataScannerViewController.isSupported && DataScannerViewController.isAvailable {
                    QRScannerView { text in
                        guard let code = ScannedCode(text) else {
                            notice = String(localized: "Ese código no es de Relaycast.")
                            return false
                        }
                        onScan(code)
                        dismiss()
                        return true
                    }
                    .ignoresSafeArea(edges: .bottom)
                } else {
                    ContentUnavailableView(
                        "No se puede escanear",
                        systemImage: "camera",
                        description: Text("Permite el acceso a la cámara en Ajustes › Relaycast, o elige una imagen del código desde Fotos.")
                    )
                }
            }
            .overlay(alignment: .bottom) {
                VStack(spacing: 12) {
                    if let notice {
                        Text(notice)
                            .multilineTextAlignment(.center)
                            .padding(.horizontal, 16)
                            .padding(.vertical, 10)
                            .background(.thinMaterial, in: Capsule())
                    }
                    PhotosPicker(selection: $photo, matching: .images) {
                        Label(reading ? String(localized: "Leyendo imagen…") : String(localized: "Elegir imagen de Fotos"), systemImage: "photo.on.rectangle")
                            .padding(.horizontal, 6)
                    }
                    .buttonStyle(.glass)
                    .controlSize(.large)
                    .disabled(reading)
                }
                .padding(.horizontal, 16)
                .padding(.bottom, 32)
            }
            .onChange(of: photo) { _, item in
                guard let item else { return }
                Task { await read(item) }
            }
            .navigationTitle("Escanear código")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancelar", role: .cancel) { dismiss() }
                }
            }
        }
    }

    /// The image's QR codes, through the same path as the camera.
    private func read(_ item: PhotosPickerItem) async {
        reading = true
        notice = nil
        defer {
            reading = false
            photo = nil
        }
        guard let data = try? await item.loadTransferable(type: Data.self) else {
            notice = String(localized: "No se pudo abrir la imagen.")
            return
        }
        let texts = QRImageReader.codes(in: data)
        guard !texts.isEmpty else {
            notice = String(localized: "No se encontró un código QR en la imagen.")
            return
        }
        guard let code = texts.lazy.compactMap({ ScannedCode($0) }).first else {
            notice = String(localized: "Ese código no es de Relaycast.")
            return
        }
        onScan(code)
        dismiss()
    }
}

/// QR codes in an image (Core Image's detector, on the device).
enum QRImageReader {
    static func codes(in data: Data) -> [String] {
        guard let image = CIImage(data: data, options: [.applyOrientationProperty: true]),
              let detector = CIDetector(ofType: CIDetectorTypeQRCode, context: nil, options: [CIDetectorAccuracy: CIDetectorAccuracyHigh]) else { return [] }
        return detector.features(in: image).compactMap { ($0 as? CIQRCodeFeature)?.messageString }
    }
}

/// VisionKit's live QR scanner. `onCode` returns true once a code was used.
private struct QRScannerView: UIViewControllerRepresentable {
    let onCode: (String) -> Bool

    func makeCoordinator() -> Coordinator { Coordinator(onCode: onCode) }

    func makeUIViewController(context: Context) -> DataScannerViewController {
        let scanner = DataScannerViewController(
            recognizedDataTypes: [.barcode(symbologies: [.qr])],
            qualityLevel: .balanced,
            isHighlightingEnabled: true
        )
        scanner.delegate = context.coordinator
        return scanner
    }

    func updateUIViewController(_ scanner: DataScannerViewController, context: Context) {
        if !scanner.isScanning { try? scanner.startScanning() }
    }

    @MainActor
    final class Coordinator: NSObject, DataScannerViewControllerDelegate {
        private let onCode: (String) -> Bool
        private var used = false

        init(onCode: @escaping (String) -> Bool) {
            self.onCode = onCode
        }

        func dataScanner(_ dataScanner: DataScannerViewController, didAdd addedItems: [RecognizedItem], allItems: [RecognizedItem]) {
            guard !used else { return }
            for item in addedItems {
                guard case .barcode(let barcode) = item, let text = barcode.payloadStringValue else { continue }
                if onCode(text) {
                    used = true
                    dataScanner.stopScanning()
                    return
                }
            }
        }
    }
}
