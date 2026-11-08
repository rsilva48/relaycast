import AVFoundation
import Observation
import SwiftUI

/// One episode saved for offline playback: the whole HLS package (video,
/// audio and every subtitle rendition) in a `.movpkg`.
struct DownloadRecord: Codable, Identifiable {
    enum State: String, Codable {
        case downloading
        case completed
        case failed
    }

    let id: String
    let watchUrl: String
    let seriesTitle: String
    var titleJp: String?
    var imageUrl: String?
    let episode: Int
    let episodeTitle: String
    let kind: StreamKind
    let serverName: String
    var intro: SkipRange?
    var outro: SkipRange?
    var totalEpisodes: Int?
    var subtitleTracks: Int
    var state: State
    var progress: Double
    /// Package location, relative to the home directory when possible: the
    /// app container path changes between installs.
    var storedPath: String?
    var bytes: Int64?
    var errorMessage: String?
    let createdAt: Date
    /// Servers that already failed for this episode (optional: records
    /// saved by 0.2.7 do not have it).
    var triedServers: [String]? = nil
    /// This episode was watched to its outro or last minutes (on this device).
    var watched: Bool? = nil

    /// The package on disk. A system process writes it into the app's real
    /// container, and the home directory the app sees can change, so it is
    /// also looked up by its `Library/…` part under every root that may be
    /// the container.
    var fileURL: URL? {
        guard let storedPath else { return nil }
        let candidates = Self.packageCandidates(for: storedPath)
        return candidates.first { FileManager.default.fileExists(atPath: $0.path) } ?? candidates.first
    }

    static func packageCandidates(for storedPath: String) -> [URL] {
        let home = NSHomeDirectory()
        var urls = [storedPath.hasPrefix("/") ? URL(fileURLWithPath: storedPath) : URL(fileURLWithPath: home).appendingPathComponent(storedPath)]
        guard let library = storedPath.range(of: "Library/") else { return urls }
        let suffix = String(storedPath[library.lowerBound...])
        let roots = [home]
        for root in roots {
            let url = URL(fileURLWithPath: root).appendingPathComponent(suffix)
            if !urls.contains(url) { urls.append(url) }
        }
        return urls
    }

    var episodeLabel: String {
        let title = episodeTitle.trimmingCharacters(in: .whitespaces)
        return title.isEmpty || title == "Episode \(episode)" ? String(localized: "Episodio \(episode)") : String(localized: "Episodio \(episode) · \(title)")
    }

    var anime: Anime {
        Anime(title: seriesTitle, watchUrl: watchUrl, imageUrl: imageUrl, meta: "", rating: nil, score: nil, mediaType: nil, year: nil, titleJp: titleJp)
    }
}

extension PlaybackResolver {
    /// A download source of `kind` for the episode: the preferred server
    /// first, then the episode's other servers of that kind, skipping the
    /// excluded ones and any that fail to resolve. Nil when none is left.
    func downloadSession(for episode: Episode, kind: StreamKind, preferredName: String?, excluding: Set<String> = []) async throws -> PlaybackSession? {
        let candidates = try await servers(for: episode).filter { $0.kind == kind && !excluding.contains($0.name) }
        let ordered = candidates.filter { $0.name == preferredName } + candidates.filter { $0.name != preferredName }
        var lastError: Error?
        for server in ordered {
            do {
                return try await session(for: episode, server: server, allowDownload: false)
            } catch is CancellationError {
                throw CancellationError()
            } catch {
                lastError = error
            }
        }
        if let lastError { throw lastError }
        return nil
    }
}

/// Downloads run in a background `AVAssetDownloadURLSession`, so they keep
/// going while the app is suspended. Records are stored per device.
@MainActor
@Observable
final class DownloadStore {
    static let shared = DownloadStore()
    static let autoDeleteKey = "relaycast.downloads.auto-delete"

    private(set) var records: [DownloadRecord] = []

    @ObservationIgnored private var session: AVAssetDownloadURLSession?
    @ObservationIgnored private let delegate = DownloadSessionDelegate()
    @ObservationIgnored private var tasks: [String: AVAssetDownloadTask] = [:]
    @ObservationIgnored private var progressObservations: [String: NSKeyValueObservation] = [:]
    /// Identity used to resolve another server when a download fails.
    @ObservationIgnored private weak var profile: ProfileStore?
    private let defaults = UserDefaults.standard
    private static let storageKey = "relaycast.downloads.v1"

    private init() {
        records = (defaults.data(forKey: Self.storageKey)).flatMap { try? JSONDecoder().decode([DownloadRecord].self, from: $0) } ?? []
        delegate.store = self
        let configuration = URLSessionConfiguration.background(withIdentifier: "relaycast.downloads")
        configuration.isDiscretionary = false
        // Finished downloads are picked up the next time the app opens.
        configuration.sessionSendsLaunchEvents = false
        // The CDN limits requests per IP, and the relay paces what it sends;
        // a few connections to it at a time keep every download moving.
        configuration.httpMaximumConnectionsPerHost = 4
        session = AVAssetDownloadURLSession(configuration: configuration, assetDownloadDelegate: delegate, delegateQueue: .main)
        Task { await reattachTasks() }
    }

    /// Deletes downloads of episodes that were watched (the history moved
    /// past them or finished them), on any device with the same identity.
    var autoDeleteWatched: Bool {
        defaults.object(forKey: Self.autoDeleteKey) == nil ? true : defaults.bool(forKey: Self.autoDeleteKey)
    }

    var totalBytes: Int64 { records.compactMap(\.bytes).reduce(0, +) }

    func use(_ profile: ProfileStore) {
        self.profile = profile
    }

    // MARK: Lookup

    func record(watchUrl: String, episode: Int, kind: StreamKind? = nil) -> DownloadRecord? {
        let path = catalogPath(of: watchUrl)
        let matches = records.filter { catalogPath(of: $0.watchUrl) == path && $0.episode == episode && (kind == nil || $0.kind == kind) }
        return matches.first { $0.state == .completed } ?? matches.first { $0.state == .downloading } ?? matches.first
    }

    /// A playable local session for the episode, if its package is on disk.
    func localSession(watchUrl: String, episode: Int, kind: StreamKind?, position: Double) -> PlaybackSession? {
        guard let record = record(watchUrl: watchUrl, episode: episode, kind: kind), record.state == .completed else { return nil }
        return localSession(for: record, position: position)
    }

    func localSession(for record: DownloadRecord, position: Double) -> PlaybackSession? {
        guard let url = record.fileURL, FileManager.default.fileExists(atPath: url.path) else { return nil }
        let playback = Playback(playbackUrl: url.absoluteString, subtitleTracks: record.subtitleTracks, serverName: record.serverName, serverKind: record.kind.rawValue, intro: record.intro, outro: record.outro)
        let entry = WatchHistoryEntry(
            seriesTitle: record.seriesTitle,
            watchUrl: record.watchUrl,
            imageUrl: record.imageUrl,
            episode: record.episode,
            episodeTitle: record.episodeTitle,
            serverName: record.serverName,
            positionSeconds: position,
            updatedAt: Date(),
            titleJp: record.titleJp,
            serverKind: record.kind.rawValue,
            totalEpisodes: record.totalEpisodes
        )
        return PlaybackSession(playback: playback, historyEntry: entry, kind: record.kind, downloadID: record.id)
    }

    /// The downloaded episodes of a series and kind, as a series the player
    /// can move through without the relay.
    func offlineSeries(for record: DownloadRecord) -> Series {
        let path = catalogPath(of: record.watchUrl)
        let episodes = records
            .filter { catalogPath(of: $0.watchUrl) == path && $0.kind == record.kind && $0.state == .completed }
            .sorted { $0.episode < $1.episode }
            .map { Episode(number: $0.episode, title: $0.episodeTitle, sub: $0.kind == .sub, dub: $0.kind == .dub) }
        return Series(
            seriesKey: "",
            title: record.seriesTitle,
            titleJp: record.titleJp,
            episodes: episodes,
            seasons: [],
            synopsis: nil,
            rating: nil,
            adult: nil,
            status: nil,
            mediaType: nil,
            score: nil,
            totalEpisodes: record.totalEpisodes,
            imageUrl: record.imageUrl
        )
    }

    // MARK: Downloading

    /// Downloads a freshly resolved (online) session. The relay keeps the
    /// source for two hours, which is plenty for one episode.
    func start(_ playbackSession: PlaybackSession, triedServers: [String] = []) async {
        let entry = playbackSession.historyEntry
        if let existing = record(watchUrl: entry.watchUrl, episode: entry.episode, kind: playbackSession.kind), existing.state != .failed { return }
        let id = UUID().uuidString
        records.append(DownloadRecord(
            id: id,
            watchUrl: entry.watchUrl,
            seriesTitle: entry.seriesTitle,
            titleJp: entry.titleJp,
            imageUrl: entry.imageUrl,
            episode: entry.episode,
            episodeTitle: entry.episodeTitle,
            kind: playbackSession.kind,
            serverName: playbackSession.playback.serverName,
            intro: playbackSession.playback.intro,
            outro: playbackSession.playback.outro,
            totalEpisodes: entry.totalEpisodes,
            subtitleTracks: playbackSession.playback.subtitleTracks,
            state: .downloading,
            progress: 0,
            createdAt: Date(),
            triedServers: triedServers
        ))
        persist()
        Task { await PosterCache.prefetch(entry.imageUrl) }
        do {
            guard let url = URL(string: playbackSession.playback.playbackUrl), let session else {
                throw RelayRequestError(message: String(localized: "La URL de reproducción no es válida."))
            }
            let asset = AVURLAsset(url: url)
            let configuration = AVAssetDownloadConfiguration(asset: asset, title: "\(entry.seriesTitle) · \(entry.episodeLabel)")
            configuration.primaryContentConfiguration.mediaSelections = try await mediaSelections(for: asset)
            let task = session.makeAssetDownloadTask(downloadConfiguration: configuration)
            task.taskDescription = id
            attach(task, id: id)
            task.resume()
        } catch {
            update(id) {
                $0.state = .failed
                $0.errorMessage = error.localizedDescription
            }
        }
    }

    /// The default selection plus one per subtitle track, so every subtitle
    /// language is stored with the video.
    private func mediaSelections(for asset: AVURLAsset) async throws -> [AVMediaSelection] {
        let preferred = try await asset.load(.preferredMediaSelection)
        guard let group = try await asset.loadMediaSelectionGroup(for: .legible) else { return [preferred] }
        let subtitles: [AVMediaSelection] = group.options.compactMap { option in
            guard let selection = preferred.mutableCopy() as? AVMutableMediaSelection else { return nil }
            selection.select(option, in: group)
            return selection
        }
        return [preferred] + subtitles
    }

    func remove(_ record: DownloadRecord) {
        tasks[record.id]?.cancel()
        detach(record.id)
        if let url = record.fileURL {
            try? FileManager.default.removeItem(at: url)
        }
        records.removeAll { $0.id == record.id }
        persist()
    }

    /// Saves the posters of the downloads (older ones did not keep them).
    func prefetchPosters() async {
        for record in records {
            await PosterCache.prefetch(record.imageUrl)
        }
    }

    /// Why a completed download cannot be played, if it cannot. The paths
    /// help diagnose a package that moved.
    func playbackProblem(for record: DownloadRecord) -> String? {
        guard let url = record.fileURL, FileManager.default.fileExists(atPath: url.path) else {
            let storedPath = record.storedPath ?? String(localized: "sin ruta")
            return String(localized: "No se encontró el archivo de esta descarga. Bórrala y descárgala de nuevo. (Guardado en: \(storedPath); inicio: \(NSHomeDirectory()))")
        }
        return nil
    }

    /// Downloads marked as failed whose package is on disk and complete (for
    /// instance when it was not found where it was recorded) become playable.
    func revalidate() {
        for record in records where record.state == .failed && tasks[record.id] == nil {
            guard let url = record.fileURL, FileManager.default.fileExists(atPath: url.path),
                  AVURLAsset(url: url).assetCache?.isPlayableOffline == true else { continue }
            let size = Self.size(of: url)
            update(record.id) {
                $0.state = .completed
                $0.progress = 1
                $0.bytes = size
                $0.errorMessage = nil
            }
        }
    }

    /// Every downloaded episode of a series (both kinds), for the series
    /// screen when the relay cannot be reached and nothing was cached.
    func offlineSeries(watchUrl: String) -> Series? {
        let path = catalogPath(of: watchUrl)
        let stored = records.filter { catalogPath(of: $0.watchUrl) == path && $0.state == .completed }
        guard let first = stored.first else { return nil }
        // Spelled out step by step: the chained version was too slow for the
        // type checker.
        var copiesByEpisode: [Int: [DownloadRecord]] = [:]
        for record in stored {
            copiesByEpisode[record.episode, default: []].append(record)
        }
        var episodes: [Episode] = []
        for (number, copies) in copiesByEpisode {
            let hasSub = copies.contains { $0.kind == .sub }
            let hasDub = copies.contains { $0.kind == .dub }
            episodes.append(Episode(number: number, title: copies[0].episodeTitle, sub: hasSub, dub: hasDub))
        }
        episodes.sort { $0.number < $1.number }
        return Series(
            seriesKey: "",
            title: first.seriesTitle,
            titleJp: first.titleJp,
            episodes: episodes,
            seasons: [],
            synopsis: nil,
            rating: nil,
            adult: nil,
            status: nil,
            mediaType: nil,
            score: nil,
            totalEpisodes: first.totalEpisodes,
            imageUrl: first.imageUrl
        )
    }

    func removeAll() {
        records.forEach(remove)
    }

    /// Runs when no player is open (app launch, Downloads tab, player
    /// closed). Only an episode that was itself finished is deleted: opening
    /// a later one (which moves Continue forward) does not count as watching.
    func cleanUpWatched(profile: ProfileStore) {
        guard autoDeleteWatched else { return }
        for record in records where record.state == .completed {
            let entry = profile.entry(for: record.watchUrl)
            // Finished here, or finished as the current episode on another device.
            let finishedElsewhere = entry.map { $0.episode == record.episode && $0.isCompleted } ?? false
            if record.watched == true || finishedElsewhere {
                remove(record)
            }
        }
    }

    /// Called by the player when an episode reaches its end; the download is
    /// deleted later, once no player is open.
    func markWatched(watchUrl: String, episode: Int) {
        let path = catalogPath(of: watchUrl)
        for record in records where catalogPath(of: record.watchUrl) == path && record.episode == episode && record.watched != true {
            update(record.id) { $0.watched = true }
        }
    }

    // MARK: Task events

    private func reattachTasks() async {
        guard let session else { return }
        let running = await session.allTasks
        for task in running {
            guard let task = task as? AVAssetDownloadTask, let id = task.taskDescription else { continue }
            if records.contains(where: { $0.id == id }) {
                attach(task, id: id)
            } else {
                task.cancel()
            }
        }
        // Tasks the system no longer knows about will not finish.
        for record in records where record.state == .downloading && tasks[record.id] == nil {
            update(record.id) {
                $0.state = .failed
                $0.errorMessage = String(localized: "La descarga se interrumpió.")
            }
        }
    }

    private func attach(_ task: AVAssetDownloadTask, id: String) {
        tasks[id] = task
        progressObservations[id] = observeProgress(of: task, id: id)
    }

    private func detach(_ id: String) {
        tasks[id] = nil
        progressObservations[id]?.invalidate()
        progressObservations[id] = nil
    }

    nonisolated private func observeProgress(of task: AVAssetDownloadTask, id: String) -> NSKeyValueObservation {
        task.progress.observe(\.fractionCompleted, options: [.new]) { [weak self] progress, _ in
            let value = progress.fractionCompleted
            let store = self
            DispatchQueue.main.async {
                MainActor.assumeIsolated { store?.progressChanged(id: id, value: value) }
            }
        }
    }

    private func progressChanged(id: String, value: Double) {
        guard let index = records.firstIndex(where: { $0.id == id }), records[index].state == .downloading, value.isFinite else { return }
        records[index].progress = min(max(value, 0), 1)
    }

    /// Recorded as reported (absolute); `fileURL` finds it again if the
    /// container root moves.
    fileprivate func packageLocation(_ location: URL, id: String?) {
        guard let id else { return }
        update(id) { $0.storedPath = location.path }
    }

    fileprivate func taskFinished(id: String?, error: Error?) {
        guard let id else { return }
        detach(id)
        guard let record = records.first(where: { $0.id == id }) else { return }
        if let error {
            if (error as NSError).code == NSURLErrorCancelled { return }
            if let url = record.fileURL { try? FileManager.default.removeItem(at: url) }
            update(id) {
                $0.state = .failed
                $0.storedPath = nil
                $0.errorMessage = error.localizedDescription
            }
            Task { await retryWithAnotherServer(id) }
        } else if let url = record.fileURL, FileManager.default.fileExists(atPath: url.path) {
            // Stores where the package was actually found.
            if url.path != record.storedPath { update(id) { $0.storedPath = url.path } }
            let size = Self.size(of: url)
            update(id) {
                $0.state = .completed
                $0.progress = 1
                $0.bytes = size
                $0.errorMessage = nil
            }
        } else {
            let storedPath = record.storedPath ?? String(localized: "sin ruta")
            update(id) {
                $0.state = .failed
                $0.errorMessage = String(localized: "La descarga terminó, pero no se encontró el archivo (\(storedPath)).")
            }
            Task { await retryWithAnotherServer(id) }
        }
    }

    /// A failed download starts over with the next server of the same kind
    /// that was not tried yet, until the episode runs out of servers.
    private func retryWithAnotherServer(_ id: String) async {
        guard let profile, profile.isRelayConfigured, let record = records.first(where: { $0.id == id }) else { return }
        let tried = Set((record.triedServers ?? []) + [record.serverName])
        let failure = record.errorMessage ?? String(localized: "La descarga falló.")
        update(id) { $0.errorMessage = String(localized: "\(failure) Probando otro servidor…") }
        do {
            guard let session = try await alternativeSession(for: record, excluding: tried, profile: profile) else {
                update(id) { $0.errorMessage = String(localized: "\(failure) Ningún otro servidor \(record.kind.displayName) funcionó.") }
                return
            }
            remove(record)
            await start(session, triedServers: Array(tried))
        } catch {
            update(id) { $0.errorMessage = "\(failure) \(error.localizedDescription)" }
        }
    }

    /// Resolves the record's episode again through the relay, preferring
    /// its server unless excluded.
    func alternativeSession(for record: DownloadRecord, excluding: Set<String>, profile: ProfileStore) async throws -> PlaybackSession? {
        let series: Series = try await RelayClient.post(base: profile.relayURL, path: "/api/series", body: WatchRequest(watchUrl: record.watchUrl))
        guard let episode = series.episodes.first(where: { $0.number == record.episode }) else {
            throw RelayRequestError(message: String(localized: "El episodio \(record.episode) ya no está en el catálogo."))
        }
        let resolver = PlaybackResolver(series: series, anime: record.anime, profile: profile)
        return try await resolver.downloadSession(for: episode, kind: record.kind, preferredName: record.serverName, excluding: excluding)
    }

    private static func size(of url: URL) -> Int64 {
        guard let files = FileManager.default.enumerator(at: url, includingPropertiesForKeys: [.totalFileAllocatedSizeKey]) else { return 0 }
        var total: Int64 = 0
        for case let file as URL in files {
            total += Int64((try? file.resourceValues(forKeys: [.totalFileAllocatedSizeKey]).totalFileAllocatedSize) ?? 0)
        }
        return total
    }

    private func update(_ id: String, _ change: (inout DownloadRecord) -> Void) {
        guard let index = records.firstIndex(where: { $0.id == id }) else { return }
        change(&records[index])
        persist()
    }

    private func persist() {
        if let data = try? JSONEncoder().encode(records) {
            defaults.set(data, forKey: Self.storageKey)
        }
    }
}

/// Session callbacks arrive on the main queue (see `delegateQueue`).
private final class DownloadSessionDelegate: NSObject, AVAssetDownloadDelegate {
    weak var store: DownloadStore?

    func urlSession(_ session: URLSession, assetDownloadTask: AVAssetDownloadTask, willDownloadTo location: URL) {
        let id = assetDownloadTask.taskDescription
        MainActor.assumeIsolated { store?.packageLocation(location, id: id) }
    }

    func urlSession(_ session: URLSession, assetDownloadTask: AVAssetDownloadTask, didFinishDownloadingTo location: URL) {
        let id = assetDownloadTask.taskDescription
        MainActor.assumeIsolated { store?.packageLocation(location, id: id) }
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        let id = task.taskDescription
        MainActor.assumeIsolated { store?.taskFinished(id: id, error: error) }
    }
}

// MARK: - Downloads tab

/// Works without a relay or network: everything here plays from disk.
struct DownloadsScreen: View {
    @Environment(ProfileStore.self) private var profile
    @Environment(DownloadStore.self) private var downloads
    @State private var activePlayer: PlayerModel?
    @State private var retrying: String?
    @State private var errorMessage: String?

    private var groups: [[DownloadRecord]] {
        let grouped = Dictionary(grouping: downloads.records) { catalogPath(of: $0.watchUrl) }
        return grouped.values
            .map { $0.sorted { ($0.episode, $0.kind.rawValue) < ($1.episode, $1.kind.rawValue) } }
            .sorted { ($0.map(\.createdAt).max() ?? .distantPast) > ($1.map(\.createdAt).max() ?? .distantPast) }
    }

    var body: some View {
        NavigationStack {
            List {
                if let errorMessage {
                    Section { ErrorNotice(message: errorMessage) }
                }
                ForEach(groups, id: \.first?.id) { group in
                    if let first = group.first {
                        Section {
                            ForEach(group) { record in
                                row(record)
                            }
                        } header: {
                            Text(first.anime.displayTitle(profile.preferences.titleLanguage))
                        }
                    }
                }
            }
            // Over the list, not as a row: a row leaves out the icon.
            .overlay {
                if downloads.records.isEmpty && errorMessage == nil {
                    ContentUnavailableView("Sin descargas", systemImage: "arrow.down.circle", description: Text("En la ficha de una serie, mantén pulsado un episodio o deslízalo a la derecha para descargarlo con sus subtítulos y verlo sin conexión."))
                }
            }
            .navigationTitle("Descargas")
            .modifier(AdaptableTabRootTitleStyle())
            .toolbar {
                if downloads.totalBytes > 0 {
                    ToolbarItem(placement: .topBarTrailing) {
                        Text(ByteCountFormatter.string(fromByteCount: downloads.totalBytes, countStyle: .file))
                            .font(.footnote.monospacedDigit())
                            .foregroundStyle(.secondary)
                    }
                }
            }
            .task {
                downloads.revalidate()
                downloads.cleanUpWatched(profile: profile)
                await downloads.prefetchPosters()
            }
            .fullScreenCover(item: $activePlayer, onDismiss: { downloads.cleanUpWatched(profile: profile) }) { model in
                PlayerScreen(model: model)
            }
        }
    }

    private func row(_ record: DownloadRecord) -> some View {
        Button {
            if record.state == .completed { play(record) }
        } label: {
            DownloadRow(record: record, progress: profile.entry(for: record.watchUrl).flatMap { $0.episode == record.episode && !$0.isCompleted ? $0.positionSeconds : nil }, retrying: retrying == record.id)
        }
        .disabled(record.state == .downloading)
        .swipeActions(edge: .trailing, allowsFullSwipe: true) {
            Button(role: .destructive) {
                downloads.remove(record)
            } label: {
                Label(record.state == .downloading ? String(localized: "Cancelar") : String(localized: "Borrar"), systemImage: "trash")
            }
        }
        .swipeActions(edge: .leading) {
            if record.state == .failed {
                Button {
                    retry(record)
                } label: {
                    Label("Reintentar", systemImage: "arrow.clockwise")
                }
                .tint(.indigo)
            }
        }
    }

    private func play(_ record: DownloadRecord) {
        guard activePlayer == nil else { return }
        errorMessage = downloads.playbackProblem(for: record)
        guard errorMessage == nil,
              let session = downloads.localSession(for: record, position: profile.position(for: record.watchUrl, episode: record.episode)) else { return }
        let resolver = PlaybackResolver(series: downloads.offlineSeries(for: record), anime: record.anime, profile: profile)
        activePlayer = PlayerModel(session: session, resolver: resolver, profile: profile)
    }

    /// Failed downloads need the relay again: the series is reloaded and the
    /// same server (or another of the same kind) is resolved once more.
    private func retry(_ record: DownloadRecord) {
        guard retrying == nil else { return }
        retrying = record.id
        errorMessage = nil
        Task {
            defer { retrying = nil }
            do {
                guard let session = try await downloads.alternativeSession(for: record, excluding: [], profile: profile) else {
                    throw RelayRequestError(message: String(localized: "El episodio \(record.episode) no tiene servidores \(record.kind.displayName)."))
                }
                downloads.remove(record)
                await downloads.start(session)
            } catch {
                errorMessage = error.localizedDescription
            }
        }
    }
}

private struct DownloadRow: View {
    let record: DownloadRecord
    let progress: Double?
    let retrying: Bool

    var body: some View {
        HStack(spacing: 12) {
            PosterImage(imageURL: record.imageUrl, cornerRadius: 6)
                .frame(width: 40)
            VStack(alignment: .leading, spacing: 4) {
                Text(record.episodeLabel)
                    .foregroundStyle(.primary)
                    .lineLimit(2)
                status
                    .font(.caption)
            }
            Spacer(minLength: 0)
            if record.state == .completed {
                Image(systemName: "play.circle")
                    .foregroundStyle(.secondary)
            }
        }
        .contentShape(Rectangle())
    }

    @ViewBuilder
    private var status: some View {
        switch record.state {
        case .downloading:
            ProgressView(value: record.progress) {
                Text(record.progress > 0
                     ? String(localized: "Descargando · \(record.kind.displayName) · \(record.serverName) · \(record.progress.formatted(.percent.precision(.fractionLength(0))))")
                     : String(localized: "Esperando · \(record.kind.displayName) · \(record.serverName)"))
                    .foregroundStyle(.secondary)
            }
        case .completed:
            let size = record.bytes.map { ByteCountFormatter.string(fromByteCount: $0, countStyle: .file) }
            let parts = [record.kind.displayName, record.serverName, size, progress.map { String(localized: "Visto hasta \(formatTime($0))") }].compactMap { $0 }
            Text(parts.joined(separator: " · "))
                .foregroundStyle(.secondary)
        case .failed:
            if retrying {
                Text("Reintentando…")
                    .foregroundStyle(.secondary)
            } else {
                Label(record.errorMessage ?? String(localized: "La descarga falló."), systemImage: "exclamationmark.triangle.fill")
                    .foregroundStyle(.red)
                    .lineLimit(2)
            }
        }
    }
}

/// Download state for an episode row in the series screen.
struct DownloadBadge: View {
    let record: DownloadRecord

    var body: some View {
        switch record.state {
        case .downloading:
            // A determinate ring: the circular ProgressView style ignores the
            // value on iOS and always spins.
            ZStack {
                Circle()
                    .stroke(Color.secondary.opacity(0.25), lineWidth: 3)
                Circle()
                    .trim(from: 0, to: min(max(record.progress, 0.02), 1))
                    .stroke(.tint, style: StrokeStyle(lineWidth: 3, lineCap: .round))
                    .rotationEffect(.degrees(-90))
                Image(systemName: "stop.fill")
                    .font(.system(size: 7, weight: .bold))
                    .foregroundStyle(.tint)
            }
            .frame(width: 22, height: 22)
            .animation(.linear(duration: 0.3), value: record.progress)
            .accessibilityElement()
            .accessibilityLabel("Descargando")
            .accessibilityValue(Text(record.progress, format: .percent.precision(.fractionLength(0))))
        case .completed:
            Image(systemName: "arrow.down.circle.fill")
                .foregroundStyle(.tint)
                .accessibilityLabel("Descargado")
        case .failed:
            Image(systemName: "exclamationmark.arrow.circlepath")
                .foregroundStyle(.red)
                .accessibilityLabel("Descarga fallida")
        }
    }
}
