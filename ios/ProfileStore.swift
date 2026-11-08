import Foundation
import Network
import Observation

/// Everything that belongs to the active identity: the tested relay URL, the
/// storage scope derived from that URL and its token, the catalog/playback
/// preferences and the watch history. Changing the token switches all of it.
@MainActor
@Observable
final class ProfileStore {
    private enum Keys {
        static let activeRelay = "relaycast.verified-relay-url"
        static let savedRelay = "relaycast.relay-base-url"
        static let legacyPreferencesMigrated = "relaycast.preferences.legacy-migrated"
        static func history(_ scope: String) -> String { "relaycast.watch-history.v3.\(scope)" }
        static func deletions(_ scope: String) -> String { "relaycast.watch-history.v4.deletions.\(scope)" }
        static func preferences(_ scope: String) -> String { "relaycast.preferences.v1.\(scope)" }
        static func account(_ scope: String) -> String { "relaycast.account.v1.\(scope)" }
    }

    private struct HistorySnapshot {
        let entries: [WatchHistoryEntry]
        let deletions: [HistoryDeletion]
    }

    /// Only a URL that answered `/health` becomes active; it stays empty until
    /// the first successful test, which keeps the catalog tabs locked.
    private(set) var relayURL: String
    private(set) var hasToken = false
    private(set) var preferences = UserPreferences()
    /// Name, email and picture of the identity (cached for offline use).
    private(set) var account: AccountProfile?
    private(set) var entries: [WatchHistoryEntry] = []
    private(set) var scope: String?

    @ObservationIgnored private var deletions: [HistoryDeletion] = []
    @ObservationIgnored private var lastSync = Date.distantPast
    @ObservationIgnored private var syncing = false
    @ObservationIgnored private let pathMonitor = NWPathMonitor()
    /// Whether the device has a network connection (observable for banners).
    private(set) var isOnline = true
    private let defaults: UserDefaults

    var isRelayConfigured: Bool { !relayURL.isEmpty }

    /// The tested relay address, for work outside the app's screens (the
    /// background release check).
    static func activeRelayURL(defaults: UserDefaults = .standard) -> String? {
        defaults.string(forKey: Keys.activeRelay).flatMap(RelayEndpoint.canonicalURL)
    }

    /// Address typed in an older version before it was tested; used to prefill settings.
    var savedRelayAddress: String? { defaults.string(forKey: Keys.savedRelay) }

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        relayURL = defaults.string(forKey: Keys.activeRelay).flatMap(RelayEndpoint.canonicalURL) ?? ""
        reloadIdentity()
        monitorConnectivity()
    }

    /// History and preferences are always saved on the device first; progress
    /// made offline (in this launch or an earlier one) reaches the relay as
    /// soon as the connection returns.
    private func monitorConnectivity() {
        pathMonitor.pathUpdateHandler = { [weak self] path in
            let online = path.status == .satisfied
            let store = self
            DispatchQueue.main.async {
                MainActor.assumeIsolated { store?.connectivityChanged(online: online) }
            }
        }
        pathMonitor.start(queue: DispatchQueue(label: "relaycast.connectivity"))
    }

    private func connectivityChanged(online: Bool) {
        let reconnected = online && !isOnline
        isOnline = online
        if reconnected {
            Task { await sync(force: true) }
        }
    }

    func useRelay(_ url: String) {
        guard let canonical = RelayEndpoint.canonicalURL(url) else { return }
        defaults.set(canonical, forKey: Keys.activeRelay)
        defaults.set(canonical, forKey: Keys.savedRelay)
        relayURL = canonical
        reloadIdentity()
    }

    /// Re-reads the token for the active relay and switches the storage scope.
    func reloadIdentity() {
        let token = isRelayConfigured ? SecureTokenStore.currentToken(for: relayURL) : ""
        hasToken = !token.isEmpty
        let newScope = isRelayConfigured ? HistoryIdentity.storageID(for: token, relayBaseURL: relayURL) : nil
        guard newScope != scope else { return }
        persist()
        scope = newScope
        lastSync = .distantPast
        guard let newScope else {
            entries = []
            deletions = []
            preferences = UserPreferences()
            account = nil
            return
        }
        account = load(AccountProfile.self, forKey: Keys.account(newScope))
        let snapshot = resolvedHistory(
            entries: load([WatchHistoryEntry].self, forKey: Keys.history(newScope)) ?? [],
            deletions: load([HistoryDeletion].self, forKey: Keys.deletions(newScope)) ?? []
        )
        entries = snapshot.entries
        deletions = snapshot.deletions
        preferences = load(UserPreferences.self, forKey: Keys.preferences(newScope)) ?? migratedLegacyPreferences() ?? UserPreferences()
        persist()
    }

    // MARK: Preferences

    func setPreferences(_ value: UserPreferences) {
        guard let scope, !value.hasSameSettings(as: preferences) else { return }
        var updated = value
        updated.updatedAt = Date()
        preferences = updated
        persist()
        let base = relayURL
        Task { await pushPreferences(base: base, scope: scope) }
    }

    // MARK: History

    func entry(for watchUrl: String) -> WatchHistoryEntry? {
        let path = catalogPath(of: watchUrl)
        return entries.first { catalogPath(of: $0.watchUrl) == path }
    }

    /// Where an episode resumes. A finished episode starts over instead of
    /// reopening on its last second.
    func position(for watchUrl: String, episode: Int) -> Double {
        guard let entry = entry(for: watchUrl), entry.episode == episode, !entry.isCompleted else { return 0 }
        return entry.positionSeconds
    }

    func open(_ entry: WatchHistoryEntry) {
        var copy = entry
        copy.positionSeconds = position(for: entry.watchUrl, episode: entry.episode)
        if let existing = self.entry(for: entry.watchUrl), existing.episode == entry.episode, !existing.isCompleted {
            copy.durationSeconds = existing.durationSeconds
        }
        copy.completed = false
        upsert(copy)
    }

    func update(_ entry: WatchHistoryEntry, positionSeconds: Double, durationSeconds: Double? = nil, completed: Bool = false) {
        var copy = entry
        copy.positionSeconds = positionSeconds
        if let durationSeconds, durationSeconds > 0 { copy.durationSeconds = durationSeconds }
        copy.completed = completed
        upsert(copy)
    }

    func remove(_ entriesToRemove: [WatchHistoryEntry]) {
        let paths = Set(entriesToRemove.map { catalogPath(of: $0.watchUrl) })
        guard scope != nil, !paths.isEmpty else { return }
        let now = Date()
        apply(resolvedHistory(
            entries: entries.filter { !paths.contains(catalogPath(of: $0.watchUrl)) },
            deletions: deletions.filter { !paths.contains(catalogPath(of: $0.watchUrl)) }
                + entriesToRemove.map { HistoryDeletion(watchUrl: $0.watchUrl, updatedAt: now) }
        ))
    }

    // MARK: Sync

    /// Merges history with the relay. Forced syncs (launch, refresh, identity
    /// change) also reconcile preferences; playback ticks only push progress.
    func sync(force: Bool = false) async {
        guard let scope, !syncing, force || Date().timeIntervalSince(lastSync) >= 12 else { return }
        syncing = true
        defer {
            syncing = false
            lastSync = Date()
        }
        let base = relayURL
        do {
            let remote: HistoryResponse = try await RelayClient.get(base: base, path: "/api/history")
            guard scope == self.scope else { return }
            apply(resolvedHistory(entries: entries + remote.entries, deletions: deletions + (remote.deletions ?? [])))
            let saved: HistoryResponse = try await RelayClient.put(base: base, path: "/api/history", body: HistoryPayload(entries: entries, deletions: deletions))
            guard scope == self.scope else { return }
            apply(resolvedHistory(entries: entries + saved.entries, deletions: deletions + (saved.deletions ?? [])))
        } catch {
            // The local history remains usable while the relay is offline.
        }
        if force, scope == self.scope {
            await pullPreferences(base: base, scope: scope)
            await pullAccount(base: base, scope: scope)
        }
    }

    // MARK: Account

    private func pullAccount(base: String, scope: String) async {
        // Relays before 0.3.0 have no profile: the local copy stays.
        guard let remote: AccountEnvelope = try? await RelayClient.get(base: base, path: "/api/profile"), scope == self.scope else { return }
        storeAccount(remote.profile, scope: scope)
    }

    /// The account's name, email and picture from the relay now (after
    /// identifying with an existing account, before offering to edit them).
    func refreshAccount() async {
        guard let scope else { return }
        await pullAccount(base: relayURL, scope: scope)
    }

    /// Saves the name, email and picture on the relay.
    func saveAccount(_ update: AccountUpdate) async throws {
        guard let scope else { return }
        let saved: AccountEnvelope = try await RelayClient.put(base: relayURL, path: "/api/profile", body: update)
        guard scope == self.scope else { return }
        storeAccount(saved.profile, scope: scope)
    }

    private func storeAccount(_ value: AccountProfile, scope: String) {
        account = value
        if let data = try? JSONEncoder().encode(value) { defaults.set(data, forKey: Keys.account(scope)) }
    }

    private func pullPreferences(base: String, scope: String) async {
        do {
            let remote: PreferencesEnvelope = try await RelayClient.get(base: base, path: "/api/preferences")
            guard scope == self.scope else { return }
            if let incoming = remote.preferences, incoming.updatedAt > preferences.updatedAt {
                preferences = incoming
                persist()
            } else if preferences.updatedAt > (remote.preferences?.updatedAt ?? .distantPast) {
                await pushPreferences(base: base, scope: scope)
            }
        } catch {
            // Relays without the preferences endpoint keep settings on the device.
        }
    }

    private func pushPreferences(base: String, scope: String) async {
        do {
            let saved: PreferencesEnvelope = try await RelayClient.put(base: base, path: "/api/preferences", body: PreferencesEnvelope(preferences: preferences))
            guard scope == self.scope, let stored = saved.preferences, stored.updatedAt > preferences.updatedAt else { return }
            preferences = stored
            persist()
        } catch {
            // Retried on the next forced sync.
        }
    }

    // MARK: Storage

    private func upsert(_ entry: WatchHistoryEntry) {
        guard scope != nil else { return }
        var copy = entry
        copy.updatedAt = Date()
        let path = catalogPath(of: copy.watchUrl)
        apply(resolvedHistory(
            entries: entries.filter { catalogPath(of: $0.watchUrl) != path } + [copy],
            deletions: deletions.filter { catalogPath(of: $0.watchUrl) != path }
        ))
    }

    /// Keeps the newest entry per series and lets a newer deletion win over it.
    /// Series are matched by their catalog path, so a domain move merges cleanly.
    private func resolvedHistory(entries: [WatchHistoryEntry], deletions: [HistoryDeletion]) -> HistorySnapshot {
        var latestEntries: [String: WatchHistoryEntry] = [:]
        var latestDeletions: [String: HistoryDeletion] = [:]
        for entry in entries {
            let path = catalogPath(of: entry.watchUrl)
            if let existing = latestEntries[path], existing.updatedAt > entry.updatedAt { continue }
            latestEntries[path] = entry
        }
        for deletion in deletions {
            let path = catalogPath(of: deletion.watchUrl)
            if let existing = latestDeletions[path], existing.updatedAt > deletion.updatedAt { continue }
            latestDeletions[path] = deletion
        }
        for (path, deletion) in latestDeletions {
            if let entry = latestEntries[path], entry.updatedAt > deletion.updatedAt {
                latestDeletions.removeValue(forKey: path)
            } else {
                latestEntries.removeValue(forKey: path)
            }
        }
        return HistorySnapshot(
            entries: Array(latestEntries.values.sorted { $0.updatedAt > $1.updatedAt }.prefix(50)),
            deletions: Array(latestDeletions.values.sorted { $0.updatedAt > $1.updatedAt }.prefix(500))
        )
    }

    private func apply(_ snapshot: HistorySnapshot) {
        entries = snapshot.entries
        deletions = snapshot.deletions
        persist()
    }

    private func persist() {
        guard let scope else { return }
        let encoder = JSONEncoder()
        if let data = try? encoder.encode(entries) { defaults.set(data, forKey: Keys.history(scope)) }
        if let data = try? encoder.encode(deletions) { defaults.set(data, forKey: Keys.deletions(scope)) }
        if let data = try? encoder.encode(preferences) { defaults.set(data, forKey: Keys.preferences(scope)) }
    }

    private func load<T: Decodable>(_ type: T.Type, forKey key: String) -> T? {
        guard let data = defaults.data(forKey: key) else { return nil }
        return try? JSONDecoder().decode(type, from: data)
    }

    /// Versions before per-identity preferences stored them globally. The first
    /// identity opened after updating inherits them once; a newer copy on the
    /// relay still wins because these keep an old timestamp.
    private func migratedLegacyPreferences() -> UserPreferences? {
        guard !defaults.bool(forKey: Keys.legacyPreferencesMigrated) else { return nil }
        defaults.set(true, forKey: Keys.legacyPreferencesMigrated)
        var value = UserPreferences()
        if let raw = defaults.string(forKey: "relaycast.title-language"), let language = TitleLanguage(rawValue: raw) { value.titleLanguage = language }
        if let raw = defaults.string(forKey: "relaycast.default-track-type"), let kind = StreamKind(rawValue: raw) { value.defaultTrack = kind }
        if defaults.object(forKey: "relaycast.autonext") != nil { value.autoNext = defaults.bool(forKey: "relaycast.autonext") }
        if defaults.object(forKey: "relaycast.autoskip-intro") != nil { value.autoSkipIntro = defaults.bool(forKey: "relaycast.autoskip-intro") }
        if defaults.object(forKey: "relaycast.autoskip-outro") != nil { value.autoSkipOutro = defaults.bool(forKey: "relaycast.autoskip-outro") }
        return value
    }
}
