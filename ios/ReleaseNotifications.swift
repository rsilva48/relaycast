import BackgroundTasks
import Foundation
import Observation
import UserNotifications

/// One estimated airing from the catalog's weekly schedule.
struct ScheduleEntry: Decodable {
    let watchUrl: String
    let title: String
    let titleJp: String?
    let imageUrl: String?
    let episode: Int
    let kind: StreamKind
    /// Unix seconds.
    let airingAt: Double

    var airingDate: Date { Date(timeIntervalSince1970: airingAt) }
}

struct ScheduleResponse: Decodable { let entries: [ScheduleEntry] }

/// A tapped reminder: the series, and the episode and Sub/Dub kind it
/// announced (reminders scheduled before 0.2.28 carry only the series).
struct ReleaseOpenRequest: Identifiable {
    let anime: Anime
    let episode: Int?
    let kind: StreamKind?
    var id: String { "\(anime.watchUrl)#\(episode ?? 0)#\(kind?.rawValue ?? "")" }

    /// The episode to play right away, when the reminder named one.
    var autoplay: ReleaseAutoplay? {
        guard let episode, let kind else { return nil }
        return ReleaseAutoplay(episode: episode, kind: kind)
    }
}

/// An episode the series screen opens in the player as soon as the relay
/// confirms it is published with a server of that kind.
struct ReleaseAutoplay: Equatable {
    let episode: Int
    let kind: StreamKind
}

/// Notifications of new episodes of the series in Continue, only once the
/// catalog has really published them: the app asks the relay for each
/// series' latest episode and notifies those past the last one it knew. An
/// estimated airing time is never announced (episodes are often delayed for
/// weeks). The check runs when the app opens and, in the background, when
/// iOS allows it (`BGAppRefreshTask`). Remote push would need a signed app with the push entitlement
/// and a sender.
@MainActor
@Observable
final class ReleaseNotifier {
    static let shared = ReleaseNotifier()
    static let enabledKey = "relaycast.release-notifications"
    /// The launch prompt was answered (or reminders were enabled before it).
    static let promptedKey = "relaycast.release-notifications.prompted"
    private static let identifierPrefix = "relaycast.release."
    /// The latest episode known per series and kind ("<path>#sub": 12).
    private static let knownKey = "relaycast.release.known"

    /// Series opened from a notification; the root view presents it.
    var openedSeries: ReleaseOpenRequest?

    @ObservationIgnored private let delegate = ReleaseNotificationDelegate()

    private init() {
        delegate.notifier = self
        UNUserNotificationCenter.current().delegate = delegate
    }

    /// iOS delivers the tap that launched the app only to a delegate set
    /// before launching finishes; the app delegate calls this from
    /// `application(_:didFinishLaunchingWithOptions:)`.
    func becomeNotificationDelegate() {
        UNUserNotificationCenter.current().delegate = delegate
    }

    var isEnabled: Bool { UserDefaults.standard.bool(forKey: Self.enabledKey) }

    /// Answer to the launch prompt: asks iOS for permission and, if granted,
    /// turns the reminders on.
    func enableFromPrompt(profile: ProfileStore) async {
        UserDefaults.standard.set(true, forKey: Self.promptedKey)
        guard await requestAuthorization() else { return }
        UserDefaults.standard.set(true, forKey: Self.enabledKey)
        await refresh(profile: profile)
    }

    /// Asks for permission; false when the user declined (now or before).
    func requestAuthorization() async -> Bool {
        (try? await UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge])) ?? false
    }

    /// Checks now with the synced history and schedules the background check.
    func refresh(profile: ProfileStore) async {
        // Versions before 0.2.41 scheduled reminders at the estimated time,
        // which announced delayed episodes.
        await removeScheduled()
        guard isEnabled, profile.isRelayConfigured, RelayLibrary.isVerified(profile.relayURL) else {
            cancelBackgroundCheck()
            return
        }
        await check(base: profile.relayURL, entries: profile.entries, preferences: profile.preferences)
        scheduleBackgroundCheck()
    }

    // MARK: Background check

    /// The background check's identifier as the Info.plist lists it.
    nonisolated private static var backgroundTaskIdentifier: String? {
        (Bundle.main.object(forInfoDictionaryKey: "BGTaskSchedulerPermittedIdentifiers") as? [String])?.first { $0.hasSuffix(".release-check") }
    }

    nonisolated private static var backgroundCheckAvailable: Bool {
        backgroundTaskIdentifier != nil
    }

    /// From `application(_:didFinishLaunchingWithOptions:)`, as iOS requires.
    static func registerBackgroundCheck() {
        guard backgroundCheckAvailable, let identifier = backgroundTaskIdentifier else { return }
        BGTaskScheduler.shared.register(forTaskWithIdentifier: identifier, using: .main) { task in
            let work = Task { @MainActor in
                await ReleaseNotifier.shared.backgroundCheck()
                ReleaseNotifier.shared.scheduleBackgroundCheck()
                task.setTaskCompleted(success: true)
            }
            task.expirationHandler = { work.cancel() }
        }
    }

    /// iOS decides when it runs (usually a few times a day for an app used often).
    func scheduleBackgroundCheck() {
        guard isEnabled, Self.backgroundCheckAvailable, let identifier = Self.backgroundTaskIdentifier else { return }
        let request = BGAppRefreshTaskRequest(identifier: identifier)
        request.earliestBeginDate = Date(timeIntervalSinceNow: 30 * 60)
        try? BGTaskScheduler.shared.submit(request)
    }

    private func cancelBackgroundCheck() {
        guard let identifier = Self.backgroundTaskIdentifier, Self.backgroundCheckAvailable else { return }
        BGTaskScheduler.shared.cancel(taskRequestWithIdentifier: identifier)
    }

    /// With the app in the background there is no synced profile: history
    /// and preferences come straight from the relay.
    private func backgroundCheck() async {
        guard isEnabled, let base = ProfileStore.activeRelayURL() else { return }
        // Only a Relay Server with the user's own library has new episodes.
        guard await RelayLibrary.verify(base: base) else { return }
        guard let history: HistoryResponse = try? await RelayClient.get(base: base, path: "/api/history") else { return }
        let envelope: PreferencesEnvelope? = try? await RelayClient.get(base: base, path: "/api/preferences")
        await check(base: base, entries: history.entries, preferences: envelope?.preferences ?? UserPreferences())
    }

    // MARK: Check

    /// Notifies the series in Continue whose latest published episode, in
    /// the Sub/Dub kind each one is followed in, went past the last one known
    /// (and past the one watched). A series seen for the first time is only
    /// remembered, so what was already out is not announced.
    private func check(base: String, entries: [WatchHistoryEntry], preferences: UserPreferences) async {
        let settings = await UNUserNotificationCenter.current().notificationSettings()
        guard settings.authorizationStatus == .authorized || settings.authorizationStatus == .provisional else { return }
        let followed = Array(entries.prefix(50))
        guard !followed.isEmpty,
              let reply: SeriesStatusResponse = try? await RelayClient.post(base: base, path: "/api/series/status", body: SeriesStatusRequest(watchUrls: followed.map(\.watchUrl))) else { return }
        var known = UserDefaults.standard.dictionary(forKey: Self.knownKey) as? [String: Int] ?? [:]
        for entry in followed {
            let path = catalogPath(of: entry.watchUrl)
            guard let status = reply.statuses.first(where: { catalogPath(of: $0.watchUrl) == path }) else { continue }
            let kind = entry.kind ?? preferences.defaultTrack
            guard let latest = kind == .dub ? status.latestDub : status.latestSub else { continue }
            let key = "\(path)#\(kind.rawValue)"
            let previous = known[key]
            known[key] = max(previous ?? 0, latest)
            guard let previous else { continue }
            let first = max(previous, entry.episode) + 1
            guard latest >= first else { continue }
            try? await UNUserNotificationCenter.current().add(request(for: entry, first: first, latest: latest, kind: kind, language: preferences.titleLanguage))
        }
        UserDefaults.standard.set(known, forKey: Self.knownKey)
    }

    fileprivate func open(watchUrl: String, title: String, titleJp: String?, imageUrl: String?, episode: Int?, kind: StreamKind?) {
        let anime = Anime(title: title, watchUrl: watchUrl, imageUrl: imageUrl, meta: "", rating: nil, score: nil, mediaType: nil, year: nil, titleJp: titleJp)
        openedSeries = ReleaseOpenRequest(anime: anime, episode: episode, kind: kind)
    }

    private func removeScheduled() async {
        let center = UNUserNotificationCenter.current()
        let stale = await center.pendingNotificationRequests()
            .map(\.identifier)
            .filter { $0.hasPrefix(Self.identifierPrefix) }
        center.removePendingNotificationRequests(withIdentifiers: stale)
    }

    /// Delivered right away; tapping it plays the first new episode.
    private func request(for entry: WatchHistoryEntry, first: Int, latest: Int, kind: StreamKind, language: TitleLanguage) -> UNNotificationRequest {
        let content = UNMutableNotificationContent()
        let japanese = entry.titleJp.flatMap { $0.isEmpty ? nil : $0 }
        content.title = language == .jp ? japanese ?? entry.seriesTitle : entry.seriesTitle
        content.body = first == latest
            ? String(localized: "Ya disponible: episodio \(latest) en \(kind.displayName).")
            : String(localized: "Ya disponibles: episodios \(first) a \(latest) en \(kind.displayName).")
        content.sound = .default
        content.threadIdentifier = catalogPath(of: entry.watchUrl)
        content.userInfo = [
            "watchUrl": entry.watchUrl,
            "title": entry.seriesTitle,
            "titleJp": japanese ?? "",
            "imageUrl": entry.imageUrl ?? "",
            "episode": first,
            "kind": kind.rawValue,
        ]
        let identifier = "\(Self.identifierPrefix)\(catalogPath(of: entry.watchUrl))#\(latest)#\(kind.rawValue)"
        return UNNotificationRequest(identifier: identifier, content: content, trigger: nil)
    }
}

private final class ReleaseNotificationDelegate: NSObject, UNUserNotificationCenterDelegate {
    weak var notifier: ReleaseNotifier?

    /// Banners also show while the app is open.
    func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification, withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) {
        completionHandler([.banner, .list, .sound])
    }

    /// Tapping a reminder opens that series and plays the episode it
    /// announced, whether the app was closed, suspended or open.
    func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse, withCompletionHandler completionHandler: @escaping () -> Void) {
        let info = response.notification.request.content.userInfo
        let text = { (key: String) -> String? in
            guard let value = info[key] as? String, !value.isEmpty else { return nil }
            return value
        }
        if let watchUrl = text("watchUrl") {
            let title = text("title") ?? "", titleJp = text("titleJp"), imageUrl = text("imageUrl")
            let episode = (info["episode"] as? NSNumber)?.intValue
            let kind = text("kind").flatMap(StreamKind.init(rawValue:))
            DispatchQueue.main.async {
                MainActor.assumeIsolated {
                    self.notifier?.open(watchUrl: watchUrl, title: title, titleJp: titleJp, imageUrl: imageUrl, episode: episode, kind: kind)
                }
            }
        }
        completionHandler()
    }
}
