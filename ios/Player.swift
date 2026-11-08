import AVFoundation
import AVKit
import MediaPlayer
import Observation
import SwiftUI
import UIKit

enum PlaybackSpeed: Float, CaseIterable, Identifiable {
    case slow = 0.75
    case normal = 1
    case fast = 1.25
    case faster = 1.5
    case double = 2

    var id: Float { rawValue }

    var label: String {
        switch self {
        case .slow: return String(localized: "0,75×")
        case .normal: return String(localized: "Normal")
        case .fast: return String(localized: "1,25×")
        case .faster: return String(localized: "1,5×")
        case .double: return "2×"
        }
    }
}

/// Owns the AVPlayer for one presentation of the player. Episodes change in
/// place: the item is replaced while the screen, AirPlay route and PiP stay.
@MainActor
@Observable
final class PlayerModel: Identifiable {
    let player = AVPlayer()
    private(set) var session: PlaybackSession
    private(set) var currentTime: Double = 0
    private(set) var duration: Double = 0
    private(set) var isPlaying = false
    private(set) var isBuffering = true
    private(set) var isExternalPlayback = false
    private(set) var externalRouteName: String?
    private(set) var legibleOptions: [AVMediaSelectionOption] = []
    private(set) var selectedLegible: AVMediaSelectionOption?
    private(set) var audibleOptions: [AVMediaSelectionOption] = []
    private(set) var selectedAudible: AVMediaSelectionOption?
    private(set) var hasLoadedMediaOptions = false
    private(set) var canUsePictureInPicture = false
    private(set) var isPictureInPictureActive = false
    private(set) var isChangingEpisode = false
    private(set) var didFinish = false
    /// The last episode available for this kind ended: the player closes.
    private(set) var reachedSeriesEnd = false
    private(set) var errorMessage: String?
    private(set) var speed: PlaybackSpeed = .normal
    var videoGravity: AVLayerVideoGravity = .resizeAspect
    /// The cast link this episode plays from on a Google Cast device; nil
    /// while it plays here (or through AirPlay).
    private(set) var castShare: CastShare?
    private(set) var isPreparingCast = false
    /// The receiver's subtitle track (`CastSubtitle.id`), nil when off.
    private(set) var castSubtitleID: Int?

    var isCasting: Bool { castShare != nil }

    private let resolver: PlaybackResolver
    private let profile: ProfileStore
    @ObservationIgnored private var legibleGroup: AVMediaSelectionGroup?
    @ObservationIgnored private var audibleGroup: AVMediaSelectionGroup?
    /// Subtitle choice made during a Dub episode; kept only for this session.
    @ObservationIgnored private var dubSubtitleChoice = ""
    @ObservationIgnored private var playerObservations: [NSKeyValueObservation] = []
    @ObservationIgnored private var itemObservations: [NSKeyValueObservation] = []
    @ObservationIgnored private var itemNotifications: [NSObjectProtocol] = []
    /// Per item: media options are loaded once and a failure is reported once.
    @ObservationIgnored private var mediaOptionsRequested = false
    @ObservationIgnored private var failureReported = false
    @ObservationIgnored private var loadWatchdog: Task<Void, Never>?
    @ObservationIgnored private var timeObserver: Any?
    @ObservationIgnored private var pictureInPicture: AVPictureInPictureController?
    @ObservationIgnored private var remoteCommandTargets: [(MPRemoteCommand, Any)] = []
    /// Plays while casting; see `keepNowPlaying(whileCasting:)`.
    @ObservationIgnored private var castSilence: AVAudioPlayer?
    /// The series' poster for Now Playing, once loaded.
    @ObservationIgnored private var artwork: (url: String, image: MPMediaItemArtwork)?
    @ObservationIgnored private var loadingArtwork: String?
    @ObservationIgnored private var lastSavedSecond = -1
    @ObservationIgnored private var started = false
    @ObservationIgnored private var closed = false

    init(session: PlaybackSession, resolver: PlaybackResolver, profile: ProfileStore) {
        self.session = session
        self.resolver = resolver
        self.profile = profile
    }

    var hasPrevious: Bool { resolver.episode(adjacentTo: session.historyEntry.episode, offset: -1) != nil }
    var hasNext: Bool { nextEpisode != nil }

    /// Sub and Dub advance separately: a Dub viewer only moves on to episodes
    /// that already have a Dub source.
    private var nextEpisode: Episode? { resolver.series.episode(after: session.historyEntry.episode, kind: session.kind) }

    var activeSkip: (title: String, end: Double)? {
        let preferences = profile.preferences
        if let intro = session.playback.intro, intro.contains(currentTime), !preferences.autoSkipIntro { return (String(localized: "Saltar intro"), intro.end) }
        if let outro = session.playback.outro, outro.contains(currentTime), !preferences.autoSkipOutro { return (String(localized: "Saltar outro"), outro.end) }
        return nil
    }

    // MARK: Lifecycle

    func start() {
        guard !started else { return }
        started = true
        // The volume buttons and the lock screen control this device again, not the TV.
        TVVolumeRelay.shared.localPlayback = true
        TVNowPlaying.shared.yieldToLocalPlayback()
        AudioSession.configure()
        AudioSession.setActive(true)
        player.allowsExternalPlayback = true
        player.usesExternalPlaybackWhileExternalScreenIsActive = true
        observePlayer()
        registerRemoteCommands()
        load(session)
    }

    func close() {
        guard started, !closed else { return }
        // A Cast device keeps playing after the player closes; its controls
        // stay in the Cast button.
        if isCasting { saveCastProgress(force: true) }
        closed = true
        TVVolumeRelay.shared.localPlayback = false
        loadWatchdog?.cancel()
        saveProgress()
        playerObservations.forEach { $0.invalidate() }
        playerObservations = []
        stopObservingItem()
        if let timeObserver {
            player.removeTimeObserver(timeObserver)
            self.timeObserver = nil
        }
        pictureInPicture?.stopPictureInPicture()
        pictureInPicture = nil
        player.pause()
        player.replaceCurrentItem(with: nil)
        keepNowPlaying(whileCasting: false)
        unregisterRemoteCommands()
        MPNowPlayingInfoCenter.default().nowPlayingInfo = nil
        // The audio session stays active (as before 0.2.0): deactivating it
        // while AVFoundation tears the item down could leave the next player
        // or the AirPlay route in a bad state.
    }

    func attach(_ layer: AVPlayerLayer) {
        guard pictureInPicture == nil, AVPictureInPictureController.isPictureInPictureSupported() else { return }
        let controller: AVPictureInPictureController? = AVPictureInPictureController(playerLayer: layer)
        guard let controller else { return }
        controller.canStartPictureInPictureAutomaticallyFromInline = true
        pictureInPicture = controller
        playerObservations += [
            refreshOnChange(of: controller, \.isPictureInPicturePossible),
            refreshOnChange(of: controller, \.isPictureInPictureActive),
        ]
    }

    // MARK: Controls

    func togglePlayback() {
        isPlaying ? pause() : play()
    }

    func play() {
        if didFinish {
            didFinish = false
            seek(to: 0)
        }
        if isCasting {
            CastCenter.shared.play()
            return
        }
        player.defaultRate = speed.rawValue
        player.play()
    }

    func pause() {
        if isCasting {
            CastCenter.shared.pause()
            saveCastProgress(force: true)
            return
        }
        player.pause()
        saveProgress()
    }

    func skip(by seconds: Double) {
        seek(to: currentTime + seconds)
    }

    func seek(to seconds: Double) {
        let upper = duration > 0 ? max(0, duration - 0.5) : max(0, seconds)
        let target = min(max(0, seconds), upper)
        currentTime = target
        if isCasting {
            CastCenter.shared.seek(to: target)
            return
        }
        let time = CMTime(seconds: target, preferredTimescale: 600)
        guard player.currentItem?.status == .readyToPlay else {
            player.seek(to: time)
            return
        }
        player.seek(to: time) { [weak self] _ in
            Task { @MainActor [weak self] in self?.updateNowPlaying() }
        }
    }

    func setSpeed(_ value: PlaybackSpeed) {
        speed = value
        player.defaultRate = value.rawValue
        if isPlaying { player.rate = value.rawValue }
        updateNowPlaying()
    }

    func selectLegible(_ option: AVMediaSelectionOption?) {
        guard let item = player.currentItem, let group = legibleGroup else { return }
        item.select(option, in: group)
        selectedLegible = option
        let choice = option.map { $0.extendedLanguageTag ?? "" } ?? UserPreferences.subtitlesOff
        if session.kind == .sub {
            // Remembered for the identity, so the next episode (and other
            // devices with the same token) start with the same subtitles.
            var preferences = profile.preferences
            preferences.subtitleLanguage = choice
            profile.setPreferences(preferences)
        } else {
            dubSubtitleChoice = choice
        }
    }

    func selectAudible(_ option: AVMediaSelectionOption) {
        guard let item = player.currentItem, let group = audibleGroup else { return }
        item.select(option, in: group)
        selectedAudible = option
    }

    func togglePictureInPicture() {
        guard let pictureInPicture else { return }
        if pictureInPicture.isPictureInPictureActive {
            pictureInPicture.stopPictureInPicture()
        } else {
            pictureInPicture.startPictureInPicture()
        }
    }

    func changeEpisode(by offset: Int) {
        guard !isChangingEpisode, !closed,
              let episode = offset > 0 ? nextEpisode : resolver.episode(adjacentTo: session.historyEntry.episode, offset: offset) else { return }
        saveProgress()
        if isCasting {
            saveCastProgress(force: true)
            CastCenter.shared.pause()
        }
        player.pause()
        isChangingEpisode = true
        errorMessage = nil
        let current = session
        Task {
            defer { isChangingEpisode = false }
            do {
                let next = try await resolver.session(for: episode, preferredName: current.playback.serverName, kind: current.kind)
                guard !closed else { return }
                load(next)
            } catch is CancellationError {
                return
            } catch {
                errorMessage = String(localized: "No se pudo abrir el episodio \(episode.number). \(error.localizedDescription)")
            }
        }
    }

    func dismissError() {
        errorMessage = nil
    }

    /// Plays the same episode in the other version (Sub: Japanese audio with
    /// subtitles; Dub: English audio) from the same position, preferring a
    /// server with the current name. The Default Media Receiver cannot switch
    /// audio tracks inside a stream, so this is how Cast changes the audio.
    func switchKind(to kind: StreamKind) {
        guard kind != session.kind, !isChangingEpisode, !closed, session.downloadID == nil,
              let episode = resolver.series.episodes.first(where: { $0.number == session.historyEntry.episode }) else { return }
        guard episode.isAvailable(for: kind) else {
            errorMessage = String(localized: "El episodio \(episode.number) todavía no tiene versión \(kind.displayName).")
            return
        }
        if isCasting { saveCastProgress(force: true) } else { saveProgress() }
        player.pause()
        isChangingEpisode = true
        errorMessage = nil
        let current = session
        Task {
            defer { isChangingEpisode = false }
            do {
                let servers = try await resolver.servers(for: episode)
                guard let server = servers.first(where: { $0.kind == kind && $0.name == current.playback.serverName })
                    ?? servers.first(where: { $0.kind == kind }) else {
                    errorMessage = String(localized: "El episodio \(episode.number) todavía no tiene servidores \(kind.displayName).")
                    return
                }
                let next = try await resolver.session(for: episode, server: server)
                guard !closed else { return }
                load(next)
            } catch is CancellationError {
                return
            } catch {
                errorMessage = String(localized: "No se pudo cambiar a \(kind.displayName). \(error.localizedDescription)")
            }
        }
    }

    // MARK: Google Cast

    /// A Cast session started or resumed. After a resume the receiver may
    /// still be playing this episode: then nothing is reloaded.
    func castSessionStarted() {
        guard started, !closed, CastCenter.shared.isConnected else { return }
        if let castShare, CastCenter.shared.loadedURL == castShare.url { return }
        beginCasting()
    }

    /// The Cast session ended: the episode continues here, paused, from the
    /// receiver's last position.
    func castSessionEnded() {
        guard started, !closed, isCasting || isPreparingCast else { return }
        saveCastProgress(force: true)
        if let castShare { CastCenter.revoke(castShare, base: profile.relayURL) }
        castShare = nil
        castSubtitleID = nil
        keepNowPlaying(whileCasting: false)
        load(session, autoplay: false)
    }

    /// Mirrors the receiver's status: position, play state, progress saved
    /// for Continue, automatic intro/outro skipping.
    func followCast() {
        guard let castShare, !closed, !isChangingEpisode else { return }
        let cast = CastCenter.shared
        // Another phone replaced the media on the receiver.
        guard cast.loadedURL == nil || cast.loadedURL == castShare.url else { return }
        let playing = cast.state != .idle && cast.state != .paused
        if isPlaying != playing { isPlaying = playing }
        let buffering = cast.state == .buffering || cast.state == .loading
        if isBuffering != buffering { isBuffering = buffering }
        if cast.duration > 0, abs(cast.duration - duration) >= 0.5 { duration = cast.duration }
        let seconds = cast.position
        guard seconds.isFinite, seconds > 0, cast.state != .loading else { return }
        currentTime = seconds
        let preferences = profile.preferences
        if preferences.autoSkipIntro, let intro = session.playback.intro, intro.contains(seconds) {
            seek(to: intro.end)
            return
        }
        if preferences.autoSkipOutro, let outro = session.playback.outro, outro.contains(seconds) {
            seek(to: outro.end)
            return
        }
        saveCastProgress()
        updateNowPlaying()
    }

    /// The receiver played the episode to its end.
    func castFinished() {
        guard isCasting, !closed, !isChangingEpisode else { return }
        playbackEnded()
    }

    func castFailed(_ message: String) {
        guard isCasting || isPreparingCast, !closed else { return }
        errorMessage = message
        CastCenter.shared.clearFailure()
    }

    func selectCastSubtitle(_ track: CastSubtitle?) {
        CastCenter.shared.setSubtitle(track?.id)
        castSubtitleID = track?.id
        let choice = track?.language ?? UserPreferences.subtitlesOff
        if session.kind == .sub {
            var preferences = profile.preferences
            preferences.subtitleLanguage = choice
            profile.setPreferences(preferences)
        } else {
            dubSubtitleChoice = choice
        }
    }

    func stopCasting() {
        CastCenter.shared.disconnect()
    }

    /// Sends this episode to the connected Cast device. The relay makes a
    /// temporary link without the identity token; the receiver loads it with
    /// the subtitle files as side-loaded tracks, at `start` (by default the
    /// current position).
    private func beginCasting(at start: Double? = nil) {
        guard started, !closed, !isPreparingCast, CastCenter.shared.isConnected else { return }
        guard session.downloadID == nil else {
            errorMessage = String(localized: "Este episodio se reproduce desde una descarga. Para verlo con Google Cast, reprodúcelo en línea.")
            return
        }
        let position = start ?? currentTime
        if player.currentItem != nil { saveProgress() }
        stopLocalItem()
        isPreparingCast = true
        isBuffering = true
        errorMessage = nil
        let current = session
        Task {
            defer { isPreparingCast = false }
            do {
                let share: CastShare = try await RelayClient.post(base: profile.relayURL, path: "/api/cast", body: CastShareRequest(playbackUrl: current.playback.playbackUrl))
                guard !closed, session.id == current.id, CastCenter.shared.isConnected else {
                    CastCenter.revoke(share, base: profile.relayURL)
                    return
                }
                let subtitleID = castSubtitle(in: share)
                let entry = current.historyEntry
                guard CastCenter.shared.load(share, title: entry.seriesTitle, subtitle: "\(entry.episodeLabel) · \(current.kind.displayName)", imageURL: entry.imageUrl, startAt: position, subtitleID: subtitleID) else {
                    CastCenter.revoke(share, base: profile.relayURL)
                    load(current, autoplay: false)
                    return
                }
                if let previous = castShare { CastCenter.revoke(previous, base: profile.relayURL) }
                CastCenter.shared.remember(CastedEpisode(share: share, entry: current.historyEntry, kind: current.kind))
                castShare = share
                castSubtitleID = subtitleID
                currentTime = position
                lastSavedSecond = Int(position)
                keepNowPlaying(whileCasting: true)
                updateNowPlaying()
            } catch is CancellationError {
                return
            } catch let error as RelayRequestError where error.statusCode == 404 {
                // The relay forgot this playback (restart or 2 h expiry):
                // resolve it again; loading it casts it.
                guard !closed else { return }
                retry()
            } catch {
                guard !closed else { return }
                errorMessage = String(localized: "No se pudo enviar el episodio a Google Cast. \(error.localizedDescription)")
                load(current, autoplay: false)
            }
        }
    }

    /// The same subtitle rules as here: the remembered language for Sub
    /// (then the device languages, then the relay default), the choice of
    /// this session for Dub. Forced tracks are not offered.
    private func castSubtitle(in share: CastShare) -> Int? {
        let tracks = share.subtitles.filter { $0.forced != true }
        let choice = session.kind == .sub ? profile.preferences.subtitleLanguage : dubSubtitleChoice
        func matching(_ tag: String) -> CastSubtitle? {
            let wanted = tag.lowercased()
            return tracks.first { $0.language.lowercased() == wanted }
                ?? tracks.first { $0.language.lowercased().hasPrefix(wanted + "-") || wanted.hasPrefix($0.language.lowercased() + "-") }
        }
        if choice == UserPreferences.subtitlesOff { return nil }
        if !choice.isEmpty, let track = matching(choice) { return track.id }
        guard session.kind == .sub else { return nil }
        for language in Locale.preferredLanguages {
            if let track = matching(language) ?? matching(String(language.prefix(2))) { return track.id }
        }
        return (tracks.first { $0.isDefault == true } ?? matching("en") ?? tracks.first)?.id
    }

    /// Progress of the episode on the receiver, once per second.
    private func saveCastProgress(force: Bool = false) {
        let seconds = currentTime
        guard seconds.isFinite, seconds > 0 else { return }
        let second = Int(seconds)
        guard force || second != lastSavedSecond else { return }
        lastSavedSecond = second
        let finished = reachedEnd(at: seconds)
        profile.update(session.historyEntry, positionSeconds: seconds, durationSeconds: duration, completed: finished)
        Task { await profile.sync(force: force) }
    }

    /// The receiver already plays this episode, sent by this device before
    /// the player closed or the app was closed or updated: the player
    /// becomes its remote again where it is, without loading it again.
    private func takeOverCast(_ session: PlaybackSession) -> Bool {
        let cast = CastCenter.shared
        guard cast.hasMedia, let casted = cast.castedEpisode,
              casted.entry.watchUrl == session.historyEntry.watchUrl,
              casted.entry.episode == session.historyEntry.episode,
              casted.kind == session.kind else { return false }
        castShare = casted.share
        castSubtitleID = cast.activeTrackIDs.first
        currentTime = cast.position
        lastSavedSecond = Int(cast.position)
        keepNowPlaying(whileCasting: true)
        updateNowPlaying()
        followCast()
        return true
    }

    /// While casting, the local player is stopped, and iOS gives Now Playing
    /// (lock screen, Control Center, headphones) only to an app playing
    /// audio, so the episode disappeared from there; with AirPlay the local
    /// player keeps playing. A silent loop plays meanwhile, as for the TV
    /// (`TVNowPlaying`): the episode stays as Now Playing, its controls drive
    /// the receiver (the remote commands already route to Cast), and the app
    /// keeps running with the phone locked, so the Cast session is not
    /// suspended (`CastCenter.configure`).
    private func keepNowPlaying(whileCasting casting: Bool) {
        guard casting else {
            castSilence?.stop()
            castSilence = nil
            return
        }
        guard castSilence == nil else { return }
        AudioSession.setActive(true)
        let silence = try? AVAudioPlayer(data: TVNowPlaying.silentWAV)
        silence?.numberOfLoops = -1
        if silence?.play() == true { castSilence = silence }
    }

    /// Releases the local item so the phone does not stream the episode too.
    private func stopLocalItem() {
        loadWatchdog?.cancel()
        stopObservingItem()
        player.pause()
        player.replaceCurrentItem(with: nil)
    }

    /// Resolves the same episode and server again (fresh playback URL and CDN
    /// token) and resumes from the saved position.
    func retry() {
        guard !isChangingEpisode, !closed,
              let episode = resolver.series.episodes.first(where: { $0.number == session.historyEntry.episode }) else { return }
        saveProgress()
        player.pause()
        isChangingEpisode = true
        errorMessage = nil
        let current = session
        Task {
            defer { isChangingEpisode = false }
            do {
                let fresh = try await resolver.session(for: episode, preferredName: current.playback.serverName, kind: current.kind)
                guard !closed else { return }
                load(fresh)
            } catch is CancellationError {
                return
            } catch {
                errorMessage = String(localized: "No se pudo reintentar. \(error.localizedDescription)")
            }
        }
    }

    // MARK: Item

    private func load(_ session: PlaybackSession, autoplay: Bool = true) {
        stopObservingItem()
        loadWatchdog?.cancel()
        mediaOptionsRequested = false
        failureReported = false
        self.session = session
        didFinish = false
        errorMessage = nil
        currentTime = 0
        duration = 0
        isBuffering = true
        legibleGroup = nil
        audibleGroup = nil
        legibleOptions = []
        audibleOptions = []
        selectedLegible = nil
        selectedAudible = nil
        hasLoadedMediaOptions = false
        lastSavedSecond = -1
        guard let url = URL(string: session.playback.playbackUrl) else {
            errorMessage = String(localized: "La URL de reproducción no es válida.")
            return
        }
        profile.open(session.historyEntry)
        let resumeAt = profile.position(for: session.historyEntry.watchUrl, episode: session.historyEntry.episode)
        // With a Cast device connected the episode goes there (downloads
        // stay here); the phone does not load the stream.
        if CastCenter.shared.isConnected, session.downloadID == nil {
            // The previous item's clock is not this episode's position.
            stopLocalItem()
            if takeOverCast(session) { return }
            currentTime = resumeAt > 3 ? resumeAt : 0
            updateNowPlaying()
            beginCasting(at: currentTime)
            return
        }
        if let castShare {
            CastCenter.revoke(castShare, base: profile.relayURL)
            self.castShare = nil
            castSubtitleID = nil
        }
        let asset = AVURLAsset(url: url)
        // A download plays from its package; an incomplete one would need the
        // relay source it came from, which has expired by now.
        if session.downloadID != nil, let cache = asset.assetCache, !cache.isPlayableOffline {
            errorMessage = String(localized: "Esta descarga no se completó y no puede verse sin conexión. Bórrala y descárgala de nuevo.")
        }
        let item = AVPlayerItem(asset: asset)
        player.replaceCurrentItem(with: item)
        if resumeAt > 3 {
            currentTime = resumeAt
            player.seek(to: CMTime(seconds: resumeAt, preferredTimescale: 600))
        }
        observe(item)
        // Never spin forever: a server that does not answer becomes an error
        // with a retry button.
        loadWatchdog = Task { [weak self] in
            try? await Task.sleep(for: .seconds(25))
            guard !Task.isCancelled, let self, !self.closed, self.player.currentItem === item,
                  item.status != .readyToPlay, self.errorMessage == nil else { return }
            self.failureReported = true
            self.fail(self.session.downloadID == nil
                ? String(localized: "El servidor no respondió a tiempo. Reintenta o elige otro servidor.")
                : String(localized: "No se pudo abrir la descarga. Bórrala y descárgala de nuevo."))
        }
        updateNowPlaying()
        player.defaultRate = speed.rawValue
        if autoplay { player.play() }
    }

    private func observe(_ item: AVPlayerItem) {
        itemObservations = [
            refreshOnChange(of: item, \.status),
            refreshOnChange(of: item, \.duration),
        ]
        itemNotifications = observeEnd(of: item)
    }

    private func stopObservingItem() {
        itemObservations.forEach { $0.invalidate() }
        itemObservations = []
        itemNotifications.forEach { NotificationCenter.default.removeObserver($0) }
        itemNotifications = []
    }

    // MARK: State

    /// KVO can fire on any thread. Every change hops to the main queue in
    /// order and the whole state is re-read there, so no transition (such as
    /// readyToPlay arriving together with the duration) can be lost.
    nonisolated private func refreshOnChange<Object: NSObject, Value>(of object: Object, _ keyPath: KeyPath<Object, Value>) -> NSKeyValueObservation {
        object.observe(keyPath, options: [.initial, .new]) { [weak self] _, _ in
            self?.refreshOnMain()
        }
    }

    nonisolated private func refreshOnMain() {
        DispatchQueue.main.async {
            MainActor.assumeIsolated { self.refreshState() }
        }
    }

    nonisolated private func observeEnd(of item: AVPlayerItem) -> [NSObjectProtocol] {
        let center = NotificationCenter.default
        return [
            center.addObserver(forName: .AVPlayerItemDidPlayToEndTime, object: item, queue: .main) { [weak self] _ in
                MainActor.assumeIsolated { () -> Void in
                    self?.playbackEnded()
                }
            },
            center.addObserver(forName: .AVPlayerItemFailedToPlayToEndTime, object: item, queue: .main) { [weak self] notification in
                let message = (notification.userInfo?[AVPlayerItemFailedToPlayToEndTimeErrorKey] as? Error)?.localizedDescription
                MainActor.assumeIsolated { () -> Void in
                    self?.fail(message)
                }
            },
        ]
    }

    private func refreshState() {
        guard started, !closed, !isCasting, !isPreparingCast else { return }
        let status = player.timeControlStatus
        let playing = status != .paused
        let buffering = status == .waitingToPlayAtSpecifiedRate
        if isPlaying != playing { isPlaying = playing }
        if isBuffering != buffering { isBuffering = buffering }
        let external = player.isExternalPlaybackActive
        if isExternalPlayback != external {
            isExternalPlayback = external
            externalRouteName = external
                ? AVAudioSession.sharedInstance().currentRoute.outputs.first(where: { $0.portType == .airPlay })?.portName
                : nil
        }
        if let pictureInPicture {
            if canUsePictureInPicture != pictureInPicture.isPictureInPicturePossible { canUsePictureInPicture = pictureInPicture.isPictureInPicturePossible }
            if isPictureInPictureActive != pictureInPicture.isPictureInPictureActive { isPictureInPictureActive = pictureInPicture.isPictureInPictureActive }
        }
        refreshItem()
        updateNowPlaying()
    }

    /// Also runs on every time tick as a safety net.
    private func refreshItem() {
        guard let item = player.currentItem else { return }
        switch item.status {
        case .readyToPlay:
            updateDuration(from: item)
            if !mediaOptionsRequested {
                mediaOptionsRequested = true
                Task { await loadMediaOptions(for: item) }
            }
        case .failed:
            if !failureReported {
                failureReported = true
                fail(item.error?.localizedDescription)
            }
        default:
            break
        }
    }

    /// VOD playlists report a duration; otherwise the seekable range is used.
    private func updateDuration(from item: AVPlayerItem) {
        var seconds = item.duration.seconds
        if !seconds.isFinite || seconds <= 0, let range = item.seekableTimeRanges.last?.timeRangeValue {
            seconds = range.end.seconds
        }
        guard seconds.isFinite, seconds > 0, abs(seconds - duration) >= 0.5 else { return }
        duration = seconds
    }

    private func loadMediaOptions(for item: AVPlayerItem) async {
        let legible = try? await item.asset.loadMediaSelectionGroup(for: .legible)
        let audible = try? await item.asset.loadMediaSelectionGroup(for: .audible)
        guard player.currentItem === item else { return }
        legibleGroup = legible
        audibleGroup = audible
        legibleOptions = legible.map { AVMediaSelectionGroup.mediaSelectionOptions(from: $0.options, withoutMediaCharacteristics: [.containsOnlyForcedSubtitles]) } ?? []
        audibleOptions = audible?.options ?? []
        // Offline, only the subtitles stored in the download can play.
        if session.downloadID != nil, let legible, let cache = (item.asset as? AVURLAsset)?.assetCache {
            let stored = Set(cache.mediaSelectionOptions(in: legible))
            legibleOptions = legibleOptions.filter { stored.contains($0) }
        }
        applySubtitleChoice(to: item)
        refreshSelection()
        hasLoadedMediaOptions = true
    }

    /// Sub sources carry Japanese audio, so subtitles are switched on: the
    /// remembered language, otherwise the device languages, otherwise the
    /// relay default. Dub sources keep AVPlayer's automatic choice unless the
    /// user picked something during this session.
    private func applySubtitleChoice(to item: AVPlayerItem) {
        guard let group = legibleGroup else { return }
        let choice = session.kind == .sub ? profile.preferences.subtitleLanguage : dubSubtitleChoice
        if choice == UserPreferences.subtitlesOff {
            item.select(nil, in: group)
        } else if !choice.isEmpty, let option = legibleOption(matching: choice) {
            item.select(option, in: group)
        } else if session.kind == .sub, let option = automaticSubtitle() {
            item.select(option, in: group)
        }
    }

    private func legibleOption(matching tag: String) -> AVMediaSelectionOption? {
        let wanted = tag.lowercased()
        if let exact = legibleOptions.first(where: { $0.extendedLanguageTag?.lowercased() == wanted }) { return exact }
        return legibleOptions.first { option in
            guard let language = option.extendedLanguageTag?.lowercased() else { return false }
            return language.hasPrefix(wanted + "-") || wanted.hasPrefix(language + "-")
        }
    }

    private func automaticSubtitle() -> AVMediaSelectionOption? {
        let ranked = AVMediaSelectionGroup.mediaSelectionOptions(from: legibleOptions, filteredAndSortedAccordingToPreferredLanguages: Locale.preferredLanguages)
        return ranked.first
            ?? legibleOptions.first(where: { $0 == legibleGroup?.defaultOption })
            ?? legibleOption(matching: "en")
            ?? legibleOptions.first
    }

    private func refreshSelection() {
        guard let item = player.currentItem else { return }
        selectedLegible = legibleGroup.flatMap { item.currentMediaSelection.selectedMediaOption(in: $0) }
        selectedAudible = audibleGroup.flatMap { item.currentMediaSelection.selectedMediaOption(in: $0) }
    }

    private func playbackEnded() {
        saveProgress(completed: true)
        if !hasNext {
            reachedSeriesEnd = true
        } else if profile.preferences.autoNext {
            changeEpisode(by: 1)
        } else {
            didFinish = true
        }
    }

    /// A finished episode's download (streamed or not) becomes eligible for
    /// automatic deletion.
    private func markDownloadWatched() {
        DownloadStore.shared.markWatched(watchUrl: session.historyEntry.watchUrl, episode: session.historyEntry.episode)
    }

    /// Watched once the outro starts or within the last stretch of the
    /// episode, so Continue offers the next episode instead of the credits.
    private func reachedEnd(at seconds: Double) -> Bool {
        if let outro = session.playback.outro, outro.start > 60, seconds >= outro.start { return true }
        guard duration > 0 else { return false }
        return seconds >= duration - min(120, duration * 0.1)
    }

    private func fail(_ message: String?) {
        isBuffering = false
        if session.downloadID == nil, !NetworkStatus.shared.isOnline {
            errorMessage = String(localized: "Sin conexión. Este episodio no está descargado; descárgalo para verlo sin red.")
        } else {
            errorMessage = message ?? String(localized: "No se pudo reproducir este servidor.")
        }
    }

    // MARK: Player observation

    private func observePlayer() {
        // `+=`: attach(_:) may already have added the PiP observations.
        playerObservations += [
            refreshOnChange(of: player, \.timeControlStatus),
            refreshOnChange(of: player, \.isExternalPlaybackActive),
        ]
        timeObserver = player.addPeriodicTimeObserver(forInterval: CMTime(seconds: 0.5, preferredTimescale: 600), queue: .main) { [weak self] time in
            MainActor.assumeIsolated { () -> Void in
                self?.tick(time.seconds)
            }
        }
    }

    private func tick(_ seconds: Double) {
        guard seconds.isFinite, seconds >= 0, !isChangingEpisode, !closed, !isCasting, !isPreparingCast else { return }
        currentTime = seconds
        refreshItem()
        let preferences = profile.preferences
        if preferences.autoSkipIntro, let intro = session.playback.intro, intro.contains(seconds) {
            seek(to: intro.end)
            return
        }
        if preferences.autoSkipOutro, let outro = session.playback.outro, outro.contains(seconds) {
            seek(to: outro.end)
            return
        }
        let second = Int(seconds)
        guard second != lastSavedSecond, seconds > 0 else { return }
        lastSavedSecond = second
        let finished = reachedEnd(at: seconds)
        profile.update(session.historyEntry, positionSeconds: seconds, durationSeconds: duration, completed: finished)
        if finished { markDownloadWatched() }
        Task { await profile.sync() }
    }

    private func saveProgress(completed: Bool? = nil) {
        let seconds = player.currentTime().seconds
        guard seconds.isFinite, seconds > 0, player.currentItem != nil else { return }
        let finished = completed ?? reachedEnd(at: seconds)
        profile.update(session.historyEntry, positionSeconds: seconds, durationSeconds: duration, completed: finished)
        if finished { markDownloadWatched() }
        Task { await profile.sync(force: true) }
    }

    // MARK: Now Playing and remote commands

    private func updateNowPlaying() {
        guard started, !closed else { return }
        let entry = session.historyEntry
        var info: [String: Any] = [
            MPMediaItemPropertyTitle: entry.episodeLabel,
            MPMediaItemPropertyArtist: entry.seriesTitle,
            MPNowPlayingInfoPropertyMediaType: MPNowPlayingInfoMediaType.video.rawValue,
            MPNowPlayingInfoPropertyElapsedPlaybackTime: currentTime,
            MPNowPlayingInfoPropertyPlaybackRate: isPlaying ? Double(speed.rawValue) : 0.0,
            MPNowPlayingInfoPropertyDefaultPlaybackRate: Double(speed.rawValue),
        ]
        if duration > 0 {
            info[MPMediaItemPropertyPlaybackDuration] = duration
        }
        // The series' poster on the lock screen, in Control Center and on
        // the AirPlay or Cast controls (here, while casting or after taking
        // over a cast).
        if let imageUrl = entry.imageUrl {
            if let artwork, artwork.url == imageUrl {
                info[MPMediaItemPropertyArtwork] = artwork.image
            } else {
                loadArtwork(imageUrl)
            }
        }
        MPNowPlayingInfoCenter.default().nowPlayingInfo = info
    }

    private func loadArtwork(_ imageUrl: String) {
        guard loadingArtwork != imageUrl, let url = URL(string: imageUrl) else { return }
        loadingArtwork = imageUrl
        Task {
            defer { loadingArtwork = nil }
            guard let (data, _) = try? await URLSession.shared.data(from: url), let image = UIImage(data: data) else { return }
            artwork = (imageUrl, MPMediaItemArtwork(boundsSize: image.size) { _ in image })
            updateNowPlaying()
        }
    }

    private func registerRemoteCommands() {
        let center = MPRemoteCommandCenter.shared()
        center.skipForwardCommand.preferredIntervals = [10]
        center.skipBackwardCommand.preferredIntervals = [10]
        addRemoteCommand(center.playCommand) { $0.play() }
        addRemoteCommand(center.pauseCommand) { $0.pause() }
        addRemoteCommand(center.togglePlayPauseCommand) { $0.togglePlayback() }
        addRemoteCommand(center.skipForwardCommand) { $0.skip(by: 10) }
        addRemoteCommand(center.skipBackwardCommand) { $0.skip(by: -10) }
        addRemoteCommand(center.nextTrackCommand) { $0.changeEpisode(by: 1) }
        addRemoteCommand(center.previousTrackCommand) { $0.changeEpisode(by: -1) }
        center.changePlaybackPositionCommand.isEnabled = true
        let target = center.changePlaybackPositionCommand.addTarget { [weak self] event in
            guard let event = event as? MPChangePlaybackPositionCommandEvent else { return .commandFailed }
            let position = event.positionTime
            MainActor.assumeIsolated { () -> Void in
                self?.seek(to: position)
            }
            return .success
        }
        remoteCommandTargets.append((center.changePlaybackPositionCommand, target))
    }

    /// Remote commands arrive on the main thread.
    private func addRemoteCommand(_ command: MPRemoteCommand, action: @escaping (PlayerModel) -> Void) {
        command.isEnabled = true
        let target = command.addTarget { [weak self] _ in
            MainActor.assumeIsolated { () -> Void in
                if let self { action(self) }
            }
            return .success
        }
        remoteCommandTargets.append((command, target))
    }

    private func unregisterRemoteCommands() {
        for (command, target) in remoteCommandTargets {
            command.removeTarget(target)
        }
        remoteCommandTargets = []
    }
}

/// The episode open in the player of a tab or sheet.
@MainActor
@Observable
final class PlayerPresenter {
    var model: PlayerModel?
}

/// Presents the player above the content it modifies (the tabs, or a sheet)
/// instead of above a series screen: rotating or folding can rebuild that
/// screen (one column becomes two), which would close a player presented
/// from it and stop the episode.
struct PlayerHost: ViewModifier {
    @Environment(DownloadStore.self) private var downloads
    @Environment(ProfileStore.self) private var profile
    @State private var presenter = PlayerPresenter()

    func body(content: Content) -> some View {
        @Bindable var presenter = presenter
        content
            .environment(presenter)
            .fullScreenCover(item: $presenter.model, onDismiss: { downloads.cleanUpWatched(profile: profile) }) { model in
                PlayerScreen(model: model)
            }
    }
}

struct PlayerScreen: View {
    private enum Level { case brightness, volume }
    private enum EdgeDrag { case brightness(start: Double), volume(start: Float), ignored }
    private struct SeekFeedback: Equatable {
        let forward: Bool
        let seconds: Int
    }

    let model: PlayerModel
    @Environment(\.dismiss) private var dismiss
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    @Environment(\.verticalSizeClass) private var verticalSizeClass
    @State private var controlsVisible = true
    @State private var hideTask: Task<Void, Never>?
    @State private var scrubbing = false
    @State private var scrubValue: Double = 0
    @State private var showMediaOptions = false
    @State private var volume = SystemVolume()
    @State private var brightness: Double = 0.5
    @State private var originalBrightness: Double?
    @State private var adjusting: Level?
    @State private var levelHideTask: Task<Void, Never>?
    @State private var edgeDrag: EdgeDrag?
    @State private var seekFeedback: SeekFeedback?
    @State private var seekFeedbackTask: Task<Void, Never>?
    @FocusState private var keyboardFocused: Bool
    /// The hinge is half open (iOS 27.1+, `UIHingeInteraction`).
    @State private var hingePartiallyOpen = false
    /// A fold across the screen, in full-screen coordinates.
    @State private var horizontalFold: CGRect?

    private var entry: WatchHistoryEntry { model.session.historyEntry }
    private var cast: CastCenter { CastCenter.shared }
    private var compact: Bool { horizontalSizeClass == .compact }
    /// iPhone in portrait has no room for the side bars next to the transport
    /// buttons; there they only appear while adjusting.
    private var showsSideBars: Bool { !(compact && verticalSizeClass == .regular) }

    /// Tabletop posture: a foldable half open with the fold across the
    /// screen. The video takes the upper half and the controls stay on the
    /// lower one, nothing on the fold (as on Android). Not while casting or
    /// on AirPlay, where the video is elsewhere.
    private var tabletopFold: CGRect? {
        guard hingePartiallyOpen, !model.isCasting, !model.isPreparingCast, !model.isExternalPlayback else { return nil }
        return horizontalFold
    }

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()

            // The video fills the screen (or the half above the fold); the
            // controls float above it and never resize the layer. The same
            // view in both layouts, so the layer and picture in picture stay.
            VideoSurface(model: model, gravity: model.videoGravity)
                .frame(height: tabletopFold?.minY)
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
                .ignoresSafeArea()
                .accessibilityHidden(true)

            if model.isCasting || model.isPreparingCast {
                castCard
            } else if model.isExternalPlayback {
                externalPlaybackCard
            }

            gestureLayer

            if (model.isBuffering && model.isPlaying) || model.isChangingEpisode {
                ProgressView()
                    .controlSize(.large)
                    .tint(.white)
                    .frame(height: tabletopFold?.minY)
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: tabletopFold == nil ? .center : .top)
                    .ignoresSafeArea()
                    .allowsHitTesting(false)
            }

            if let fold = tabletopFold {
                tabletopPanel
                    .padding(.top, fold.maxY + 16)
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
                    .ignoresSafeArea(edges: .top)
            } else if controlsVisible {
                controls
                    .transition(.opacity)
            }

            if let fold = tabletopFold {
                sideBars
                    .frame(height: fold.minY)
                    .frame(maxHeight: .infinity, alignment: .top)
                    .ignoresSafeArea(edges: .top)
            } else {
                sideBars
            }

            seekFeedbackBubble

            skipButton

            if let message = model.errorMessage {
                errorCard(message)
            }
        }
        .environment(\.colorScheme, .dark)
        // Where the fold is, and whether the hinge is half open.
        .background {
            GeometryReader { proxy in
                Color.clear
                    .onAppear { horizontalFold = Self.horizontalFold(in: proxy) }
                    .onChange(of: proxy.size) { _, _ in horizontalFold = Self.horizontalFold(in: proxy) }
                    .onChange(of: hingePartiallyOpen) { _, _ in horizontalFold = Self.horizontalFold(in: proxy) }
            }
            .ignoresSafeArea()
            HingeObserver { hingePartiallyOpen = $0 }
                .frame(width: 1, height: 1)
                .allowsHitTesting(false)
                .accessibilityHidden(true)
        }
        .animation(.easeInOut(duration: 0.25), value: tabletopFold)
        .statusBarHidden(!controlsVisible)
        .persistentSystemOverlays(controlsVisible ? .automatic : .hidden)
        // While this view is on screen iOS does not draw its own volume HUD;
        // the volume bar replaces it.
        .background {
            VolumeViewHost(view: volume.view)
                .frame(width: 1, height: 1)
                .allowsHitTesting(false)
                .accessibilityHidden(true)
        }
        .focusable()
        .focused($keyboardFocused)
        .focusEffectDisabled()
        .onKeyPress(keys: [.space, .leftArrow, .rightArrow, .upArrow, .downArrow]) { press in
            handleKey(press.key)
        }
        .onAppear {
            model.start()
            volume.start()
            brightness = Double(ScreenBrightness.level)
            keyboardFocused = true
            scheduleAutoHide()
        }
        .onDisappear {
            hideTask?.cancel()
            levelHideTask?.cancel()
            seekFeedbackTask?.cancel()
            volume.stop()
            if let originalBrightness { ScreenBrightness.set(originalBrightness) }
            model.close()
        }
        .onChange(of: volume.externalChange) { _, _ in
            showLevel(.volume)
        }
        .onChange(of: model.reachedSeriesEnd) { _, ended in
            if ended { close() }
        }
        .onChange(of: showMediaOptions) { _, showing in
            if !showing { keyboardFocused = true }
        }
        .onChange(of: model.isPlaying) { _, playing in
            if playing { scheduleAutoHide() } else { showControls(autoHide: false) }
        }
        .onChange(of: model.currentTime) { _, time in
            if !scrubbing { scrubValue = time }
        }
        .onChange(of: model.didFinish) { _, finished in
            if finished { showControls(autoHide: false) }
        }
        .onChange(of: cast.sessionGeneration) { _, _ in model.castSessionStarted() }
        .onChange(of: cast.endedGeneration) { _, _ in model.castSessionEnded() }
        .onChange(of: cast.position) { _, _ in model.followCast() }
        .onChange(of: cast.state) { _, _ in model.followCast() }
        .onChange(of: cast.finishedCount) { _, _ in model.castFinished() }
        .onChange(of: cast.failure) { _, failure in
            if let failure { model.castFailed(failure) }
        }
        .onChange(of: model.isCasting) { _, casting in
            // The receiver is controlled from here: keep the controls up.
            if casting { showControls(autoHide: false) }
        }
        .sheet(isPresented: $showMediaOptions, onDismiss: scheduleAutoHide) {
            MediaOptionsSheet(model: model)
        }
    }

    /// A fold across the screen (wider than tall) with room on both sides,
    /// from iOS 27.1's reserved regions; nil on other devices and versions.
    private static func horizontalFold(in proxy: GeometryProxy) -> CGRect? {
        if #available(iOS 27.1, *) {
            let height = proxy.size.height
            return proxy.reservedRegions(kind: .division, options: .includeInactive)
                .map(\.frame)
                .first { $0.width > $0.height && $0.minY >= 160 && height - $0.maxY >= 160 }
        }
        return nil
    }

    /// The controls below the fold in tabletop posture: always shown, as in
    /// the full-screen layout but stacked.
    private var tabletopPanel: some View {
        VStack(spacing: 20) {
            topBar
            transport
            bottomBar
            // Brightness and volume side by side (the drags on the video
            // still work; here they can be seen and set directly).
            HStack(spacing: 14) {
                Image(systemName: brightness < 0.5 ? "sun.min.fill" : "sun.max.fill")
                    .foregroundStyle(.white)
                    .accessibilityHidden(true)
                Slider(value: Binding(get: { brightness }, set: { setBrightness($0) }), in: 0...1, onEditingChanged: { editingLevel($0) })
                    .accessibilityLabel("Brillo")
                Image(systemName: volumeSymbol)
                    .foregroundStyle(.white)
                    .padding(.leading, 12)
                    .accessibilityHidden(true)
                Slider(value: Binding(get: { Double(volume.level) }, set: { volume.set(Float($0)) }), in: 0...1, onEditingChanged: { editingLevel($0) })
                    .accessibilityLabel("Volumen")
            }
            .tint(.white)
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 28)
        .padding(.bottom, 12)
    }

    // MARK: Gestures and keyboard

    /// Tap toggles the controls, a double tap on either half seeks 10 s, a
    /// vertical swipe on the left/right half changes brightness/volume and a
    /// pinch switches between fit and fill.
    private var gestureLayer: some View {
        GeometryReader { proxy in
            Color.clear
                .contentShape(Rectangle())
                .onTapGesture(count: 2, coordinateSpace: LocalCoordinateSpace.local) { location in
                    seek(forward: location.x > proxy.size.width / 2)
                }
                .onTapGesture(perform: toggleControls)
                .gesture(edgeDragGesture(in: proxy.size))
                .simultaneousGesture(MagnifyGesture().onEnded { value in
                    withAnimation(.easeInOut(duration: 0.25)) {
                        model.videoGravity = value.magnification > 1 ? .resizeAspectFill : .resizeAspect
                    }
                })
        }
        // Over the video only (the half above the fold in tabletop posture).
        .frame(height: tabletopFold?.minY)
        .frame(maxHeight: .infinity, alignment: .top)
        .ignoresSafeArea()
        .accessibilityLabel(controlsVisible ? Text("Ocultar controles") : Text("Mostrar controles"))
        .accessibilityAddTraits(.isButton)
        .accessibilityAction { toggleControls() }
    }

    private func edgeDragGesture(in size: CGSize) -> some Gesture {
        DragGesture(minimumDistance: 16)
            .onChanged { drag in
                if edgeDrag == nil {
                    let vertical = abs(drag.translation.height) > abs(drag.translation.width)
                    if !vertical {
                        edgeDrag = .ignored
                    } else if drag.startLocation.x < size.width / 2 {
                        edgeDrag = .brightness(start: brightness)
                    } else {
                        edgeDrag = .volume(start: volume.level)
                    }
                }
                let delta = -drag.translation.height / max(size.height * 0.7, 1)
                switch edgeDrag ?? .ignored {
                case .brightness(let start):
                    setBrightness(start + delta)
                case .volume(let start):
                    volume.set(start + Float(delta))
                    showLevel(.volume)
                default:
                    break
                }
            }
            .onEnded { _ in edgeDrag = nil }
    }

    private func handleKey(_ key: KeyEquivalent) -> KeyPress.Result {
        switch key {
        case .space:
            model.togglePlayback()
            bumpControls()
        case .leftArrow:
            seek(forward: false)
        case .rightArrow:
            seek(forward: true)
        case .upArrow:
            volume.change(by: 1 / 16)
            showLevel(.volume)
        case .downArrow:
            volume.change(by: -1 / 16)
            showLevel(.volume)
        default:
            return .ignored
        }
        return .handled
    }

    /// Seeks 10 s and shows the accumulated jump on that side of the screen.
    private func seek(forward: Bool) {
        model.skip(by: forward ? 10 : -10)
        let previous = seekFeedback?.forward == forward ? seekFeedback?.seconds ?? 0 : 0
        withAnimation(.easeOut(duration: 0.15)) {
            seekFeedback = SeekFeedback(forward: forward, seconds: previous + 10)
        }
        seekFeedbackTask?.cancel()
        seekFeedbackTask = Task {
            try? await Task.sleep(for: .seconds(0.9))
            guard !Task.isCancelled else { return }
            withAnimation(.easeOut(duration: 0.25)) { seekFeedback = nil }
        }
    }

    private func setBrightness(_ value: Double) {
        if originalBrightness == nil { originalBrightness = Double(ScreenBrightness.level) }
        brightness = min(max(value, 0), 1)
        ScreenBrightness.set(brightness)
        showLevel(.brightness)
    }

    /// Shows one side bar for a moment even while the controls are hidden.
    private func showLevel(_ level: Level) {
        if adjusting != level {
            withAnimation(.easeOut(duration: 0.15)) { adjusting = level }
        }
        levelHideTask?.cancel()
        levelHideTask = Task {
            try? await Task.sleep(for: .seconds(1.2))
            guard !Task.isCancelled else { return }
            withAnimation(.easeOut(duration: 0.3)) { adjusting = nil }
        }
    }

    private var sideBars: some View {
        HStack {
            if adjusting == .brightness || (controlsVisible && showsSideBars && tabletopFold == nil) {
                LevelBar(
                    title: String(localized: "Brillo"),
                    systemImage: brightness < 0.5 ? "sun.min.fill" : "sun.max.fill",
                    value: brightness,
                    onChange: setBrightness,
                    onEditing: editingLevel
                )
                .transition(.opacity)
            }
            Spacer(minLength: 0)
            if adjusting == .volume || (controlsVisible && showsSideBars && tabletopFold == nil) {
                LevelBar(
                    title: String(localized: "Volumen"),
                    systemImage: volumeSymbol,
                    value: Double(volume.level),
                    onChange: { volume.set(Float($0)); showLevel(.volume) },
                    onEditing: editingLevel
                )
                .transition(.opacity)
            }
        }
        .padding(.horizontal, compact ? 16 : 28)
    }

    private var volumeSymbol: String {
        switch volume.level {
        case 0: return "speaker.slash.fill"
        case ..<0.34: return "speaker.wave.1.fill"
        case ..<0.67: return "speaker.wave.2.fill"
        default: return "speaker.wave.3.fill"
        }
    }

    /// Dragging a bar keeps the controls up until it is released.
    private func editingLevel(_ editing: Bool) {
        if editing { hideTask?.cancel() } else { scheduleAutoHide() }
    }

    @ViewBuilder
    private var seekFeedbackBubble: some View {
        if let seekFeedback {
            HStack {
                if seekFeedback.forward { Spacer() }
                Label("\(seekFeedback.seconds) s", systemImage: seekFeedback.forward ? "goforward" : "gobackward")
                    .font(.headline.monospacedDigit())
                    .foregroundStyle(.white)
                    .padding(.horizontal, 18)
                    .padding(.vertical, 12)
                    .glassEffect(.regular, in: .capsule)
                if !seekFeedback.forward { Spacer() }
            }
            .padding(.horizontal, compact ? 72 : 120)
            .allowsHitTesting(false)
            .transition(.opacity)
        }
    }

    // MARK: Controls

    private var controls: some View {
        ZStack {
            LinearGradient(colors: [.black.opacity(0.65), .clear, .clear, .black.opacity(0.75)], startPoint: .top, endPoint: .bottom)
                .ignoresSafeArea()
                .allowsHitTesting(false)
            VStack(spacing: 0) {
                topBar
                Spacer(minLength: 12)
                transport
                Spacer(minLength: 12)
                bottomBar
            }
            .padding(.horizontal, compact ? 16 : 28)
            .padding(.vertical, 12)
        }
    }

    private var topBar: some View {
        HStack(spacing: 12) {
            Button(action: close) {
                GlassIcon(systemName: "xmark")
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Cerrar")

            VStack(alignment: .leading, spacing: 2) {
                Text(entry.seriesTitle)
                    .font(.headline)
                    .lineLimit(1)
                Text("\(entry.episodeLabel) · \(model.session.kind.displayName)")
                    .font(.subheadline)
                    .foregroundStyle(.white.opacity(0.75))
                    .lineLimit(1)
            }
            .foregroundStyle(.white)
            .shadow(radius: 4)

            Spacer(minLength: 8)

            GlassEffectContainer(spacing: 10) {
                HStack(spacing: 10) {
                    if model.canUsePictureInPicture || model.isPictureInPictureActive {
                        Button {
                            model.togglePictureInPicture()
                            bumpControls()
                        } label: {
                            GlassIcon(systemName: model.isPictureInPictureActive ? "pip.exit" : "pip.enter")
                        }
                        .buttonStyle(.plain)
                        .accessibilityLabel("Imagen dentro de imagen")
                    }
                    // Only with a Cast device on this Wi-Fi, or a session open.
                    if cast.devicesAvailable || cast.isConnected {
                        CastButton()
                            .frame(width: 44, height: 44)
                            .glassEffect(.regular.interactive(), in: .circle)
                            .accessibilityLabel("Google Cast")
                    }
                    RoutePickerButton()
                        .frame(width: 44, height: 44)
                        .glassEffect(.regular.interactive(), in: .circle)
                        .accessibilityLabel("AirPlay")
                }
            }
        }
    }

    private var transport: some View {
        GlassEffectContainer(spacing: compact ? 16 : 28) {
            HStack(spacing: compact ? 16 : 28) {
                Button {
                    model.changeEpisode(by: -1)
                    bumpControls()
                } label: {
                    GlassIcon(systemName: "backward.end.fill", size: compact ? 40 : 48, font: .body)
                }
                .buttonStyle(.plain)
                .disabled(!model.hasPrevious || model.isChangingEpisode)
                .opacity(model.hasPrevious ? 1 : 0.4)
                .accessibilityLabel("Episodio anterior")

                Button {
                    model.skip(by: -10)
                    bumpControls()
                } label: {
                    GlassIcon(systemName: "gobackward.10", size: compact ? 52 : 60, font: .title2)
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Retroceder 10 segundos")

                Button {
                    model.togglePlayback()
                    bumpControls()
                } label: {
                    GlassIcon(systemName: model.isPlaying ? "pause.fill" : "play.fill", size: compact ? 68 : 80, font: .largeTitle)
                }
                .buttonStyle(.plain)
                .accessibilityLabel(model.isPlaying ? Text("Pausar") : Text("Reproducir"))

                Button {
                    model.skip(by: 10)
                    bumpControls()
                } label: {
                    GlassIcon(systemName: "goforward.10", size: compact ? 52 : 60, font: .title2)
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Adelantar 10 segundos")

                Button {
                    model.changeEpisode(by: 1)
                    bumpControls()
                } label: {
                    GlassIcon(systemName: "forward.end.fill", size: compact ? 40 : 48, font: .body)
                }
                .buttonStyle(.plain)
                .disabled(!model.hasNext || model.isChangingEpisode)
                .opacity(model.hasNext ? 1 : 0.4)
                .accessibilityLabel("Siguiente episodio")
            }
        }
    }

    private var bottomBar: some View {
        VStack(spacing: 12) {
            HStack(spacing: 12) {
                Text(formatTime(scrubbing ? scrubValue : model.currentTime))
                Slider(value: $scrubValue, in: 0...max(model.duration, 1), onEditingChanged: { editing in
                    scrubbing = editing
                    if editing {
                        hideTask?.cancel()
                    } else {
                        model.seek(to: scrubValue)
                        scheduleAutoHide()
                    }
                })
                .disabled(model.duration <= 0)
                .accessibilityLabel("Posición")
                Text("-" + formatTime(max(0, model.duration - (scrubbing ? scrubValue : model.currentTime))))
            }
            .font(.footnote.weight(.medium).monospacedDigit())
            .foregroundStyle(.white)

            if model.isCasting {
                castVolumeBar
            }

            HStack {
                Spacer()
                GlassEffectContainer(spacing: 10) {
                    HStack(spacing: 10) {
                        mediaOptionsButton
                        Button {
                            withAnimation(.easeInOut(duration: 0.25)) {
                                model.videoGravity = model.videoGravity == .resizeAspect ? .resizeAspectFill : .resizeAspect
                            }
                            bumpControls()
                        } label: {
                            GlassIcon(systemName: model.videoGravity == .resizeAspect ? "arrow.up.left.and.arrow.down.right" : "arrow.down.right.and.arrow.up.left")
                        }
                        .buttonStyle(.plain)
                        .accessibilityLabel(model.videoGravity == .resizeAspect ? Text("Llenar pantalla") : Text("Ajustar a pantalla"))
                    }
                }
            }
        }
    }

    /// Opens a sheet rather than a menu: it is always tappable (even before
    /// the tracks load) and auto-hiding the controls cannot dismiss it.
    private var mediaOptionsButton: some View {
        Button {
            hideTask?.cancel()
            showMediaOptions = true
        } label: {
            GlassIcon(systemName: (model.isCasting ? model.castSubtitleID == nil : model.selectedLegible == nil) ? "captions.bubble" : "captions.bubble.fill")
        }
        .buttonStyle(.plain)
        .accessibilityLabel("Subtítulos, audio y velocidad")
    }

    @ViewBuilder
    private var skipButton: some View {
        if let skip = model.activeSkip {
            VStack {
                Spacer()
                HStack {
                    Spacer()
                    Button {
                        model.seek(to: skip.end)
                        bumpControls()
                    } label: {
                        Label(skip.title, systemImage: "forward.fill")
                            .font(.headline)
                            .padding(.horizontal, 6)
                            .padding(.vertical, 4)
                    }
                    .buttonStyle(.glass)
                    .controlSize(.large)
                }
            }
            .padding(.horizontal, compact ? 16 : 28)
            .padding(.bottom, controlsVisible ? 128 : 28)
            .animation(.easeInOut(duration: 0.2), value: controlsVisible)
            .transition(.opacity)
        }
    }

    private var externalPlaybackCard: some View {
        VStack(spacing: 12) {
            Image(systemName: "airplayvideo")
                .font(.system(size: 56, weight: .regular))
            Text("Reproduciendo en \(model.externalRouteName ?? "AirPlay")")
                .font(.title3.weight(.semibold))
            Text("Controla la reproducción desde aquí o con el control del TV.")
                .font(.subheadline)
                .foregroundStyle(.white.opacity(0.7))
        }
        .multilineTextAlignment(.center)
        .foregroundStyle(.white)
        .padding(32)
        .allowsHitTesting(false)
    }

    private var castCard: some View {
        VStack(spacing: 12) {
            Image(systemName: "tv.and.mediabox")
                .font(.system(size: 56, weight: .regular))
            if model.isPreparingCast {
                Text("Enviando a \(cast.deviceName ?? "Google Cast")…")
                    .font(.title3.weight(.semibold))
            } else {
                Text("Transmitiendo a \(cast.deviceName ?? "Google Cast")")
                    .font(.title3.weight(.semibold))
                Text("Controla la reproducción, los subtítulos, la versión Sub/Dub y el volumen desde aquí.")
                    .font(.subheadline)
                    .foregroundStyle(.white.opacity(0.7))
            }
        }
        .multilineTextAlignment(.center)
        .foregroundStyle(.white)
        .padding(32)
        .allowsHitTesting(false)
    }

    /// Volume of the Cast device and the way back to this phone.
    private var castVolumeBar: some View {
        HStack(spacing: 12) {
            Image(systemName: "speaker.fill")
            Slider(value: Binding(get: { Double(cast.volume) }, set: { cast.setVolume(Float($0)) }), in: 0...1)
                .accessibilityLabel("Volumen de \(cast.deviceName ?? "Google Cast")")
            Image(systemName: "speaker.wave.3.fill")
            Button("Dejar de transmitir", systemImage: "stop.circle") { model.stopCasting() }
                .buttonStyle(.glass)
                .labelStyle(.titleAndIcon)
        }
        .font(.footnote.weight(.medium))
        .foregroundStyle(.white)
    }

    private func errorCard(_ message: String) -> some View {
        VStack(spacing: 14) {
            Label(message, systemImage: "exclamationmark.triangle.fill")
                .multilineTextAlignment(.center)
            HStack(spacing: 12) {
                Button("Cerrar reproductor", action: close)
                    .buttonStyle(.glass)
                Button("Reintentar") { model.retry() }
                    .buttonStyle(.glassProminent)
            }
            Button("Ocultar aviso") { model.dismissError() }
                .font(.footnote)
                .foregroundStyle(.white.opacity(0.8))
        }
        .foregroundStyle(.white)
        .padding(24)
        .glassEffect(.regular, in: .rect(cornerRadius: 24))
        .padding(32)
    }

    // MARK: Visibility

    private func close() {
        hideTask?.cancel()
        model.close()
        dismiss()
    }

    private func toggleControls() {
        if controlsVisible {
            hideTask?.cancel()
            withAnimation(.easeOut(duration: 0.2)) { controlsVisible = false }
        } else {
            showControls(autoHide: true)
        }
    }

    private func bumpControls() {
        showControls(autoHide: true)
    }

    private func showControls(autoHide: Bool) {
        if !controlsVisible {
            withAnimation(.easeOut(duration: 0.2)) { controlsVisible = true }
        }
        if autoHide { scheduleAutoHide() } else { hideTask?.cancel() }
    }

    private func scheduleAutoHide() {
        hideTask?.cancel()
        guard model.isPlaying, !model.isCasting, !scrubbing, !showMediaOptions else { return }
        hideTask = Task {
            try? await Task.sleep(for: .seconds(4))
            guard !Task.isCancelled, model.isPlaying, !scrubbing, !showMediaOptions else { return }
            withAnimation(.easeOut(duration: 0.3)) { controlsVisible = false }
        }
    }
}

private struct MediaOptionsSheet: View {
    let model: PlayerModel
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                if let share = model.castShare {
                    Section("Subtítulos") {
                        option(String(localized: "Desactivados"), selected: model.castSubtitleID == nil) { model.selectCastSubtitle(nil) }
                        ForEach(share.subtitles.filter { $0.forced != true }) { track in
                            option(track.name, selected: model.castSubtitleID == track.id) { model.selectCastSubtitle(track) }
                        }
                        if share.subtitles.isEmpty {
                            Text("Este servidor no publicó subtítulos.")
                                .foregroundStyle(.secondary)
                        }
                    }
                } else {
                    Section("Subtítulos") {
                        option(String(localized: "Desactivados"), selected: model.selectedLegible == nil) { model.selectLegible(nil) }
                        ForEach(model.legibleOptions, id: \.self) { track in
                            option(track.displayName, selected: model.selectedLegible == track) { model.selectLegible(track) }
                        }
                        if model.legibleOptions.isEmpty {
                            (model.hasLoadedMediaOptions ? Text("Este servidor no publicó subtítulos.") : Text("Cargando pistas…"))
                                .foregroundStyle(.secondary)
                        }
                    }
                }
                if model.session.downloadID == nil {
                    Section {
                        ForEach(StreamKind.allCases) { kind in
                            option("\(kind.displayName) · \(kind.detail)", selected: model.session.kind == kind) { model.switchKind(to: kind) }
                        }
                    } header: {
                        Text("Versión")
                    } footer: {
                        Text("Cambia de versión en el mismo punto del episodio.")
                    }
                }
                if !model.isCasting, model.audibleOptions.count > 1 {
                    Section("Audio") {
                        ForEach(model.audibleOptions, id: \.self) { track in
                            option(track.displayName, selected: model.selectedAudible == track) { model.selectAudible(track) }
                        }
                    }
                }
                if !model.isCasting {
                    Section("Velocidad") {
                        ForEach(PlaybackSpeed.allCases) { speed in
                            option(speed.label, selected: model.speed == speed) { model.setSpeed(speed) }
                        }
                    }
                }
            }
            .navigationTitle("Subtítulos y audio")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Listo") { dismiss() }
                }
            }
        }
        .presentationDetents([.medium, .large])
    }

    private func option(_ title: String, selected: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack {
                Text(title).foregroundStyle(.primary)
                Spacer()
                if selected {
                    Image(systemName: "checkmark")
                        .fontWeight(.semibold)
                        .foregroundStyle(.tint)
                }
            }
            .contentShape(Rectangle())
        }
        .accessibilityAddTraits(selected ? [.isSelected] : [])
    }
}

/// Vertical Liquid Glass bar for brightness or volume. Drag anywhere on it
/// to set the level; VoiceOver adjusts it with swipes.
private struct LevelBar: View {
    let title: String
    let systemImage: String
    let value: Double
    let onChange: (Double) -> Void
    let onEditing: (Bool) -> Void

    var body: some View {
        VStack(spacing: 10) {
            GeometryReader { proxy in
                ZStack(alignment: .bottom) {
                    Capsule().fill(.white.opacity(0.2))
                    Capsule().fill(.white)
                        .frame(height: proxy.size.height * min(max(value, 0), 1))
                }
            }
            .frame(width: 6)
            Image(systemName: systemImage)
                .font(.footnote.weight(.semibold))
                .foregroundStyle(.white)
                .frame(width: 24, height: 20)
        }
        .padding(.top, 16)
        .padding(.bottom, 12)
        .frame(width: 44, height: 168)
        .glassEffect(.regular, in: .capsule)
        .contentShape(Capsule())
        .gesture(
            DragGesture(minimumDistance: 0)
                .onChanged { drag in
                    onEditing(true)
                    // The track spans the bar minus the padding and the icon.
                    onChange(1 - (drag.location.y - 16) / (168 - 16 - 42))
                }
                .onEnded { _ in onEditing(false) }
        )
        .accessibilityElement()
        .accessibilityLabel(title)
        .accessibilityValue(Text(value, format: .percent.precision(.fractionLength(0))))
        .accessibilityAdjustableAction { direction in
            onChange(value + (direction == .increment ? 0.1 : -0.1))
        }
    }
}

/// System output volume (also the AirPlay route's). Apps can only set it
/// through an MPVolumeView, so a 1-pt one stays on screen with the player.
@MainActor
@Observable
final class SystemVolume {
    private(set) var level: Float = AVAudioSession.sharedInstance().outputVolume
    /// Bumped when the hardware buttons (or Control Center) change the volume.
    private(set) var externalChange = 0

    let view: MPVolumeView = {
        let view = MPVolumeView(frame: CGRect(x: 0, y: 0, width: 1, height: 1))
        view.alpha = 0.01
        view.isUserInteractionEnabled = false
        return view
    }()
    @ObservationIgnored private var observation: NSKeyValueObservation?
    @ObservationIgnored private var requested: Float?

    func start() {
        guard observation == nil else { return }
        level = AVAudioSession.sharedInstance().outputVolume
        observation = observeOutputVolume()
    }

    func stop() {
        observation?.invalidate()
        observation = nil
    }

    func set(_ value: Float) {
        let clamped = min(max(value, 0), 1)
        level = clamped
        requested = clamped
        guard let slider = Self.slider(in: view) else { return }
        slider.setValue(clamped, animated: false)
        slider.sendActions(for: .valueChanged)
    }

    func change(by delta: Float) {
        set(level + delta)
    }

    private static func slider(in view: UIView) -> UISlider? {
        if let slider = view as? UISlider { return slider }
        for subview in view.subviews {
            if let slider = slider(in: subview) { return slider }
        }
        return nil
    }

    nonisolated private func observeOutputVolume() -> NSKeyValueObservation {
        AVAudioSession.sharedInstance().observe(\.outputVolume, options: [.new]) { [weak self] session, _ in
            let value = session.outputVolume
            let volume = self
            DispatchQueue.main.async {
                MainActor.assumeIsolated { volume?.outputVolumeChanged(value) }
            }
        }
    }

    private func outputVolumeChanged(_ value: Float) {
        let ownChange = requested.map { abs($0 - value) < 0.02 } ?? false
        level = value
        if !ownChange { externalChange += 1 }
    }
}

private struct VolumeViewHost: UIViewRepresentable {
    let view: MPVolumeView

    func makeUIView(context: Context) -> MPVolumeView { view }
    func updateUIView(_ view: MPVolumeView, context: Context) {}
}

/// Brightness of the screen showing the app.
@MainActor
enum ScreenBrightness {
    private static var screen: UIScreen? {
        UIApplication.shared.connectedScenes.compactMap { ($0 as? UIWindowScene)?.screen }.first
    }

    static var level: CGFloat { screen?.brightness ?? 0.5 }

    static func set(_ value: Double) {
        screen?.brightness = CGFloat(min(max(value, 0), 1))
    }
}

/// Reports whether a foldable's hinge is half open (iOS 27.1+); never on
/// other devices and versions.
private struct HingeObserver: UIViewRepresentable {
    let onChange: (Bool) -> Void

    func makeUIView(context: Context) -> UIView {
        let view = UIView()
        view.isUserInteractionEnabled = false
        if #available(iOS 27.1, *) {
            let report = onChange
            view.addInteraction(UIHingeInteraction { _, update in
                report(update.hinge?.status == .partiallyOpen)
            })
        }
        return view
    }

    func updateUIView(_ view: UIView, context: Context) {}
}

/// UIView backed by an AVPlayerLayer, which also renders the selected subtitles.
final class PlayerLayerView: UIView {
    override class var layerClass: AnyClass { AVPlayerLayer.self }
    var playerLayer: AVPlayerLayer { layer as! AVPlayerLayer }
}

private struct VideoSurface: UIViewRepresentable {
    let model: PlayerModel
    let gravity: AVLayerVideoGravity

    func makeUIView(context: Context) -> PlayerLayerView {
        let view = PlayerLayerView()
        view.backgroundColor = .black
        view.playerLayer.player = model.player
        view.playerLayer.videoGravity = gravity
        model.attach(view.playerLayer)
        return view
    }

    func updateUIView(_ view: PlayerLayerView, context: Context) {
        if view.playerLayer.videoGravity != gravity {
            view.playerLayer.videoGravity = gravity
        }
    }
}

private struct RoutePickerButton: UIViewRepresentable {
    func makeUIView(context: Context) -> AVRoutePickerView {
        let picker = AVRoutePickerView()
        picker.prioritizesVideoDevices = true
        picker.tintColor = .white
        picker.activeTintColor = .systemBlue
        picker.backgroundColor = .clear
        return picker
    }

    func updateUIView(_ picker: AVRoutePickerView, context: Context) {}
}
