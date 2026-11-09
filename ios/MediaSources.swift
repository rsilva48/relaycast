import AVFoundation
import Foundation
import Observation
import Security
import UniformTypeIdentifiers
import SMBClient
import Citadel
import NIOSSH

// MARK: - Model

/// Where a source's videos come from. Everything plays on this device; the
/// Relay Server is not involved (it has its own row in Fuentes).
enum MediaSourceKind: String, Codable, CaseIterable, Identifiable {
    case local, folder, http, webdav, smb, sftp

    var id: String { rawValue }

    var title: String {
        switch self {
        case .local: String(localized: "Archivo del dispositivo")
        case .folder: String(localized: "Carpeta del dispositivo")
        case .http: String(localized: "Enlace de vídeo (HTTP/HLS)")
        case .webdav: String(localized: "WebDAV")
        case .smb: String(localized: "Carpeta compartida (SMB)")
        case .sftp: String(localized: "SFTP")
        }
    }

    var systemImage: String {
        switch self {
        case .local: "doc"
        case .folder: "folder"
        case .http: "link"
        case .webdav: "globe"
        case .smb: "externaldrive.connected.to.line.below"
        case .sftp: "lock.rectangle.stack"
        }
    }

    /// Picked with the system's Files picker instead of a form.
    var usesFilePicker: Bool { self == .local || self == .folder }
    /// Opens a folder to browse; the others play right away.
    var isBrowsable: Bool { self == .folder || self == .webdav || self == .smb || self == .sftp }

    var addressPlaceholder: String {
        switch self {
        case .http: "https://servidor/video.mp4"
        case .webdav: "https://servidor/dav/videos"
        case .smb: "smb://servidor/recurso/carpeta"
        case .sftp: "sftp://servidor/home/usuario/videos"
        case .local, .folder: ""
        }
    }
}

struct MediaSource: Codable, Identifiable, Hashable {
    var id = UUID()
    var name: String
    var kind: MediaSourceKind
    var address = ""
    var username = ""
    var password = ""
    /// SFTP: the server's public key in OpenSSH format, pinned when added.
    var hostKey = ""
    /// Device file or folder: a bookmark to reopen it after a relaunch.
    var bookmark: Data?

    var hasCredentials: Bool { !username.isEmpty }

    /// The host for network sources, or the kind for device files.
    var detail: String {
        if let host = URL(string: address)?.host, !host.isEmpty { return "\(kind.title) · \(host)" }
        return kind.title
    }
}

struct SourceEntry: Identifiable, Hashable {
    var name: String
    /// Relative to the source's root, with "/" separators.
    var path: String
    var directory: Bool
    var id: String { path }
}

struct SourceFailure: LocalizedError {
    let message: String
    var errorDescription: String? { message }
}

// MARK: - Storage

/// The user's sources, credentials included, in the Keychain (this device
/// only: never in iCloud Keychain or backups).
@MainActor
@Observable
final class MediaSourceStore {
    private(set) var sources: [MediaSource] = []
    private(set) var loadError: String?

    private let query: [String: Any] = [
        kSecClass as String: kSecClassGenericPassword,
        kSecAttrService as String: "net.silvara.relaycast.sources",
        kSecAttrAccount as String: "sources",
    ]

    init() {
        var lookup = query
        lookup[kSecReturnData as String] = true
        var result: CFTypeRef?
        let status = SecItemCopyMatching(lookup as CFDictionary, &result)
        if status == errSecSuccess, let data = result as? Data {
            do { sources = try JSONDecoder().decode([MediaSource].self, from: data) }
            catch { loadError = String(localized: "No se pudieron leer las fuentes guardadas.") }
        } else if status != errSecItemNotFound {
            loadError = String(localized: "No se pudieron abrir las fuentes del Llavero.")
        }
    }

    func add(_ source: MediaSource) throws { try save(sources + [source]) }

    func remove(_ ids: Set<UUID>) throws {
        let removed = sources.filter { ids.contains($0.id) }
        try save(sources.filter { !ids.contains($0.id) })
        removed.forEach(SourcePositions.forget)
    }

    func update(_ source: MediaSource) throws {
        try save(sources.map { $0.id == source.id ? source : $0 })
    }

    private func save(_ values: [MediaSource]) throws {
        // A Keychain item that could not be read would be overwritten.
        if let loadError { throw SourceFailure(message: loadError) }
        let data = try JSONEncoder().encode(values)
        let status = SecItemUpdate(query as CFDictionary, [kSecValueData as String: data] as CFDictionary)
        if status == errSecItemNotFound {
            var add = query
            add[kSecValueData as String] = data
            add[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
            guard SecItemAdd(add as CFDictionary, nil) == errSecSuccess else {
                throw SourceFailure(message: String(localized: "No se pudieron guardar las fuentes en el Llavero."))
            }
        } else if status != errSecSuccess {
            throw SourceFailure(message: String(localized: "No se pudieron guardar las fuentes en el Llavero."))
        }
        sources = values
    }
}

/// Where each video of a source was left, to resume it.
enum SourcePositions {
    private static let prefix = "relaycast.source-position."

    private static func key(_ source: MediaSource, _ path: String) -> String {
        prefix + source.id.uuidString + "." + path
    }

    static func position(_ source: MediaSource, _ path: String) -> Double {
        UserDefaults.standard.double(forKey: key(source, path))
    }

    static func save(_ seconds: Double, duration: Double, source: MediaSource, path: String) {
        // Finished (or barely started): it starts from the beginning next time.
        if seconds < 5 || (duration > 0 && (duration - seconds < 30 || seconds / duration > 0.95)) {
            UserDefaults.standard.removeObject(forKey: key(source, path))
        } else {
            UserDefaults.standard.set(seconds, forKey: key(source, path))
        }
    }

    static func forget(_ source: MediaSource) {
        let start = prefix + source.id.uuidString + "."
        for name in UserDefaults.standard.dictionaryRepresentation().keys where name.hasPrefix(start) {
            UserDefaults.standard.removeObject(forKey: name)
        }
    }
}

// MARK: - Browsing

enum SourceFiles {
    /// What AVPlayer plays. MKV, AVI or WebM need the Relay Server, which
    /// converts them to HLS (with their subtitles).
    private static let playable: Set<String> = ["mp4", "m4v", "mov", "mp3", "m4a", "aac", "wav"]

    static func isPlayable(_ name: String) -> Bool {
        playable.contains((name as NSString).pathExtension.lowercased())
    }

    static func validate(_ source: MediaSource) throws {
        guard !source.name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            throw SourceFailure(message: String(localized: "Escribe un nombre para la fuente."))
        }
        if source.kind.usesFilePicker {
            guard source.bookmark != nil else { throw SourceFailure(message: String(localized: "Elige un archivo o una carpeta.")) }
            return
        }
        guard let url = URL(string: source.address), let host = url.host, !host.isEmpty,
              url.user == nil, url.password == nil, url.fragment == nil else {
            throw SourceFailure(message: String(localized: "Escribe una dirección válida; el usuario y la contraseña van en sus propios campos."))
        }
        let schemes: [String]
        switch source.kind {
        case .smb: schemes = ["smb"]
        case .sftp: schemes = ["sftp"]
        default: schemes = ["http", "https"]
        }
        guard schemes.contains(url.scheme?.lowercased() ?? "") else {
            let prefixes = schemes.map { $0 + "://" }.joined(separator: " o ")
            throw SourceFailure(message: String(localized: "La dirección debe empezar por \(prefixes)."))
        }
        if source.kind == .smb, shareParts(source).share.isEmpty {
            throw SourceFailure(message: String(localized: "Incluye el recurso compartido: smb://servidor/recurso."))
        }
        if source.kind == .sftp {
            guard (try? NIOSSHPublicKey(openSSHPublicKey: source.hostKey.trimmingCharacters(in: .whitespacesAndNewlines))) != nil else {
                throw SourceFailure(message: String(localized: "Pega la clave pública del servidor SSH (por ejemplo, la línea de /etc/ssh/ssh_host_ed25519_key.pub)."))
            }
            guard !source.username.isEmpty else { throw SourceFailure(message: String(localized: "SFTP necesita un usuario.")) }
        }
        if source.kind == .http, !isPlayable(url.lastPathComponent), url.pathExtension.lowercased() != "m3u8" {
            throw SourceFailure(message: String(localized: "El enlace debe llevar a un vídeo MP4 o MOV, o a una lista HLS (.m3u8)."))
        }
    }

    /// Refuses absolute paths and "." / ".." so a listing never leaves the source.
    static func checked(_ relative: String) throws -> String {
        let parts = relative.split(separator: "/", omittingEmptySubsequences: true)
        guard !relative.hasPrefix("/"), !parts.contains(where: { $0 == "." || $0 == ".." }) else {
            throw SourceFailure(message: String(localized: "Ruta no válida."))
        }
        return parts.joined(separator: "/")
    }

    /// The address's path components (percent-decoded).
    private static func rootComponents(_ source: MediaSource) -> [String] {
        (URL(string: source.address)?.path ?? "").split(separator: "/").map(String.init)
    }

    /// SMB: the share and the folder inside it.
    static func shareParts(_ source: MediaSource) -> (share: String, folder: String) {
        let parts = rootComponents(source)
        return (parts.first ?? "", parts.dropFirst().joined(separator: "/"))
    }

    static func smbPath(_ source: MediaSource, _ relative: String) throws -> String {
        let folder = shareParts(source).folder
        let inner = try checked(relative)
        return [folder, inner].filter { !$0.isEmpty }.joined(separator: "/")
    }

    static func sftpPath(_ source: MediaSource, _ relative: String) throws -> String {
        let inner = try checked(relative).split(separator: "/").map(String.init)
        return "/" + (rootComponents(source) + inner).joined(separator: "/")
    }

    static func webURL(_ source: MediaSource, _ relative: String) throws -> URL {
        guard let root = URL(string: source.address) else { throw SourceFailure(message: String(localized: "Ruta no válida.")) }
        return try checked(relative).split(separator: "/").reduce(root) { $0.appendingPathComponent(String($1)) }
    }

    static func basicAuthorization(_ source: MediaSource) -> String? {
        guard source.hasCredentials else { return nil }
        return "Basic " + Data("\(source.username):\(source.password)".utf8).base64EncodedString()
    }

    static func resolveBookmark(_ source: MediaSource) throws -> URL {
        guard let bookmark = source.bookmark else { throw SourceFailure(message: String(localized: "Vuelve a elegir el archivo o la carpeta.")) }
        var stale = false
        do {
            return try URL(resolvingBookmarkData: bookmark, options: [], relativeTo: nil, bookmarkDataIsStale: &stale)
        } catch {
            throw SourceFailure(message: String(localized: "El archivo o la carpeta ya no está disponible. Quita la fuente y vuelve a añadirla."))
        }
    }

    static func list(_ source: MediaSource, _ relative: String) async throws -> [SourceEntry] {
        let relative = try checked(relative)
        func child(_ name: String) -> String { relative.isEmpty ? name : relative + "/" + name }
        var entries: [SourceEntry]
        switch source.kind {
        case .folder:
            let root = try resolveBookmark(source)
            let access = root.startAccessingSecurityScopedResource()
            defer { if access { root.stopAccessingSecurityScopedResource() } }
            let folder = relative.isEmpty ? root : root.appendingPathComponent(relative, isDirectory: true)
            let urls = try FileManager.default.contentsOfDirectory(at: folder, includingPropertiesForKeys: [.isDirectoryKey], options: [.skipsHiddenFiles])
            entries = urls.map { url in
                let directory = (try? url.resourceValues(forKeys: [.isDirectoryKey]))?.isDirectory ?? false
                return SourceEntry(name: url.lastPathComponent, path: child(url.lastPathComponent), directory: directory)
            }
        case .smb:
            let client = try await SMBRangeReader.connect(source)
            do {
                let files = try await client.listDirectory(path: smbPath(source, relative))
                entries = files.filter { !$0.isHidden }.map { SourceEntry(name: $0.name, path: child($0.name), directory: $0.isDirectory) }
            } catch {
                await SMBRangeReader.disconnect(client)
                throw error
            }
            await SMBRangeReader.disconnect(client)
        case .sftp:
            let client = try await SFTPRangeReader.connect(source)
            do {
                let sftp = try await client.openSFTP()
                do {
                    let names = try await sftp.listDirectory(atPath: sftpPath(source, relative))
                    entries = names.flatMap(\.components).map { item in
                        let directory: Bool
                        if let permissions = item.attributes.permissions {
                            directory = permissions & 0o170000 == 0o040000
                        } else {
                            directory = item.longname.hasPrefix("d")
                        }
                        return SourceEntry(name: item.filename, path: child(item.filename), directory: directory)
                    }
                } catch {
                    try? await sftp.close()
                    throw error
                }
                try? await sftp.close()
            } catch {
                try? await client.close()
                throw error
            }
            try? await client.close()
        case .webdav:
            entries = try await WebDAV.list(source, relative)
        case .local, .http:
            entries = []
        }
        return entries
            .filter { ![".", ".."].contains($0.name) && !$0.name.hasPrefix(".") && ($0.directory || isPlayable($0.name)) }
            .sorted { $0.directory != $1.directory ? $0.directory : $0.name.localizedStandardCompare($1.name) == .orderedAscending }
    }
}

// MARK: - HTTP and WebDAV

/// Never forwards a source's credentials to another address.
final class SourceHTTPDelegate: NSObject, URLSessionTaskDelegate, @unchecked Sendable {
    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse, newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) {
        completionHandler(nil)
    }
}

enum WebDAV {
    static func list(_ source: MediaSource, _ relative: String) async throws -> [SourceEntry] {
        let folder = try SourceFiles.webURL(source, relative).appendingPathComponent("", isDirectory: true)
        var request = URLRequest(url: folder)
        request.timeoutInterval = 30
        request.httpMethod = "PROPFIND"
        request.setValue("1", forHTTPHeaderField: "Depth")
        request.setValue("application/xml; charset=utf-8", forHTTPHeaderField: "Content-Type")
        if let authorization = SourceFiles.basicAuthorization(source) { request.setValue(authorization, forHTTPHeaderField: "Authorization") }
        request.httpBody = Data(#"<?xml version="1.0" encoding="utf-8"?><d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/></d:prop></d:propfind>"#.utf8)
        let session = URLSession(configuration: .ephemeral, delegate: SourceHTTPDelegate(), delegateQueue: nil)
        defer { session.invalidateAndCancel() }
        let (data, response) = try await session.data(for: request)
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        if status == 401 { throw SourceFailure(message: String(localized: "El servidor rechazó el usuario o la contraseña.")) }
        guard status == 207 else { throw SourceFailure(message: String(localized: "El servidor WebDAV respondió HTTP \(status).")) }
        let parser = DAVResponses()
        let xml = XMLParser(data: data)
        xml.shouldProcessNamespaces = true
        xml.shouldResolveExternalEntities = false
        xml.delegate = parser
        guard xml.parse() else { throw SourceFailure(message: String(localized: "El servidor WebDAV devolvió una respuesta que la app no pudo leer.")) }

        let base = URL(string: source.address)
        let current = folder.path.split(separator: "/").map(String.init)
        return parser.items.compactMap { item in
            // Only children of this folder, on the same server.
            guard let url = URL(string: item.href, relativeTo: folder)?.absoluteURL,
                  url.host?.lowercased() == base?.host?.lowercased(), url.port == base?.port else { return nil }
            let parts = url.path.split(separator: "/").map(String.init)
            guard parts.count == current.count + 1, Array(parts.prefix(current.count)) == current, let name = parts.last else { return nil }
            return SourceEntry(name: name, path: relative.isEmpty ? name : relative + "/" + name, directory: item.directory)
        }
    }
}

private final class DAVResponses: NSObject, XMLParserDelegate {
    struct Item { var href: String; var directory: Bool }
    var items: [Item] = []
    private var href = ""
    private var directory = false
    private var text = ""

    func parser(_ parser: XMLParser, didStartElement elementName: String, namespaceURI: String?, qualifiedName qName: String?, attributes attributeDict: [String: String] = [:]) {
        text = ""
        if elementName == "response" { href = ""; directory = false }
        if elementName == "collection" { directory = true }
    }

    func parser(_ parser: XMLParser, foundCharacters string: String) { text += string }

    func parser(_ parser: XMLParser, didEndElement elementName: String, namespaceURI: String?, qualifiedName qName: String?) {
        if elementName == "href" { href = text.trimmingCharacters(in: .whitespacesAndNewlines) }
        if elementName == "response", !href.isEmpty { items.append(Item(href: href, directory: directory)) }
    }
}

// MARK: - Byte ranges for streaming

/// Reads a remote file by byte ranges, so it plays while it streams instead
/// of being downloaded first. One connection per playing video.
protocol RangeReader: AnyObject {
    /// Connects and returns the file's size in bytes.
    func open() async throws -> UInt64
    /// Up to `length` bytes from `offset`; fewer only at the end of the file.
    func read(offset: UInt64, length: Int) async throws -> Data
    func close() async
}

final class HTTPRangeReader: RangeReader {
    private let url: URL
    private let authorization: String?
    private let session = URLSession(configuration: .ephemeral, delegate: SourceHTTPDelegate(), delegateQueue: nil)

    init(url: URL, authorization: String?) {
        self.url = url
        self.authorization = authorization
    }

    private func request(_ range: String) -> URLRequest {
        var request = URLRequest(url: url)
        request.timeoutInterval = 45
        request.setValue("bytes=\(range)", forHTTPHeaderField: "Range")
        if let authorization { request.setValue(authorization, forHTTPHeaderField: "Authorization") }
        return request
    }

    func open() async throws -> UInt64 {
        let (_, response) = try await session.data(for: request("0-0"))
        guard let http = response as? HTTPURLResponse else { throw SourceFailure(message: String(localized: "El servidor no respondió correctamente.")) }
        if http.statusCode == 401 { throw SourceFailure(message: String(localized: "El servidor rechazó el usuario o la contraseña.")) }
        // "bytes 0-0/123456"
        guard http.statusCode == 206, let range = http.value(forHTTPHeaderField: "Content-Range"),
              let total = range.split(separator: "/").last.flatMap({ UInt64($0) }) else {
            throw SourceFailure(message: String(localized: "El servidor no permite reproducir por partes (HTTP \(http.statusCode)). Usa un enlace sin contraseña o el Relay Server."))
        }
        return total
    }

    func read(offset: UInt64, length: Int) async throws -> Data {
        let (data, response) = try await session.data(for: request("\(offset)-\(offset + UInt64(length) - 1)"))
        guard (response as? HTTPURLResponse)?.statusCode == 206 else {
            throw SourceFailure(message: String(localized: "El servidor dejó de responder."))
        }
        return data
    }

    func close() async { session.invalidateAndCancel() }
}

final class SMBRangeReader: RangeReader {
    private let source: MediaSource
    private let path: String
    private var client: SMBClient?
    private var reader: FileReader?

    init(source: MediaSource, path: String) throws {
        self.source = source
        self.path = try SourceFiles.smbPath(source, path)
    }

    static func connect(_ source: MediaSource) async throws -> SMBClient {
        guard let url = URL(string: source.address), let host = url.host else { throw SourceFailure(message: String(localized: "Ruta no válida.")) }
        let client = SMBClient(host: host, port: url.port ?? 445)
        do {
            try await client.login(username: source.username.isEmpty ? "guest" : source.username, password: source.password)
            _ = try await client.connectShare(SourceFiles.shareParts(source).share)
            return client
        } catch {
            _ = try? await client.logoff()
            throw error
        }
    }

    static func disconnect(_ client: SMBClient) async {
        _ = try? await client.disconnectShare()
        _ = try? await client.logoff()
    }

    func open() async throws -> UInt64 {
        let client = try await Self.connect(source)
        self.client = client
        let reader = client.fileReader(path: path)
        self.reader = reader
        return try await reader.fileSize
    }

    func read(offset: UInt64, length: Int) async throws -> Data {
        guard let reader else { throw CancellationError() }
        return try await reader.read(offset: offset, length: UInt32(length))
    }

    func close() async {
        try? await reader?.close()
        if let client { await Self.disconnect(client) }
        reader = nil
        client = nil
    }
}

final class SFTPRangeReader: RangeReader {
    private let source: MediaSource
    private let path: String
    private var client: SSHClient?
    private var sftp: SFTPClient?
    private var file: SFTPFile?

    init(source: MediaSource, path: String) throws {
        self.source = source
        self.path = try SourceFiles.sftpPath(source, path)
    }

    /// Only the pinned host key is accepted: never trust on first use.
    static func connect(_ source: MediaSource) async throws -> SSHClient {
        guard let url = URL(string: source.address), let host = url.host else { throw SourceFailure(message: String(localized: "Ruta no válida.")) }
        let key = try NIOSSHPublicKey(openSSHPublicKey: source.hostKey.trimmingCharacters(in: .whitespacesAndNewlines))
        return try await SSHClient.connect(
            host: host,
            port: url.port ?? 22,
            authenticationMethod: .passwordBased(username: source.username, password: source.password),
            hostKeyValidator: .trustedKeys([key]),
            reconnect: .never
        )
    }

    func open() async throws -> UInt64 {
        let client = try await Self.connect(source)
        self.client = client
        let sftp = try await client.openSFTP()
        self.sftp = sftp
        let file = try await sftp.openFile(filePath: path, flags: .read)
        self.file = file
        guard let size = try await file.readAttributes().size else {
            throw SourceFailure(message: String(localized: "El servidor SFTP no informó el tamaño del archivo."))
        }
        return size
    }

    func read(offset: UInt64, length: Int) async throws -> Data {
        guard let file else { throw CancellationError() }
        // A server answers at most its packet size per read.
        var data = Data()
        while data.count < length {
            try Task.checkCancellation()
            let chunk = try await file.read(from: offset + UInt64(data.count), length: UInt32(length - data.count))
            if chunk.readableBytes == 0 { break }
            data.append(contentsOf: chunk.readableBytesView)
        }
        return data
    }

    func close() async {
        try? await file?.close()
        try? await sftp?.close()
        try? await client?.close()
        file = nil
        sftp = nil
        client = nil
    }
}

/// One read at a time on a connection: AVPlayer asks for several ranges at once.
actor ReadGate {
    private var locked = false
    private var waiters: [CheckedContinuation<Void, Never>] = []

    func lock() async {
        guard locked else {
            locked = true
            return
        }
        await withCheckedContinuation { waiters.append($0) }
    }

    func unlock() {
        if waiters.isEmpty { locked = false } else { waiters.removeFirst().resume() }
    }
}

/// Serves a `RangeReader` to AVPlayer through a custom URL scheme
/// (AVAssetResourceLoader), answering the byte ranges it asks for.
final class SourceStreamLoader: NSObject, AVAssetResourceLoaderDelegate, @unchecked Sendable {
    static let scheme = "relaycast-source"
    private static let chunk = 512 * 1024

    let queue = DispatchQueue(label: "net.silvara.relaycast.source-loader")
    private let reader: RangeReader
    private let contentType: String
    private let gate = ReadGate()
    private var length: UInt64?
    private var tasks: [ObjectIdentifier: Task<Void, Never>] = [:]
    private var closed = false

    init(reader: RangeReader, fileExtension: String) {
        self.reader = reader
        contentType = UTType(filenameExtension: fileExtension.lowercased())?.identifier ?? UTType.mpeg4Movie.identifier
    }

    /// The URL to give AVURLAsset (the name only helps AVPlayer guess the type).
    static func url(fileName: String) -> URL {
        var components = URLComponents()
        components.scheme = scheme
        components.host = "item"
        components.path = "/" + fileName
        return components.url ?? URL(string: "\(scheme)://item/video.mp4")!
    }

    // Called on `queue`.
    func resourceLoader(_ resourceLoader: AVAssetResourceLoader, shouldWaitForLoadingOfRequestedResource loadingRequest: AVAssetResourceLoadingRequest) -> Bool {
        guard !closed else { return false }
        let key = ObjectIdentifier(loadingRequest)
        tasks[key] = Task { [weak self] in
            await self?.serve(loadingRequest)
            self?.queue.async { self?.tasks[key] = nil }
        }
        return true
    }

    func resourceLoader(_ resourceLoader: AVAssetResourceLoader, didCancel loadingRequest: AVAssetResourceLoadingRequest) {
        tasks.removeValue(forKey: ObjectIdentifier(loadingRequest))?.cancel()
    }

    /// Stops every request and closes the connection.
    func close() {
        queue.async { [self] in
            closed = true
            tasks.values.forEach { $0.cancel() }
            tasks.removeAll()
            let reader = reader
            let gate = gate
            Task {
                await gate.lock()
                await reader.close()
                await gate.unlock()
            }
        }
    }

    private func contentLength() async throws -> UInt64 {
        await gate.lock()
        do {
            if let length {
                await gate.unlock()
                return length
            }
            let value = try await reader.open()
            length = value
            await gate.unlock()
            return value
        } catch {
            await gate.unlock()
            throw error
        }
    }

    private func serve(_ request: AVAssetResourceLoadingRequest) async {
        do {
            let total = try await contentLength()
            if let information = request.contentInformationRequest {
                queue.sync {
                    information.contentType = contentType
                    information.contentLength = Int64(total)
                    information.isByteRangeAccessSupported = true
                }
            }
            if let dataRequest = request.dataRequest {
                var offset = UInt64(max(0, dataRequest.currentOffset != 0 ? dataRequest.currentOffset : dataRequest.requestedOffset))
                let end = dataRequest.requestsAllDataToEndOfResource
                    ? total
                    : min(total, UInt64(max(0, dataRequest.requestedOffset)) + UInt64(max(0, dataRequest.requestedLength)))
                while offset < end {
                    try Task.checkCancellation()
                    let count = Int(min(UInt64(Self.chunk), end - offset))
                    await gate.lock()
                    let bytes: Data
                    do {
                        try Task.checkCancellation()
                        bytes = try await reader.read(offset: offset, length: count)
                    } catch {
                        await gate.unlock()
                        throw error
                    }
                    await gate.unlock()
                    if bytes.isEmpty { break }
                    queue.sync { dataRequest.respond(with: bytes) }
                    offset += UInt64(bytes.count)
                }
            }
            queue.sync { if !request.isCancelled { request.finishLoading() } }
        } catch is CancellationError {
            // AVPlayer cancelled it (seek, enough buffered) or the player closed.
        } catch {
            queue.sync { if !request.isCancelled { request.finishLoading(with: error) } }
        }
    }
}

// MARK: - Playback

/// A video from a source, ready for AVPlayer, and what to release when it closes.
@MainActor
@Observable
final class SourcePlayback: Identifiable {
    let id = UUID()
    let title: String
    let player: AVPlayer
    /// Why the video could not play (shown over the player).
    private(set) var failure: String?
    private let source: MediaSource
    private let path: String
    private let loader: SourceStreamLoader?
    private let scopedURL: URL?
    @ObservationIgnored private var timeObserver: Any?
    @ObservationIgnored private var statusObservation: NSKeyValueObservation?
    @ObservationIgnored private var closed = false

    private init(title: String, player: AVPlayer, source: MediaSource, path: String, loader: SourceStreamLoader?, scopedURL: URL?) {
        self.title = title
        self.player = player
        self.source = source
        self.path = path
        self.loader = loader
        self.scopedURL = scopedURL
    }

    static func make(source: MediaSource, path: String, title: String) throws -> SourcePlayback {
        let item: AVPlayerItem
        var loader: SourceStreamLoader?
        var scopedURL: URL?
        switch source.kind {
        case .local, .folder:
            let root = try SourceFiles.resolveBookmark(source)
            if root.startAccessingSecurityScopedResource() { scopedURL = root }
            var file = root
            if source.kind == .folder {
                let inner = try SourceFiles.checked(path)
                file = root.appendingPathComponent(inner)
            }
            item = AVPlayerItem(url: file)
        case .http where !source.hasCredentials:
            guard let url = URL(string: source.address) else { throw SourceFailure(message: String(localized: "Ruta no válida.")) }
            item = AVPlayerItem(url: url)
        default:
            let reader: RangeReader
            let name: String
            switch source.kind {
            case .smb:
                reader = try SMBRangeReader(source: source, path: path)
                name = (path as NSString).lastPathComponent
            case .sftp:
                reader = try SFTPRangeReader(source: source, path: path)
                name = (path as NSString).lastPathComponent
            default:
                let address: URL?
                if source.kind == .http {
                    address = URL(string: source.address)
                } else {
                    address = try SourceFiles.webURL(source, path)
                }
                guard let url = address else { throw SourceFailure(message: String(localized: "Ruta no válida.")) }
                if url.pathExtension.lowercased() == "m3u8" {
                    throw SourceFailure(message: String(localized: "Las listas HLS con contraseña no se pueden reproducir; usa un enlace sin contraseña."))
                }
                reader = HTTPRangeReader(url: url, authorization: SourceFiles.basicAuthorization(source))
                name = url.lastPathComponent
            }
            let streamLoader = SourceStreamLoader(reader: reader, fileExtension: (name as NSString).pathExtension)
            let asset = AVURLAsset(url: SourceStreamLoader.url(fileName: name.isEmpty ? "video.mp4" : name))
            asset.resourceLoader.setDelegate(streamLoader, queue: streamLoader.queue)
            item = AVPlayerItem(asset: asset)
            loader = streamLoader
        }
        let player = AVPlayer(playerItem: item)
        player.allowsExternalPlayback = true
        let playback = SourcePlayback(title: title, player: player, source: source, path: path, loader: loader, scopedURL: scopedURL)
        playback.startTracking()
        return playback
    }

    private func startTracking() {
        let resume = SourcePositions.position(source, path)
        if resume > 0 { player.seek(to: CMTime(seconds: resume, preferredTimescale: 600)) }
        timeObserver = player.addPeriodicTimeObserver(forInterval: CMTime(seconds: 5, preferredTimescale: 1), queue: .main) { [weak self] _ in
            MainActor.assumeIsolated { self?.savePosition() }
        }
        statusObservation = player.currentItem?.observe(\.status, options: [.new]) { [weak self] item, _ in
            guard item.status == .failed else { return }
            // The source's own message (e.g. a rejected password) when there is one.
            let underlying = (item.error as NSError?)?.userInfo[NSUnderlyingErrorKey] as? Error
            let message = (underlying ?? item.error)?.localizedDescription ?? String(localized: "El vídeo no se pudo reproducir.")
            Task { @MainActor in self?.failure = message }
        }
    }

    private func savePosition() {
        let seconds = player.currentTime().seconds
        let duration = player.currentItem?.duration.seconds ?? 0
        guard seconds.isFinite else { return }
        SourcePositions.save(seconds, duration: duration.isFinite ? duration : 0, source: source, path: path)
    }

    func close() {
        guard !closed else { return }
        closed = true
        savePosition()
        if let timeObserver { player.removeTimeObserver(timeObserver) }
        timeObserver = nil
        statusObservation?.invalidate()
        statusObservation = nil
        player.pause()
        player.replaceCurrentItem(with: nil)
        loader?.close()
        scopedURL?.stopAccessingSecurityScopedResource()
    }
}
