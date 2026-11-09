import Foundation

/// The app's interface language: Spanish when the device prefers Spanish,
/// English for every other language (the development region).
enum AppLanguage {
    static var isSpanish: Bool { Bundle.main.preferredLocalizations.first?.hasPrefix("es") == true }
}

enum StreamKind: String, Codable, CaseIterable, Identifiable {
    case sub
    case dub

    var id: String { rawValue }
    var displayName: String { self == .dub ? "Dub" : "Sub" }
    var detail: String { self == .dub ? String(localized: "Audio en inglés") : String(localized: "Audio en japonés con subtítulos") }
}

enum TitleLanguage: String, Codable, CaseIterable, Identifiable {
    case en
    case jp

    var id: String { rawValue }
    var displayName: String { self == .jp ? String(localized: "Japonés") : String(localized: "Inglés") }
}

/// Catalog and playback settings. They belong to the active identity (relay
/// URL + token) and sync through the relay like the watch history.
struct UserPreferences: Codable, Equatable {
    static let subtitlesOff = "off"

    var titleLanguage: TitleLanguage = .en
    var defaultTrack: StreamKind = .sub
    /// Subtitle language for Sub sources: empty follows the device languages,
    /// `off` disables them, anything else is a BCP-47 tag such as `en` or `es-419`.
    var subtitleLanguage = ""
    var autoNext = true
    var autoSkipIntro = false
    var autoSkipOutro = false
    /// Hides R+ and Rx series from Recents and Search.
    var hideAdult = false
    var updatedAt = Date.distantPast

    enum CodingKeys: String, CodingKey {
        case titleLanguage
        case defaultTrack = "defaultTrackType"
        case subtitleLanguage
        case autoNext
        case autoSkipIntro
        case autoSkipOutro
        case hideAdult
        case updatedAt
    }

    init() {}

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        titleLanguage = (try? container.decode(TitleLanguage.self, forKey: .titleLanguage)) ?? .en
        defaultTrack = (try? container.decode(StreamKind.self, forKey: .defaultTrack)) ?? .sub
        subtitleLanguage = (try? container.decode(String.self, forKey: .subtitleLanguage)) ?? ""
        autoNext = (try? container.decode(Bool.self, forKey: .autoNext)) ?? true
        autoSkipIntro = (try? container.decode(Bool.self, forKey: .autoSkipIntro)) ?? false
        autoSkipOutro = (try? container.decode(Bool.self, forKey: .autoSkipOutro)) ?? false
        hideAdult = (try? container.decode(Bool.self, forKey: .hideAdult)) ?? false
        updatedAt = (try? container.decode(Date.self, forKey: .updatedAt)) ?? .distantPast
    }

    /// Compares the settings themselves, ignoring when they were changed.
    func hasSameSettings(as other: UserPreferences) -> Bool {
        var copy = other
        copy.updatedAt = updatedAt
        return copy == self
    }
}

struct Anime: Codable, Hashable, Identifiable {
    let title: String
    let watchUrl: String
    let imageUrl: String?
    let meta: String
    let rating: String?
    let score: Double?
    let mediaType: String?
    let year: Int?
    var titleJp: String? = nil
    /// Newest available episode (Recents); Sub/Dub/total counts when known.
    var episode: Int? = nil
    var subEpisodes: Int? = nil
    var dubEpisodes: Int? = nil
    var totalEpisodes: Int? = nil
    var adult: Bool? = nil
    var id: String { watchUrl }

    /// R+ and Rx ratings, or the catalog's own 18+ marker.
    var isAdult: Bool { adult == true || ["R+", "Rx"].contains(rating ?? "") }

    /// "EP 32" or "EP 32/34" when the total is known and not reached yet.
    var episodeBadge: String? {
        guard let episode else { return nil }
        if let totalEpisodes, totalEpisodes > episode { return "EP \(episode)/\(totalEpisodes)" }
        return "EP \(episode)"
    }

    func displayTitle(_ language: TitleLanguage) -> String {
        if language == .jp, let titleJp, !titleJp.isEmpty { return titleJp }
        return title
    }

    var metadataLine: String {
        [mediaType, year.map(String.init), score.map { String(format: "★ %.1f", $0) }, rating]
            .compactMap { $0 }
            .joined(separator: " · ")
    }
}

/// Codable so the series screen can be cached for offline use.
struct Series: Codable {
    let seriesKey: String
    let title: String
    let titleJp: String?
    let episodes: [Episode]
    let seasons: [Season]
    // Details from the series page; older relays omit them.
    let synopsis: String?
    let rating: String?
    let adult: Bool?
    let status: String?
    let mediaType: String?
    let score: Double?
    let totalEpisodes: Int?
    let imageUrl: String?
    /// Estimated airing of the next episode (Unix seconds).
    var nextEpisodeAt: Double? = nil

    var isAdult: Bool { adult == true || ["R+", "Rx"].contains(rating ?? "") }

    /// The estimated next airing, only while it is still ahead.
    var nextEpisodeDate: Date? { upcomingDate(nextEpisodeAt) }

    var statusLabel: String? {
        switch status {
        case "finished": return String(localized: "Finalizado")
        case "airing": return String(localized: "En emisión")
        case "upcoming": return String(localized: "Próximamente")
        default: return nil
        }
    }

    var metadataLine: String {
        [mediaType, statusLabel, score.map { String(format: "★ %.1f", $0) }, rating]
            .compactMap { $0 }
            .joined(separator: " · ")
    }

    /// The first episode after `number` that has a source of the given kind.
    func episode(after number: Int, kind: StreamKind) -> Episode? {
        episodes.first { $0.number > number && $0.isAvailable(for: kind) }
    }
}

struct Season: Codable, Identifiable, Hashable {
    let title: String
    let titleJp: String?
    let watchUrl: String
    let imageUrl: String?
    let meta: String?
    let rating: String?
    let score: Double?
    let mediaType: String?
    let year: Int?
    let seasonNumber: Int
    /// Curated name such as "Season 1, Part 2", when the catalog has one.
    let label: String?
    var id: String { watchUrl }

    /// The curated name in the app's language: Spanish ("Temporada 1, parte
    /// 2", "Especial 1") or the catalog's own English name.
    var localizedLabel: String? {
        guard let label, !label.isEmpty else { return nil }
        guard AppLanguage.isSpanish else { return label }
        return label
            .replacingOccurrences(of: "Season", with: "Temporada")
            .replacingOccurrences(of: "Part", with: "parte")
            .replacingOccurrences(of: "Specials", with: "Especiales")
            .replacingOccurrences(of: "Special", with: "Especial")
    }

    /// Specials, recaps, OVAs and movies listed between regular seasons.
    var isExtra: Bool {
        title.range(of: #"\b(specials?|mini anime|recap|ova|oad|movie|film)\b"#, options: [.regularExpression, .caseInsensitive]) != nil
    }

    var anime: Anime {
        Anime(title: title, watchUrl: watchUrl, imageUrl: imageUrl, meta: meta ?? "", rating: rating, score: score, mediaType: mediaType, year: year, titleJp: titleJp)
    }
}

struct Episode: Codable, Identifiable, Hashable {
    let number: Int
    let title: String
    /// Whether the catalog has Sub/Dub sources; nil on older relays.
    let sub: Bool?
    let dub: Bool?
    var id: Int { number }

    func isAvailable(for kind: StreamKind) -> Bool {
        switch kind {
        case .sub: return sub ?? true
        case .dub: return dub ?? true
        }
    }

    /// The relay falls back to "Episode N"; that adds nothing next to the number.
    var subtitle: String? {
        let value = title.trimmingCharacters(in: .whitespaces)
        return value.isEmpty || value == "Episode \(number)" ? nil : value
    }
}

struct Server: Decodable, Identifiable {
    let index: Int
    let name: String
    let type: String
    var id: Int { index }
    var kind: StreamKind { StreamKind(rawValue: type.lowercased()) ?? .sub }
}

struct EpisodeServers: Identifiable {
    let episode: Episode
    let servers: [Server]
    var id: Int { episode.number }
}

struct SkipRange: Codable, Hashable {
    let start: Double
    let end: Double

    func contains(_ seconds: Double) -> Bool { seconds >= start && seconds < end }
}

struct Playback: Decodable {
    let playbackUrl: String
    let subtitleTracks: Int
    let serverName: String
    let serverKind: String?
    let intro: SkipRange?
    let outro: SkipRange?
}

struct PlaybackSession: Identifiable {
    let playback: Playback
    let historyEntry: WatchHistoryEntry
    let kind: StreamKind
    /// Set when the episode plays from a local download.
    var downloadID: String? = nil
    var id: String { playback.playbackUrl }
}

struct WatchHistoryEntry: Codable, Hashable, Identifiable {
    let seriesTitle: String
    let watchUrl: String
    let imageUrl: String?
    let episode: Int
    let episodeTitle: String
    let serverName: String
    var positionSeconds: Double
    var updatedAt: Date
    var titleJp: String? = nil
    var serverKind: String? = nil
    var durationSeconds: Double? = nil
    /// The episode was watched to the end or to its outro.
    var completed: Bool? = nil
    /// Episodes announced for the series when it was last played.
    var totalEpisodes: Int? = nil
    var id: String { "\(watchUrl)#\(episode)" }

    var kind: StreamKind? { serverKind.flatMap(StreamKind.init(rawValue:)) }
    var isCompleted: Bool { completed == true }

    var episodeLabel: String {
        let title = episodeTitle.trimmingCharacters(in: .whitespaces)
        return title.isEmpty || title == "Episode \(episode)" ? String(localized: "Episodio \(episode)") : String(localized: "Episodio \(episode) · \(title)")
    }

    var anime: Anime {
        Anime(title: seriesTitle, watchUrl: watchUrl, imageUrl: imageUrl, meta: "", rating: nil, score: nil, mediaType: nil, year: nil, titleJp: titleJp)
    }
}

struct HistoryDeletion: Codable, Hashable, Identifiable {
    let watchUrl: String
    let updatedAt: Date
    var id: String { watchUrl }
}

// MARK: - Relay payloads

struct RelayErrorResponse: Decodable { let error: String? }
struct HealthResponse: Decodable {
    let ok: Bool
    let provider: String?
    let version: String?

    /// "relay 0.2.0 · ANK", or nil for relays that predate the fields.
    var summary: String? {
        let parts = [version.map { "relay \($0)" }, provider].compactMap { $0 }
        return parts.isEmpty ? nil : parts.joined(separator: " · ")
    }
}
struct SearchResponse: Decodable { let results: [Anime] }
struct LatestResponse: Decodable { let results: [Anime]; let page: Int; let hasNext: Bool }
struct WatchRequest: Encodable { let watchUrl: String }
struct ServerResponse: Decodable { let episode: Int; let servers: [Server] }
struct PlayRequest: Encodable { let seriesKey: String; let episode: Int; let serverIndex: Int }
/// Episode counts the Continue screen uses to spot new episodes and seasons.
struct SeriesStatus: Decodable {
    let watchUrl: String
    let totalEpisodes: Int?
    let status: String?
    let latestSub: Int?
    let latestDub: Int?
    let nextSeason: Season?
    let nextEpisodeAt: Double?

    var nextEpisodeDate: Date? { upcomingDate(nextEpisodeAt) }

    func latest(for kind: StreamKind) -> Int? { kind == .dub ? latestDub : latestSub }
}
struct SeriesStatusRequest: Encodable { let watchUrls: [String] }
struct SeriesStatusResponse: Decodable { let statuses: [SeriesStatus] }
struct HistoryResponse: Decodable { let entries: [WatchHistoryEntry]; let deletions: [HistoryDeletion]? }
struct HistoryPayload: Encodable { let entries: [WatchHistoryEntry]; let deletions: [HistoryDeletion] }
struct PreferencesEnvelope: Codable { let preferences: UserPreferences? }
struct AdminUserRequest: Encodable { let token: String; let label: String }
struct AdminUserResponse: Decodable { let userId: String; let createdAt: String }

/// Provider-relative identity of a series (its `/watch/…` path). The relay
/// rebases watch URLs when the catalog moves to another domain, so history
/// entries saved under the old domain still match the same series.
func catalogPath(of watchUrl: String) -> String {
    let path = URLComponents(string: watchUrl)?.path ?? ""
    return path.isEmpty ? watchUrl : path
}

/// Whether `episode` is the last one the series will have: the announced
/// total, or the newest episode of a series that finished airing.
func isFinalEpisode(_ episode: Int, totalEpisodes: Int?, status: String?, latestEpisode: Int?) -> Bool {
    if let totalEpisodes { return episode >= totalEpisodes }
    return status == "finished" && episode >= (latestEpisode ?? episode)
}

/// A Unix timestamp as a date, when it is still in the future.
func upcomingDate(_ timestamp: Double?) -> Date? {
    guard let timestamp, timestamp > 0 else { return nil }
    let date = Date(timeIntervalSince1970: timestamp)
    return date > Date() ? date : nil
}

/// "jue 5 nov, 11:28" ("Thu, Nov 5, 11:28 AM") in the device time zone and
/// the app's language.
func airingLabel(_ date: Date) -> String {
    date.formatted(.dateTime.weekday(.abbreviated).day().month(.abbreviated).hour().minute())
}

func formatTime(_ seconds: Double) -> String {
    let value = max(0, Int(seconds.isFinite ? seconds.rounded(.down) : 0))
    let hours = value / 3600, minutes = (value % 3600) / 60, rest = value % 60
    return hours > 0 ? String(format: "%d:%02d:%02d", hours, minutes, rest) : String(format: "%d:%02d", minutes, rest)
}

/// The profile of the identity, kept on the relay (`/api/profile`): the name
/// and picture phones and TVs show, and an email for recovery (only stored
/// for now). `label` is the name the identity was created with, shown until
/// a name is set.
struct AccountProfile: Codable, Equatable {
    struct Avatar: Codable, Equatable {
        var color: String
        var emoji: String?
        /// A small JPEG or PNG, Base64.
        var photo: String?
    }

    var name: String?
    var email: String?
    var avatar: Avatar?
    var label: String?

    var displayName: String? { name ?? label }

    /// The relay accepts only these colors.
    static let colors = ["#4B49D6", "#E5484D", "#F76B15", "#E2A336", "#46A758", "#12A594", "#0090FF", "#8E4EC6", "#D6409F", "#6F6E77"]
    static let emojis = ["🦊", "🐱", "🐼", "🐸", "🐧", "🦄", "🐙", "🐯", "🌸", "⭐️", "🎮", "🍙"]
}

struct AccountEnvelope: Decodable { let profile: AccountProfile }

/// A profile change: empty strings clear the name, email and emoji; a nil
/// photo keeps the current one and an empty one removes it.
struct AccountUpdate: Encodable {
    struct Avatar: Encodable {
        let color: String
        let emoji: String
        let photo: String?
    }

    let name: String
    let email: String
    let avatar: Avatar
}
