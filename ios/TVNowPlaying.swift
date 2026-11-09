import AVFAudio
import MediaPlayer
import UIKit

/// While the connected TV has an episode on (playing, paused or loading),
/// this device shows it as Now Playing (lock screen, Control Center,
/// headphones) and those controls drive the TV. iOS gives Now Playing only
/// to an app that plays audio, so a silent loop plays meanwhile; it also
/// keeps the app following the TV in the background. The local player
/// takes over when it opens.
@MainActor
@Observable
final class TVNowPlaying {
    static let shared = TVNowPlaying()
    /// Showing the TV's episode as Now Playing.
    private(set) var isActive = false

    @ObservationIgnored private var target: TVVolumeRelay.Target?
    @ObservationIgnored private var poll: Task<Void, Never>?
    @ObservationIgnored private var silence: AVAudioPlayer?
    @ObservationIgnored private var commandTargets: [(MPRemoteCommand, Any)] = []
    @ObservationIgnored private var artwork: (url: String, image: MPMediaItemArtwork)?
    @ObservationIgnored private var loadingArtwork: String?

    /// Follows the connected TV (nil: none, or the local player is open).
    func follow(_ target: TVVolumeRelay.Target?) {
        guard target != self.target else { return }
        self.target = target
        poll?.cancel()
        poll = nil
        guard let target else {
            deactivate()
            return
        }
        poll = Task { [weak self] in
            while !Task.isCancelled {
                await self?.refresh(target)
                let busy = self?.isActive ?? false
                try? await Task.sleep(for: .seconds(busy ? 2 : 4))
            }
        }
    }

    /// Hands the audio session and the remote commands to the local player.
    func yieldToLocalPlayback() {
        follow(nil)
    }

    private func refresh(_ target: TVVolumeRelay.Target) async {
        guard let reply: RemoteDevicesResponse = try? await RelayClient.get(base: target.base, path: "/api/devices", timeoutInterval: 10),
              self.target == target else { return }
        let device = reply.devices.first { $0.id == target.device }
        guard let device, let state = device.state, state.isActive else {
            deactivate()
            return
        }
        activate()
        show(state, device: device)
    }

    // MARK: Session

    private func activate() {
        guard !isActive, !TVVolumeRelay.shared.localPlayback else { return }
        let session = AVAudioSession.sharedInstance()
        // Not mixable: only the app that owns the audio gets Now Playing.
        do {
            try session.setCategory(.playback, mode: .default)
            try session.setActive(true)
        } catch {
            return
        }
        let player = try? AVAudioPlayer(data: Self.silentWAV)
        player?.numberOfLoops = -1
        guard player?.play() == true else { return }
        silence = player
        registerCommands()
        isActive = true
    }

    private func deactivate() {
        guard isActive else { return }
        isActive = false
        silence?.stop()
        silence = nil
        unregisterCommands()
        MPNowPlayingInfoCenter.default().nowPlayingInfo = nil
        // The local player sets up its own session.
        guard !TVVolumeRelay.shared.localPlayback else { return }
        if TVVolumeRelay.shared.isRunning {
            try? AVAudioSession.sharedInstance().setCategory(.ambient, options: [.mixWithOthers])
        } else {
            try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
            AudioSession.configure()
        }
    }

    // MARK: Now Playing

    private func show(_ state: RemoteDeviceState, device: RemoteDevice) {
        let playing = state.status == "playing"
        let title: String
        if let episode = state.episode {
            if let episodeTitle = state.episodeTitle {
                title = String(localized: "Episodio \(episode) · \(episodeTitle)")
            } else {
                title = String(localized: "Episodio \(episode)")
            }
        } else {
            title = state.seriesTitle ?? device.name
        }
        let album = String(localized: "En \(device.name)")
        var info: [String: Any] = [
            MPMediaItemPropertyTitle: title,
            MPMediaItemPropertyArtist: state.seriesTitle ?? "",
            MPMediaItemPropertyAlbumTitle: album,
            MPNowPlayingInfoPropertyMediaType: MPNowPlayingInfoMediaType.video.rawValue,
            MPNowPlayingInfoPropertyPlaybackRate: playing ? 1.0 : 0.0,
            MPNowPlayingInfoPropertyDefaultPlaybackRate: 1.0,
        ]
        if let duration = state.duration, duration > 0 {
            info[MPMediaItemPropertyPlaybackDuration] = duration
        }
        if let position = state.position {
            // The TV reported it `stateAge` ago; the system advances it from here.
            info[MPNowPlayingInfoPropertyElapsedPlaybackTime] = position + (playing ? (device.stateAge ?? 0) / 1000 : 0)
        }
        if let imageUrl = state.imageUrl {
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
            let item = MPMediaItemArtwork(boundsSize: image.size) { _ in image }
            artwork = (imageUrl, item)
            if isActive, var info = MPNowPlayingInfoCenter.default().nowPlayingInfo {
                info[MPMediaItemPropertyArtwork] = item
                MPNowPlayingInfoCenter.default().nowPlayingInfo = info
            }
        }
    }

    // MARK: Remote commands

    private func registerCommands() {
        let center = MPRemoteCommandCenter.shared()
        center.skipForwardCommand.preferredIntervals = [10]
        center.skipBackwardCommand.preferredIntervals = [10]
        add(center.playCommand) { _ in RemoteCommand(type: "resume") }
        add(center.pauseCommand) { _ in RemoteCommand(type: "pause") }
        add(center.togglePlayPauseCommand) { _ in RemoteCommand(type: "toggle") }
        add(center.skipForwardCommand) { _ in RemoteCommand(type: "seek", seconds: 10) }
        add(center.skipBackwardCommand) { _ in RemoteCommand(type: "seek", seconds: -10) }
        add(center.nextTrackCommand) { _ in RemoteCommand(type: "next") }
        add(center.previousTrackCommand) { _ in RemoteCommand(type: "previous") }
        add(center.changePlaybackPositionCommand) { event in
            (event as? MPChangePlaybackPositionCommandEvent).map { RemoteCommand(type: "seek", position: $0.positionTime) }
        }
    }

    private func add(_ command: MPRemoteCommand, _ makeCommand: @escaping (MPRemoteCommandEvent) -> RemoteCommand?) {
        command.isEnabled = true
        let handler = command.addTarget { [weak self] event in
            guard let remote = makeCommand(event) else { return .commandFailed }
            MainActor.assumeIsolated { () -> Void in
                self?.send(remote)
            }
            return .success
        }
        commandTargets.append((command, handler))
    }

    private func unregisterCommands() {
        for (command, handler) in commandTargets {
            command.removeTarget(handler)
        }
        commandTargets = []
    }

    private func send(_ command: RemoteCommand) {
        guard let target else { return }
        // Pause and resume show right away; the next report confirms them.
        if ["pause", "resume", "toggle"].contains(command.type), var info = MPNowPlayingInfoCenter.default().nowPlayingInfo {
            let rate = info[MPNowPlayingInfoPropertyPlaybackRate] as? Double ?? 0
            let playing = command.type == "resume" || (command.type == "toggle" && rate == 0)
            info[MPNowPlayingInfoPropertyPlaybackRate] = playing ? 1.0 : 0.0
            MPNowPlayingInfoCenter.default().nowPlayingInfo = info
        }
        Task {
            let _: RemoteCommandReplyBody? = try? await RelayClient.post(base: target.base, path: "/api/devices/\(target.device)/commands", body: command)
            try? await Task.sleep(for: .milliseconds(700))
            await refresh(target)
        }
    }

    // MARK: Silence

    /// One second of 8 kHz mono 16-bit silence, as a WAV file (also kept
    /// playing while casting, see `PlayerModel`).
    static let silentWAV: Data = {
        let sampleRate: UInt32 = 8000
        let samples = Data(count: Int(sampleRate) * 2)
        var data = Data()
        func append<T: FixedWidthInteger>(_ value: T) { withUnsafeBytes(of: value.littleEndian) { data.append(contentsOf: $0) } }
        data.append(contentsOf: Array("RIFF".utf8))
        append(UInt32(36 + samples.count))
        data.append(contentsOf: Array("WAVEfmt ".utf8))
        append(UInt32(16))
        append(UInt16(1)) // PCM
        append(UInt16(1)) // mono
        append(sampleRate)
        append(sampleRate * 2) // bytes per second
        append(UInt16(2)) // block align
        append(UInt16(16)) // bits per sample
        data.append(contentsOf: Array("data".utf8))
        append(UInt32(samples.count))
        data.append(samples)
        return data
    }()
}

private struct RemoteCommandReplyBody: Decodable { let ok: Bool }
