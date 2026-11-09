import AVKit
import SwiftUI
import UniformTypeIdentifiers

/// A folder of a source, pushed in Fuentes.
struct SourceRoute: Hashable {
    let source: MediaSource
    let path: String
    let title: String
}

/// Fuentes: the app's home. The user's own sources (this device, links,
/// WebDAV, SMB, SFTP) work without any server; the Relay Server is an
/// optional connection for a library with covers, subtitles and sync.
struct SourcesScreen: View {
    /// Switches to the Biblioteca tab (the Relay Server's library).
    let openLibrary: () -> Void

    @Environment(ProfileStore.self) private var profile
    @State private var store = MediaSourceStore()
    @State private var navigation: [SourceRoute] = []
    @State private var adding: MediaSourceKind?
    @State private var importKind: MediaSourceKind = .local
    @State private var importing = false
    @State private var playback: SourcePlayback?
    @State private var errorMessage: String?
    @State private var showSetup = false
    private var library: RelayLibraryStatus { .shared }

    var body: some View {
        NavigationStack(path: $navigation) {
            List {
                if let loadError = store.loadError {
                    Section {
                        Label(loadError, systemImage: "exclamationmark.triangle")
                            .foregroundStyle(.red)
                    }
                }
                if store.sources.isEmpty {
                    Section {
                        ContentUnavailableView {
                            Label("Sin fuentes", systemImage: "externaldrive.connected.to.line.below")
                        } description: {
                            Text("Reproduce tus vídeos desde este dispositivo, tus servidores o tus carpetas compartidas. No necesitas cuenta.")
                        } actions: {
                            addMenu {
                                Label("Añadir una fuente", systemImage: "plus")
                            }
                            .buttonStyle(.glassProminent)
                        }
                    }
                } else {
                    Section("Tus fuentes") {
                        ForEach(store.sources) { source in
                            row(source)
                        }
                        .onDelete { offsets in
                            let ids = Set(offsets.map { store.sources[$0].id })
                            do { try store.remove(ids) } catch { errorMessage = error.localizedDescription }
                        }
                    }
                }
                relaySection
            }
            .navigationTitle("Fuentes")
            .toolbar {
                ToolbarItem(placement: .primaryAction) {
                    addMenu {
                        Label("Añadir", systemImage: "plus")
                    }
                }
            }
            .navigationDestination(for: SourceRoute.self) { route in
                SourceFolderView(route: route, play: play)
            }
        }
        .sheet(item: $adding) { kind in
            AddSourceSheet(initialKind: kind) { source in
                try store.add(source)
            }
        }
        .fileImporter(
            isPresented: $importing,
            allowedContentTypes: importKind == .folder ? [.folder] : [.movie, .audio],
            allowsMultipleSelection: false
        ) { result in
            switch result {
            case .success(let urls):
                if let url = urls.first { addPicked(url) }
            case .failure(let error):
                errorMessage = error.localizedDescription
            }
        }
        .fullScreenCover(item: $playback) { playback in
            SourcePlayerView(playback: playback)
        }
        .fullScreenCover(isPresented: $showSetup) {
            SetupAssistant { showSetup = false }
        }
        .alert("No se pudo abrir", isPresented: Binding(get: { errorMessage != nil }, set: { if !$0 { errorMessage = nil } })) {
            Button("Aceptar", role: .cancel) { errorMessage = nil }
        } message: {
            Text(errorMessage ?? "")
        }
    }

    private func addMenu<MenuLabel: View>(@ViewBuilder label: () -> MenuLabel) -> some View {
        Menu {
            ForEach(MediaSourceKind.allCases) { kind in
                Button(kind.title, systemImage: kind.systemImage) { choose(kind) }
            }
        } label: {
            label()
        }
    }

    @ViewBuilder
    private func row(_ source: MediaSource) -> some View {
        let label = SwiftUI.Label {
            VStack(alignment: .leading, spacing: 2) {
                Text(source.name)
                Text(source.detail)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }
        } icon: {
            Image(systemName: source.kind.systemImage)
        }
        if source.kind.isBrowsable {
            NavigationLink(value: SourceRoute(source: source, path: "", title: source.name)) { label }
        } else {
            Button { play(source, "", source.name) } label: { label }
                .foregroundStyle(.primary)
        }
    }

    @ViewBuilder
    private var relaySection: some View {
        Section {
            switch library.state {
            case .notConfigured:
                Button("Conectar un Relay Server", systemImage: "server.rack") { showSetup = true }
            case .checking:
                HStack(spacing: 12) {
                    ProgressView()
                    Text("Comprobando tu servidor…")
                        .foregroundStyle(.secondary)
                }
            case .available:
                Button(action: openLibrary) {
                    SwiftUI.Label("Tu biblioteca", systemImage: "film.stack")
                }
            case .unavailable:
                SwiftUI.Label {
                    Text("Este servidor no ofrece una biblioteca propia. Tu cuenta, perfiles y TVs siguen funcionando.")
                        .foregroundStyle(.secondary)
                } icon: {
                    Image(systemName: "server.rack")
                }
            case .unreachable(let message):
                VStack(alignment: .leading, spacing: 6) {
                    SwiftUI.Label("No se pudo conectar con tu servidor", systemImage: "wifi.exclamationmark")
                    Text(message)
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
                Button("Reintentar", systemImage: "arrow.clockwise") {
                    Task { await library.refresh(relayURL: profile.relayURL) }
                }
            }
        } header: {
            Text("Relay Server (opcional)")
        } footer: {
            Text("Tu propio servidor: biblioteca con portadas y subtítulos, perfiles, progreso compartido y control de la TV.")
        }
    }

    private func choose(_ kind: MediaSourceKind) {
        if kind.usesFilePicker {
            importKind = kind
            importing = true
        } else {
            adding = kind
        }
    }

    private func addPicked(_ url: URL) {
        let access = url.startAccessingSecurityScopedResource()
        defer { if access { url.stopAccessingSecurityScopedResource() } }
        do {
            let bookmark = try url.bookmarkData(options: [], includingResourceValuesForKeys: nil, relativeTo: nil)
            let name = importKind == .folder ? url.lastPathComponent : url.deletingPathExtension().lastPathComponent
            try store.add(MediaSource(name: name, kind: importKind, bookmark: bookmark))
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    private func play(_ source: MediaSource, _ path: String, _ title: String) {
        do {
            playback = try SourcePlayback.make(source: source, path: path, title: title)
        } catch {
            errorMessage = error.localizedDescription
        }
    }
}

// MARK: - Folder

struct SourceFolderView: View {
    let route: SourceRoute
    let play: (MediaSource, String, String) -> Void

    @State private var entries: [SourceEntry]?
    @State private var errorMessage: String?

    var body: some View {
        List {
            if let errorMessage {
                ContentUnavailableView {
                    Label("No se pudo abrir la carpeta", systemImage: "exclamationmark.triangle")
                } description: {
                    Text(errorMessage)
                } actions: {
                    Button("Reintentar") { Task { await load() } }
                }
            } else if let entries {
                if entries.isEmpty {
                    ContentUnavailableView("Carpeta vacía", systemImage: "folder", description: Text("No hay carpetas ni vídeos compatibles."))
                }
                ForEach(entries) { entry in
                    if entry.directory {
                        NavigationLink(value: SourceRoute(source: route.source, path: entry.path, title: entry.name)) {
                            Label(entry.name, systemImage: "folder")
                        }
                    } else {
                        Button { play(route.source, entry.path, (entry.name as NSString).deletingPathExtension) } label: {
                            Label(entry.name, systemImage: "play.rectangle")
                        }
                        .foregroundStyle(.primary)
                    }
                }
            } else {
                HStack {
                    Spacer()
                    ProgressView()
                    Spacer()
                }
                .listRowBackground(Color.clear)
            }
            Section {
            } footer: {
                Text("En iPhone y iPad se reproducen MP4, MOV y audio. Para MKV y subtítulos, usa tu Relay Server.")
            }
        }
        .navigationTitle(route.title)
        .task { await load() }
        .refreshable { await load() }
    }

    private func load() async {
        do {
            entries = try await SourceFiles.list(route.source, route.path)
            errorMessage = nil
        } catch is CancellationError {
        } catch {
            errorMessage = error.localizedDescription
        }
    }
}

// MARK: - Adding a network source

struct AddSourceSheet: View {
    let initialKind: MediaSourceKind
    let onAdd: (MediaSource) throws -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var kind: MediaSourceKind = .http
    @State private var name = ""
    @State private var address = ""
    @State private var username = ""
    @State private var password = ""
    @State private var hostKey = ""
    @State private var errorMessage: String?

    private static let networkKinds: [MediaSourceKind] = [.http, .webdav, .smb, .sftp]

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Picker("Tipo", selection: $kind) {
                        ForEach(Self.networkKinds) { kind in
                            Label(kind.title, systemImage: kind.systemImage).tag(kind)
                        }
                    }
                }
                Section {
                    TextField("Nombre", text: $name)
                    TextField("Dirección", text: $address, prompt: Text(verbatim: kind.addressPlaceholder))
                        .keyboardType(.URL)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                } footer: {
                    Text(addressHelp)
                }
                Section("Acceso") {
                    TextField(kind == .sftp ? LocalizedStringKey("Usuario") : LocalizedStringKey("Usuario (opcional)"), text: $username)
                        .textContentType(.username)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    SecureField("Contraseña", text: $password)
                        .textContentType(.password)
                }
                if kind == .sftp {
                    Section {
                        TextField("ssh-ed25519 AAAA…", text: $hostKey, axis: .vertical)
                            .font(.footnote.monospaced())
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                    } header: {
                        Text("Clave pública del servidor SSH")
                    } footer: {
                        Text("Sólo se conecta si el servidor presenta esta clave. En el servidor: cat /etc/ssh/ssh_host_ed25519_key.pub")
                    }
                }
                if let errorMessage {
                    Section {
                        Text(errorMessage)
                            .foregroundStyle(.red)
                    }
                }
            }
            .navigationTitle("Añadir fuente")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancelar") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Añadir", action: add)
                }
            }
            .onAppear {
                if Self.networkKinds.contains(initialKind) { kind = initialKind }
            }
        }
    }

    private var addressHelp: String {
        switch kind {
        case .http: String(localized: "Un vídeo MP4 o MOV, o una lista HLS (.m3u8).")
        case .webdav: String(localized: "La carpeta del servidor WebDAV (Nextcloud, NAS…).")
        case .smb: String(localized: "El recurso compartido y, si quieres, una carpeta dentro.")
        case .sftp: String(localized: "La carpeta del servidor SSH.")
        case .local, .folder: ""
        }
    }

    private func add() {
        let trimmedAddress = address.trimmingCharacters(in: .whitespacesAndNewlines)
        var trimmedName = name.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmedName.isEmpty { trimmedName = URL(string: trimmedAddress)?.host ?? "" }
        let source = MediaSource(
            name: trimmedName,
            kind: kind,
            address: trimmedAddress,
            username: username.trimmingCharacters(in: .whitespacesAndNewlines),
            password: password,
            hostKey: kind == .sftp ? hostKey.trimmingCharacters(in: .whitespacesAndNewlines) : ""
        )
        do {
            try SourceFiles.validate(source)
            try onAdd(source)
            dismiss()
        } catch {
            errorMessage = error.localizedDescription
        }
    }
}

// MARK: - Player

struct SourcePlayerView: View {
    let playback: SourcePlayback
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        ZStack(alignment: .topLeading) {
            Color.black.ignoresSafeArea()
            SourcePlayerController(player: playback.player)
                .ignoresSafeArea()
            if let failure = playback.failure {
                ContentUnavailableView {
                    Label("No se pudo reproducir", systemImage: "exclamationmark.triangle")
                } description: {
                    Text(failure)
                }
                .foregroundStyle(.white)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
            Button {
                dismiss()
            } label: {
                Image(systemName: "xmark")
                    .font(.headline)
                    .padding(6)
            }
            .buttonStyle(.glass)
            .accessibilityLabel("Cerrar")
            .padding()
        }
        .onAppear {
            AudioSession.setActive(true)
            TVVolumeRelay.shared.localPlayback = true
            playback.player.play()
        }
        .onDisappear {
            playback.close()
            TVVolumeRelay.shared.localPlayback = false
            AudioSession.setActive(false)
        }
    }
}

private struct SourcePlayerController: UIViewControllerRepresentable {
    let player: AVPlayer

    func makeUIViewController(context: Context) -> AVPlayerViewController {
        let controller = AVPlayerViewController()
        controller.player = player
        controller.allowsPictureInPicturePlayback = true
        controller.canStartPictureInPictureAutomaticallyFromInline = true
        controller.updatesNowPlayingInfoCenter = true
        return controller
    }

    func updateUIViewController(_ controller: AVPlayerViewController, context: Context) {
        if controller.player !== player { controller.player = player }
    }
}
