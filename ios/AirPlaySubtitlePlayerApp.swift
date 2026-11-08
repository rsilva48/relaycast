import AVFAudio
import SwiftUI
import UIKit

/// Work that iOS requires before launching finishes: the notification
/// delegate (so tapping a reminder can launch the app into its episode) and
/// the Google Cast context (so a Cast session resumes after a relaunch).
final class AppDelegate: NSObject, UIApplicationDelegate {
    func application(_ application: UIApplication, didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        MainActor.assumeIsolated {
            // Relaycast (App Store) has no catalog, so no new-episode alerts.
            ReleaseNotifier.shared.becomeNotificationDelegate()
            ReleaseNotifier.registerBackgroundCheck()
            CastCenter.configure()
        }
        return true
    }
}

@main
struct AirPlaySubtitlePlayerApp: App {
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
    @State private var selection: AppTab = .latest
    /// Asistente de configuración inicial: by itself only without a relay,
    /// until finished or left for later.
    @AppStorage("relaycast.setup-assistant.done") private var setupDone = false
    @State private var showSetup = false
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
            Tab("Recientes", systemImage: "sparkles", value: AppTab.latest) {
                requiringRelay { LatestScreen() }
            }
            Tab("Continuar", systemImage: "play.circle", value: AppTab.history) {
                requiringRelay { HistoryScreen() }
            }
            // Not gated: downloads play without a relay or network.
            Tab("Descargas", systemImage: "arrow.down.circle", value: AppTab.downloads) {
                DownloadsScreen()
            }
            Tab("Ajustes", systemImage: "gearshape", value: AppTab.settings) {
                SettingsScreen()
            }
            Tab(value: AppTab.search, role: .search) {
                requiringRelay { SearchScreen(activation: searchActivation) }
            }
        }
        .modifier(PlayerHost())
        .onChange(of: selection) { _, tab in
            if tab == .search { searchActivation += 1 }
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
        .onAppear {
            if !profile.isRelayConfigured && !setupDone { showSetup = true }
        }
        .fullScreenCover(isPresented: $showSetup) {
            SetupAssistant {
                setupDone = true
                showSetup = false
                if profile.isRelayConfigured { selection = .latest }
            }
        }
        .tabViewStyle(.sidebarAdaptable)
        .tabBarMinimizeBehavior(.onScrollDown)
        .task(id: profile.scope) {
            downloads.use(profile)
            await profile.sync(force: true)
            downloads.cleanUpWatched(profile: profile)
            await notifier.refresh(profile: profile)
        }
        .onChange(of: scenePhase) { _, phase in
            // Checks for released episodes with the latest history on every return.
            if phase == .active {
                Task {
                    await profile.sync(force: true)
                    await notifier.refresh(profile: profile)
                }
            } else if phase == .background {
                notifier.scheduleBackgroundCheck()
            }
        }
        // Offers release reminders once, after a relay is configured.
        .task(id: profile.isRelayConfigured && !showSetup) {
            // The assistant asks itself; never over it.
            guard profile.isRelayConfigured, !showSetup, !promptedNotifications else { return }
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

    /// Catalog tabs stay locked until a relay answered the connection test,
    /// so a fresh install never searches against an unset address.
    @ViewBuilder
    private func requiringRelay<Content: View>(@ViewBuilder _ content: () -> Content) -> some View {
        if profile.isRelayConfigured {
            content()
        } else {
            RelaySetupRequiredView(openSettings: { selection = .settings }, openAssistant: { showSetup = true })
        }
    }
}

private struct RelaySetupRequiredView: View {
    let openSettings: () -> Void
    let openAssistant: () -> Void

    var body: some View {
        NavigationStack {
            ContentUnavailableView {
                Label("Configura tu relay", systemImage: "antenna.radiowaves.left.and.right")
            } description: {
                Text("Escribe la dirección de tu relay en Ajustes y pruébala. Buscar, Recientes y Continuar se activan en cuanto responde.")
            } actions: {
                Button("Asistente de configuración inicial", systemImage: "wand.and.stars", action: openAssistant)
                    .buttonStyle(.glassProminent)
                    .controlSize(.large)
                Button("Ir a Ajustes", systemImage: "gearshape", action: openSettings)
                    .buttonStyle(.glass)
            }
        }
    }
}
