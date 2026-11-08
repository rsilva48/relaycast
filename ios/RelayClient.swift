import CryptoKit
import Foundation
import Network
import Security

/// Whether the device has a usable network path. iOS reports a missing
/// connection and a denied local network permission with the same error, so
/// messages check this to tell them apart.
final class NetworkStatus: @unchecked Sendable {
    static let shared = NetworkStatus()

    private let monitor = NWPathMonitor()
    private let lock = NSLock()
    private var satisfied = true

    private init() {
        monitor.pathUpdateHandler = { [weak self] path in
            self?.update(path.status == .satisfied)
        }
        monitor.start(queue: DispatchQueue(label: "relaycast.network-status"))
    }

    var isOnline: Bool {
        lock.lock()
        defer { lock.unlock() }
        return satisfied
    }

    private func update(_ value: Bool) {
        lock.lock()
        satisfied = value
        lock.unlock()
    }
}

enum OfflineMessage {
    static var general: String { String(localized: "Sin conexión a internet. Los episodios descargados siguen disponibles en Descargas.") }
}

enum RelayScheme: String, CaseIterable, Identifiable {
    case https
    case http

    var id: String { rawValue }
}

enum RelayEndpoint {
    static func canonicalURL(_ value: String) -> String? {
        let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
        guard var components = URLComponents(string: trimmed),
              let scheme = components.scheme?.lowercased(),
              scheme == "http" || scheme == "https",
              let host = components.host?.lowercased(), !host.isEmpty else {
            return nil
        }
        components.scheme = scheme
        components.host = host
        components.user = nil
        components.password = nil
        components.path = ""
        components.query = nil
        components.fragment = nil
        if (scheme == "http" && components.port == 80) || (scheme == "https" && components.port == 443) {
            components.port = nil
        }
        return components.url?.absoluteString
    }

    /// Removes a pasted `http://` or `https://` prefix from the host field and
    /// reports which scheme it was, so the picker can follow it.
    static func strippingScheme(_ value: String) -> (host: String, scheme: RelayScheme?) {
        let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
        for scheme in RelayScheme.allCases {
            let prefix = "\(scheme.rawValue)://"
            if trimmed.lowercased().hasPrefix(prefix) {
                return (String(trimmed.dropFirst(prefix.count)), scheme)
            }
        }
        return (value, nil)
    }

    /// Builds the canonical relay URL from the scheme picker and a
    /// `host[:port]` field. Paths, queries and pasted schemes are ignored.
    static func make(scheme: RelayScheme, host: String) -> String? {
        var value = strippingScheme(host).host.trimmingCharacters(in: .whitespacesAndNewlines)
        if let end = value.firstIndex(where: { $0 == "/" || $0 == "?" || $0 == "#" }) {
            value = String(value[..<end])
        }
        guard !value.isEmpty, !value.contains(where: \.isWhitespace) else { return nil }
        return canonicalURL("\(scheme.rawValue)://\(value)")
    }

    /// Splits a canonical URL back into the picker and host field values.
    static func fields(from url: String) -> (scheme: RelayScheme, host: String) {
        let stripped = strippingScheme(url)
        return (stripped.scheme ?? .https, stripped.host.trimmingCharacters(in: CharacterSet(charactersIn: "/")))
    }

    /// The relay's catalog API (latest, filters, series, episode playback
    /// and release status). Relaycast (App Store) never calls it.
    static func isCatalogPath(_ path: String) -> Bool {
        let catalog = ["/api/latest", "/api/catalog", "/api/series", "/api/play"]
        return catalog.contains { path == $0 || path.hasPrefix($0 + "/") }
    }

    static func tokenAccount(for relayBaseURL: String) -> String? {
        guard let endpoint = canonicalURL(relayBaseURL) else { return nil }
        let digest = SHA256.hash(data: Data(endpoint.utf8)).map { String(format: "%02x", $0) }.joined()
        return "relay-token.\(digest)"
    }
}

struct RelayRequestError: LocalizedError {
    let message: String
    var statusCode: Int? = nil
    /// The device has no network connection.
    var isOffline = false
    var errorDescription: String? { message }
    var isExpiredSession: Bool { statusCode == 410 }
    var isUnauthorized: Bool { statusCode == 401 }
}

enum RelayClient {
    static func get<T: Decodable>(base: String, path: String, query: [URLQueryItem] = [], timeoutInterval: TimeInterval = 30) async throws -> T {
        var components = try components(base: base, path: path)
        if !query.isEmpty { components.queryItems = query }
        guard let url = components.url else { throw RelayRequestError(message: String(localized: "La dirección del relay no es válida.")) }
        var request = URLRequest(url: url)
        request.timeoutInterval = timeoutInterval
        authorize(&request, relayBaseURL: base)
        return try await send(request)
    }

    static func post<T: Decodable, Body: Encodable>(base: String, path: String, body: Body) async throws -> T {
        try await sendJSON(method: "POST", base: base, path: path, body: body, timeoutInterval: path == "/api/play" ? 90 : 30)
    }

    static func put<T: Decodable, Body: Encodable>(base: String, path: String, body: Body) async throws -> T {
        try await sendJSON(method: "PUT", base: base, path: path, body: body, timeoutInterval: 30)
    }

    static func delete<T: Decodable>(base: String, path: String) async throws -> T {
        let components = try components(base: base, path: path)
        guard let url = components.url else { throw RelayRequestError(message: String(localized: "La dirección del relay no es válida.")) }
        var request = URLRequest(url: url)
        request.httpMethod = "DELETE"
        request.timeoutInterval = 15
        authorize(&request, relayBaseURL: base)
        return try await send(request)
    }

    static func provisionUser<T: Decodable, Body: Encodable>(base: String, administratorToken: String, body: Body) async throws -> T {
        let components = try components(base: base, path: "/api/admin/users")
        guard let url = components.url else { throw RelayRequestError(message: String(localized: "La dirección del relay no es válida.")) }
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.timeoutInterval = 30
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue(administratorToken.trimmingCharacters(in: .whitespacesAndNewlines), forHTTPHeaderField: "X-Bridge-Admin-Token")
        request.httpBody = try JSONEncoder().encode(body)
        return try await send(request, unauthorizedMessage: String(localized: "El token administrador no es válido para este relay."))
    }

    /// `true` when the relay rejects requests without a user token.
    static func requiresIdentity(base: String) async -> Bool {
        do {
            let _: HistoryResponse = try await get(base: base, path: "/api/history", timeoutInterval: 10)
            return false
        } catch let error as RelayRequestError {
            return error.isUnauthorized
        } catch {
            return false
        }
    }

    private static func sendJSON<T: Decodable, Body: Encodable>(method: String, base: String, path: String, body: Body, timeoutInterval: TimeInterval) async throws -> T {
        let components = try components(base: base, path: path)
        guard let url = components.url else { throw RelayRequestError(message: String(localized: "La dirección del relay no es válida.")) }
        var request = URLRequest(url: url)
        request.httpMethod = method
        request.timeoutInterval = timeoutInterval
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try JSONEncoder().encode(body)
        authorize(&request, relayBaseURL: base)
        return try await send(request)
    }

    private static func components(base: String, path: String) throws -> URLComponents {
        guard let canonical = RelayEndpoint.canonicalURL(base), var components = URLComponents(string: canonical) else {
            throw RelayRequestError(message: String(localized: "Configura primero la dirección del relay."))
        }
        components.path = path
        return components
    }

    private static func authorize(_ request: inout URLRequest, relayBaseURL: String) {
        let token = SecureTokenStore.currentToken(for: relayBaseURL)
        if !token.isEmpty {
            request.setValue(token, forHTTPHeaderField: "X-Bridge-Token")
        }
    }

    private static func send<T: Decodable>(_ request: URLRequest, unauthorizedMessage: String? = nil) async throws -> T {
        let result: (Data, URLResponse)
        do {
            result = try await URLSession.shared.data(for: request)
        } catch let error as URLError {
            if error.code == .cancelled { throw CancellationError() }
            if !NetworkStatus.shared.isOnline {
                throw RelayRequestError(message: OfflineMessage.general, isOffline: true)
            }
            throw RelayRequestError(message: message(for: error, host: request.url?.host))
        }
        let (data, response) = result
        guard let http = response as? HTTPURLResponse else {
            throw RelayRequestError(message: String(localized: "El relay no respondió correctamente."))
        }
        guard (200...299).contains(http.statusCode) else {
            if http.statusCode == 401 {
                throw RelayRequestError(message: unauthorizedMessage ?? String(localized: "El relay requiere una identidad privada válida. Configúrala en Ajustes."), statusCode: 401)
            }
            let relayMessage = try? JSONDecoder().decode(RelayErrorResponse.self, from: data).error
            throw RelayRequestError(message: relayMessage ?? String(localized: "El relay respondió HTTP \(http.statusCode)."), statusCode: http.statusCode)
        }
        do {
            return try JSONDecoder().decode(T.self, from: data)
        } catch {
            throw RelayRequestError(message: String(localized: "El relay devolvió una respuesta que la app no pudo leer. ¿Está actualizado?"))
        }
    }

    /// IPs and names that only resolve inside the home network, where iOS
    /// asks for the local network permission.
    private static func isLocalNetwork(_ host: String?) -> Bool {
        guard let host = host?.lowercased() else { return false }
        if host.hasSuffix(".local") || host == "localhost" || !host.contains(".") { return true }
        let parts = host.split(separator: ".").compactMap { Int($0) }
        guard parts.count == 4 else { return false }
        switch (parts[0], parts[1]) {
        case (10, _), (127, _), (192, 168), (169, 254): return true
        case (172, 16...31): return true
        default: return false
        }
    }

    /// The device is online here (see `send`).
    private static func message(for error: URLError, host: String?) -> String {
        switch error.code {
        case .appTransportSecurityRequiresSecureConnection:
            return String(localized: "iOS sólo permite HTTP dentro de tu red local. Usa HTTPS para un dominio público.")
        case .timedOut:
            return String(localized: "El relay no respondió a tiempo.")
        case .cannotFindHost, .dnsLookupFailed:
            return String(localized: "No se encontró el host del relay. Revisa el dominio o la IP.")
        case .cannotConnectToHost, .networkConnectionLost, .notConnectedToInternet:
            // Online but unreachable: for a relay on the home network the
            // usual cause is the local network permission.
            return isLocalNetwork(host)
                ? String(localized: "No se pudo llegar al relay en tu red local. Comprueba que esté encendido y que la app tenga permiso en Ajustes → Privacidad y seguridad → Red local.")
                : String(localized: "No se pudo conectar con el relay. Comprueba que esté encendido y accesible.")
        case .secureConnectionFailed, .serverCertificateUntrusted, .serverCertificateHasBadDate, .serverCertificateNotYetValid, .serverCertificateHasUnknownRoot:
            return String(localized: "Falló la conexión HTTPS. Revisa el certificado del dominio o usa HTTP si es una IP local.")
        default:
            return error.localizedDescription
        }
    }
}

enum HistoryIdentity {
    static func storageID(for token: String, relayBaseURL: String) -> String {
        let value = token.trimmingCharacters(in: .whitespacesAndNewlines)
        let endpoint = RelayEndpoint.canonicalURL(relayBaseURL) ?? "unconfigured"
        let material = "\(endpoint)\u{0}\(value.isEmpty ? "local" : value)"
        return SHA256.hash(data: Data(material.utf8)).prefix(12).map { String(format: "%02x", $0) }.joined()
    }
}

enum SecureTokenStore {
    private static let service = "com.personal.airplaysubtitleplayer"
    private static let legacyAccount = "relay-access-token"
    private static let legacyDefaultsKey = "relaycast.relay-access-token"

    static func currentToken(for relayBaseURL: String) -> String {
        guard let account = RelayEndpoint.tokenAccount(for: relayBaseURL) else { return "" }
        return token(forAccount: account, synchronizable: kSecAttrSynchronizableAny) ?? fallbackToken(forAccount: account) ?? ""
    }

    static func restoreFromICloud(for relayBaseURL: String) throws -> String {
        guard let account = RelayEndpoint.tokenAccount(for: relayBaseURL) else {
            throw RelayRequestError(message: String(localized: "Primero configura una URL válida del relay."))
        }
        guard let token = token(forAccount: account, synchronizable: true) else {
            throw RelayRequestError(message: String(localized: "No hay una identidad para esta URL en el Llavero de iCloud todavía."))
        }
        return token
    }

    static func migrateLegacyToken(to relayBaseURL: String) throws -> Bool {
        guard currentToken(for: relayBaseURL).isEmpty else { return false }
        let legacyToken = token(forAccount: legacyAccount, synchronizable: kSecAttrSynchronizableAny)
            ?? UserDefaults.standard.string(forKey: legacyDefaultsKey)?.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let legacyToken, !legacyToken.isEmpty else { return false }
        try save(legacyToken, for: relayBaseURL)
        UserDefaults.standard.removeObject(forKey: legacyDefaultsKey)
        deleteLegacyToken()
        return true
    }

    static func makeCurrentTokenSynchronizable(for relayBaseURL: String) throws -> Bool {
        let account = try account(for: relayBaseURL)
        if token(forAccount: account, synchronizable: true) != nil { return false }
        guard let existing = token(forAccount: account, synchronizable: false) else { return false }
        try save(existing, for: relayBaseURL)
        guard token(forAccount: account, synchronizable: true) == existing else {
            throw RelayRequestError(message: String(localized: "No se pudo verificar la copia sincronizable del token. La copia anterior sigue intacta."))
        }
        return true
    }

    static func save(_ token: String, for relayBaseURL: String) throws {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: try account(for: relayBaseURL),
            kSecAttrSynchronizable as String: true,
        ]
        let data = Data(token.trimmingCharacters(in: .whitespacesAndNewlines).utf8)
        guard !data.isEmpty else { throw RelayRequestError(message: String(localized: "El token no puede estar vacío.")) }
        let attributes: [String: Any] = [
            kSecValueData as String: data,
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlock,
        ]
        let account = try account(for: relayBaseURL)
        // iCloud Keychain first (other devices can restore it), then this
        // device's Keychain. Builds without Keychain access (an unsigned
        // Simulator build, as cloud simulators run) keep it in a protected
        // file instead of failing: the account still works on that device.
        if store(query: query, attributes: attributes) || store(query: query.merging([kSecAttrSynchronizable as String: false]) { $1 }, attributes: attributes) {
            removeFallbackToken(forAccount: account)
            return
        }
        do {
            try saveFallbackToken(data, forAccount: account)
        } catch {
            throw RelayRequestError(message: String(localized: "No se pudo guardar el token en el Llavero."))
        }
    }

    /// Updates or adds one Keychain item; false when the Keychain refuses.
    private static func store(query: [String: Any], attributes: [String: Any]) -> Bool {
        let status = SecItemUpdate(query as CFDictionary, attributes as CFDictionary)
        if status == errSecSuccess { return true }
        guard status == errSecItemNotFound else { return false }
        var add = query
        attributes.forEach { add[$0.key] = $0.value }
        return SecItemAdd(add as CFDictionary, nil) == errSecSuccess
    }

    // MARK: Fallback without Keychain access

    private static func fallbackURL(forAccount account: String) -> URL? {
        guard let folder = try? FileManager.default.url(for: .applicationSupportDirectory, in: .userDomainMask, appropriateFor: nil, create: true)
            .appendingPathComponent("Tokens", isDirectory: true) else { return nil }
        try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        let safeName = account.map { $0.isLetter || $0.isNumber ? String($0) : "_" }.joined()
        return folder.appendingPathComponent(safeName)
    }

    private static func saveFallbackToken(_ data: Data, forAccount account: String) throws {
        guard var url = fallbackURL(forAccount: account) else { throw CocoaError(.fileNoSuchFile) }
        try data.write(to: url, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        try? url.setResourceValues(values)
    }

    private static func fallbackToken(forAccount account: String) -> String? {
        guard let url = fallbackURL(forAccount: account), let data = try? Data(contentsOf: url),
              let token = String(data: data, encoding: .utf8)?.trimmingCharacters(in: .whitespacesAndNewlines), !token.isEmpty else { return nil }
        return token
    }

    private static func removeFallbackToken(forAccount account: String) {
        guard let url = fallbackURL(forAccount: account) else { return }
        try? FileManager.default.removeItem(at: url)
    }

    private static func account(for relayBaseURL: String) throws -> String {
        guard let account = RelayEndpoint.tokenAccount(for: relayBaseURL) else {
            throw RelayRequestError(message: String(localized: "Primero configura una URL válida del relay."))
        }
        return account
    }

    private static func token(forAccount account: String, synchronizable: Any) -> String? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecAttrSynchronizable as String: synchronizable,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        var result: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &result) == errSecSuccess,
              let data = result as? Data,
              let token = String(data: data, encoding: .utf8), !token.isEmpty else {
            return nil
        }
        return token
    }

    private static func deleteLegacyToken() {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: legacyAccount,
            kSecAttrSynchronizable as String: kSecAttrSynchronizableAny,
        ]
        SecItemDelete(query as CFDictionary)
    }
}

enum TokenGenerator {
    static func make() throws -> String {
        var bytes = [UInt8](repeating: 0, count: 32)
        guard SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes) == errSecSuccess else {
            throw RelayRequestError(message: String(localized: "No se pudo generar un token seguro."))
        }
        return bytes.map { String(format: "%02x", $0) }.joined()
    }
}
