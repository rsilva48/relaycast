import AVFAudio
import SwiftUI
import UIKit

/// Work that iOS requires before launching finishes: the notification
/// delegate (so tapping a reminder can launch the app into its episode) and
/// the Google Cast context (so a Cast session resumes after a relaunch).
final class AppDelegate: NSObject, UIApplicationDelegate {
    func application(_ application: UIApplication, didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        MainActor.assumeIsolated {
            // New-episode alerts only follow a Relay Server's own library.
            ReleaseNotifier.shared.becomeNotificationDelegate()
            ReleaseNotifier.registerBackgroundCheck()
            CastCenter.configure()
        }
        return true
    }
}

@main
struct RelaycastApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate
    @State private var profile = ProfileStore()
    /// Created at launch so background downloads reconnect right away.
    @State private var downloads = DownloadStore.shared
    /// Created at launch so it is the notification delegate before a tapped
    /// reminder is delivered.
    @State private var notifier = ReleaseNotifier.shared

    init() {
        AudioSession.configure()
        // Starts watching the network path before the first request fails.
        _ = NetworkStatus.shared
        Task.detached(priority: .background) {
            PosterCache.prune()
            SeriesCache.prune()
        }
    }

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(profile)
                .environment(downloads)
                .environment(notifier)
                .tint(.indigo)
        }
    }
}

enum AudioSession {
    /// Long-form video routing makes AirPlay send the video stream (with its
    /// subtitle tracks) to the TV instead of mirroring the screen.
    static func configure() {
        try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .moviePlayback, policy: .longFormVideo)
    }

    /// Activated only while the player is open so other audio is not interrupted at launch.
    static func setActive(_ active: Bool) {
        let options: AVAudioSession.SetActiveOptions = active ? [] : [.notifyOthersOnDeactivation]
        try? AVAudioSession.sharedInstance().setActive(active, options: options)
    }
}

enum AppTab: Hashable {
    /// The home: the user's sources, with the Relay Server as an option.
    case sources
    /// The Relay Server's library (only when it offers one).
    case latest
    case history
    case downloads
    case settings
    case search
}

struct RootView: View {
    @Environment(ProfileStore.self) private var profile
    @Environment(DownloadStore.self) private var downloads
    @Environment(ReleaseNotifier.self) private var notifier
    @Environment(\.scenePhase) private var scenePhase
    @AppStorage(ReleaseNotifier.promptedKey) private var promptedNotifications = false
    @State private var showNotificationPrompt = false
    /// A TV pairing QR scanned with the camera (relaycast://pair…).
    @State private var pairingRequest: TVPairingRequest?
    /// A TV profile's QR (relaycast://identity…) to use on this device.
    @State private var identityRequest: IdentityTransferRequest?
    @State private var relaySetupRequest: RelaySetupRequest?
    @State private var selection: AppTab = .sources
    /// The setup assistant opens only from Settings or Fuentes: the Relay
    /// Server is optional.
    private var library: RelayLibraryStatus { .shared }
    /// Increments each time the Search tab is selected to focus its field.
    @State private var searchActivation = 0
    /// The TV of the remote control: the volume buttons change its volume.
    @AppStorage(TVRemoteSheet.connectedKey) private var connectedTV = ""

    /// The connected TV while nothing plays here: its episode shows on the
    /// lock screen (`TVNowPlaying`).
    private var connectedTarget: TVVolumeRelay.Target? {
        guard !connectedTV.isEmpty, profile.isRelayConfigured, !TVVolumeRelay.shared.localPlayback else { return nil }
        return TVVolumeRelay.Target(base: profile.relayURL, device: connectedTV)
    }

    /// The volume buttons go to the TV in the app, and on the lock screen
    /// while the TV's episode is shown there.
    private var volumeRelayTarget: TVVolumeRelay.Target? {
        guard scenePhase == .active || TVNowPlaying.shared.isActive else { return nil }
        return connectedTarget
    }

    var body: some View {
        @Bindable var notifier = notifier
        TabView(selection: $selection) {
            Tab("Fuentes", systemImage: "externaldrive.connected.to.line.below", value: AppTab.sources) {
                SourcesScreen(openLibrary: { selection = .latest })
            }
            // The Relay Server's library: only from a server that offers one.
            if library.isAvailable {
                Tab("Biblioteca", systemImage: "film.stack", value: AppTab.latest) {
                    LatestScreen()
                }
                Tab("Continuar", systemImage: "play.circle", value: AppTab.history) {
                    HistoryScreen()
                }
            }
            // Downloads play without a relay or network, so they stay while there are any.
            if library.isAvailable || !downloads.records.isEmpty {
                Tab("Descargas", systemImage: "arrow.down.circle", value: AppTab.downloads) {
                    DownloadsScreen()
                }
            }
            Tab("Ajustes", systemImage: "gearshape", value: AppTab.settings) {
                SettingsScreen()
            }
            if library.isAvailable {
                Tab(value: AppTab.search, role: .search) {
                    SearchScreen(activation: searchActivation)
                }
            }
        }
        .modifier(PlayerHost())
        .onChange(of: selection) { _, tab in
            if tab == .search { searchActivation += 1 }
        }
        // A tab that disappeared (the library is gone): back to Fuentes.
        .onChange(of: library.isAvailable) { _, available in
            if !available, [.latest, .history, .search].contains(selection) { selection = .sources }
        }
        .task(id: profile.relayURL) {
            await library.refresh(relayURL: profile.relayURL)
        }
        .background {
            TVVolumeRelayHost()
                .frame(width: 1, height: 1)
                .accessibilityHidden(true)
        }
        .task(id: connectedTarget) {
            TVNowPlaying.shared.follow(connectedTarget)
        }
        .task(id: volumeRelayTarget) {
            if let target = volumeRelayTarget {
                TVVolumeRelay.shared.start(target)
            } else {
                TVVolumeRelay.shared.stop()
            }
        }
        .tabViewStyle(.sidebarAdaptable)
        .tabBarMinimizeBehavior(.onScrollDown)
        .task(id: profile.scope) {
            downloads.use(profile)
            await profile.sync(force: true)
            downloads.cleanUpWatched(profile: profile)
            await library.refresh(relayURL: profile.relayURL)
            await notifier.refresh(profile: profile)
        }
        .onChange(of: scenePhase) { _, phase in
            // Checks for released episodes with the latest history on every return.
            if phase == .active {
                Task {
                    await library.refresh(relayURL: profile.relayURL)
                    await profile.sync(force: true)
                    await notifier.refresh(profile: profile)
                }
            } else if phase == .background {
                notifier.scheduleBackgroundCheck()
            }
        }
        // Offers release reminders once, when a Relay Server's library is available.
        .task(id: library.isAvailable) {
            guard library.isAvailable, !promptedNotifications else { return }
            if notifier.isEnabled {
                promptedNotifications = true
                return
            }
            try? await Task.sleep(for: .seconds(1.5))
            guard !Task.isCancelled else { return }
            showNotificationPrompt = true
        }
        .alert("¿Avisarte de nuevos episodios?", isPresented: $showNotificationPrompt) {
            Button("Activar avisos") {
                Task { await notifier.enableFromPrompt(profile: profile) }
            }
            Button("Ahora no", role: .cancel) {
                promptedNotifications = true
            }
        } message: {
            Text("La app puede avisarte cuando salga un episodio nuevo de las series que sigues en Continuar viendo. Puedes cambiarlo cuando quieras en Ajustes.")
        }
        .onOpenURL { url in
            switch ScannedCode(url: url) {
            case .identity(let request): identityRequest = request
            case .pairing(let request): pairingRequest = request
            case .relaySetup(let request): relaySetupRequest = request
            case nil: break
            }
        }
        .sheet(item: $relaySetupRequest) { request in
            RelaySetupSheet(request: request)
        }
        .sheet(item: $pairingRequest) { request in
            TVPairingSheet(request: request)
        }
        .sheet(item: $identityRequest) { request in
            IdentityImportSheet(request: request)
        }
        .sheet(item: $notifier.openedSeries) { request in
            NavigationStack {
                EpisodesScreen(anime: request.anime, autoplay: request.autoplay)
                    .toolbar {
                        ToolbarItem(placement: .cancellationAction) {
                            Button("Cerrar") { notifier.openedSeries = nil }
                        }
                    }
            }
            // Its own player: the tabs' one cannot present over this sheet.
            .modifier(PlayerHost())
        }
    }
}
