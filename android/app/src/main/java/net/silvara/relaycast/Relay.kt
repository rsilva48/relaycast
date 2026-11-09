package net.silvara.relaycast

import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

// Payloads of the relay API (the same ones the iOS app uses).

@Serializable
data class Health(val ok: Boolean, val provider: String? = null, val version: String? = null)

@Serializable
data class Anime(
    val title: String,
    val watchUrl: String,
    val imageUrl: String? = null,
    val meta: String = "",
    val rating: String? = null,
    val score: Double? = null,
    val mediaType: String? = null,
    val year: Int? = null,
    val titleJp: String? = null,
    val episode: Int? = null,
    val subEpisodes: Int? = null,
    val dubEpisodes: Int? = null,
    val totalEpisodes: Int? = null,
    val adult: Boolean? = null,
) {
    val isAdult: Boolean get() = adult == true || rating == "R+" || rating == "Rx"

    fun displayTitle(japanese: Boolean): String = if (japanese && !titleJp.isNullOrBlank()) titleJp else title
}

@Serializable
data class LatestResponse(val results: List<Anime>, val page: Int = 1, val hasNext: Boolean = false)

@Serializable
data class Episode(val number: Int, val title: String = "", val sub: Boolean? = null, val dub: Boolean? = null) {
    fun isAvailable(kind: String): Boolean = if (kind == "dub") dub ?: true else sub ?: true

    /** The relay falls back to "Episode N"; that adds nothing next to the number. */
    val subtitle: String? get() = title.trim().takeUnless { it.isEmpty() || it == "Episode $number" }
}

@Serializable
data class Season(
    val title: String,
    val titleJp: String? = null,
    val watchUrl: String,
    val imageUrl: String? = null,
    val seasonNumber: Int = 1,
    val label: String? = null,
) {
    /** The relay's labels are English ("Season 2", "Part 2"); the Spanish UI translates those words. */
    val displayLabel: String
        get() = if (!Strings.spanish) label ?: title
        else label?.replace("Season", "Temporada")?.replace("Part", "parte")?.replace("Special", "Especial") ?: title

    fun asAnime() = Anime(title = title, watchUrl = watchUrl, imageUrl = imageUrl, titleJp = titleJp)
}

/** Episode counts of a series for Continue: the newest Sub/Dub episode and what follows it. */
@Serializable
data class SeriesStatus(
    val watchUrl: String,
    val totalEpisodes: Int? = null,
    val status: String? = null,
    val latestSub: Int? = null,
    val latestDub: Int? = null,
    val nextSeason: Season? = null,
    /** Unix seconds of the next episode's estimated release. */
    val nextEpisodeAt: Double? = null,
) {
    fun latest(kind: String): Int? = if (kind == "dub") latestDub else latestSub
}

@Serializable
private data class SeriesStatusRequest(val watchUrls: List<String>)

@Serializable
private data class SeriesStatusResponse(val statuses: List<SeriesStatus> = emptyList())

@Serializable
data class Series(
    val seriesKey: String,
    val title: String,
    val titleJp: String? = null,
    val episodes: List<Episode>,
    val seasons: List<Season> = emptyList(),
    val synopsis: String? = null,
    val rating: String? = null,
    val adult: Boolean? = null,
    val status: String? = null,
    val mediaType: String? = null,
    val score: Double? = null,
    val totalEpisodes: Int? = null,
    val imageUrl: String? = null,
    /** Unix seconds of the next episode's estimated release. */
    val nextEpisodeAt: Double? = null,
) {
    fun episodeAfter(number: Int, kind: String): Episode? = episodes.firstOrNull { it.number > number && it.isAvailable(kind) }

    fun episodeBefore(number: Int): Episode? = episodes.lastOrNull { it.number < number }
}

@Serializable
data class Server(val index: Int, val name: String, val type: String)

@Serializable
data class ServersResponse(val episode: Int, val servers: List<Server>)

@Serializable
data class SkipRange(val start: Double, val end: Double)

@Serializable
data class Playback(
    val playbackUrl: String,
    val subtitleTracks: Int = 0,
    val serverName: String,
    val serverKind: String? = null,
    val intro: SkipRange? = null,
    val outro: SkipRange? = null,
)

/** One row of the shared watch history; `updatedAt` is seconds since 2001 (as iOS stores it). */
@Serializable
data class HistoryEntry(
    val seriesTitle: String,
    val watchUrl: String,
    val imageUrl: String? = null,
    val episode: Int,
    val episodeTitle: String = "",
    val serverName: String = "",
    val positionSeconds: Double = 0.0,
    val updatedAt: Double = 0.0,
    val titleJp: String? = null,
    val serverKind: String? = null,
    val durationSeconds: Double? = null,
    val completed: Boolean? = null,
    val totalEpisodes: Int? = null,
) {
    val isCompleted: Boolean get() = completed == true

    fun asAnime() = Anime(title = seriesTitle, watchUrl = watchUrl, imageUrl = imageUrl, titleJp = titleJp)

    companion object {
        /** Seconds between 1970 and 2001, the reference date of iOS dates. */
        private const val APPLE_EPOCH_OFFSET = 978_307_200.0

        fun now(): Double = System.currentTimeMillis() / 1000.0 - APPLE_EPOCH_OFFSET
    }
}

@Serializable
data class HistoryDeletion(val watchUrl: String, val updatedAt: Double)

@Serializable
data class HistoryPayload(val entries: List<HistoryEntry>, val deletions: List<HistoryDeletion> = emptyList())

@Serializable
data class Preferences(
    val titleLanguage: String = "en",
    val defaultTrackType: String = "sub",
    val subtitleLanguage: String = "",
    val autoNext: Boolean = true,
    val autoSkipIntro: Boolean = false,
    val autoSkipOutro: Boolean = false,
    val hideAdult: Boolean = false,
    /** Seconds since 2001 (as iOS stores it); the newest copy wins on the relay. */
    val updatedAt: Double? = null,
)

@Serializable
data class PreferencesEnvelope(val preferences: Preferences? = null)

/** A profile's picture without a photo: one of the relay's colors, with an emoji or the initial. */
@Serializable
data class AccountAvatar(val color: String = "#4B49D6", val emoji: String? = null, val photo: String? = null)

/**
 * The identity's profile on the relay (`/api/profile`): the name and
 * picture phones and TVs show, and an email kept for recovery. `label` is
 * the name it was created with, shown until a name is set.
 */
@Serializable
data class AccountProfile(val name: String? = null, val email: String? = null, val avatar: AccountAvatar? = null, val label: String? = null) {
    val displayName: String? get() = name ?: label
}

@Serializable
private data class AccountEnvelope(val profile: AccountProfile)

@Serializable
data class PairingStart(val pairingId: String, val code: String, val expiresAt: String)

/** A TV of this identity, as the phone app lists it. */
@Serializable
data class RemoteTv(
    val id: String,
    val name: String,
    val state: DeviceState? = null,
    /** Milliseconds since the TV reported `state`. */
    val stateAge: Double? = null,
    /** Whether the TV can open the app by itself (null on older relays). */
    val canOpen: Boolean? = null,
    /**
     * Whether this phone and the TV reached the relay from the same network;
     * null when unknown. Not a condition: commands go through the relay.
     */
    val sameNetwork: Boolean? = null,
)

@Serializable
private data class RemoteTvs(val devices: List<RemoteTv> = emptyList())

@Serializable
private data class PairApproval(val code: String, val label: String)

@Serializable
private data class RegisterUser(val token: String, val label: String)

@Serializable
data class CreatedIdentity(val token: String, val label: String? = null)

@Serializable
private data class IdentityRequest(val label: String)

@Serializable
data class PairingStatus(val status: String, val token: String? = null, val label: String? = null)

/** A command sent from a phone app (remote control). */
@Serializable
data class RemoteCommand(
    val id: String = "",
    /** Order of the TV's commands; the next poll acknowledges it. */
    val seq: Long = 0,
    val type: String,
    val watchUrl: String? = null,
    val episode: Int? = null,
    val kind: String? = null,
    /** Relative jump for "seek" (negative goes back). */
    val seconds: Double? = null,
    /** Absolute position for "seek", from scrubbing on the phone. */
    val position: Double? = null,
    /** Volume steps for "volume" (positive raises it). */
    val steps: Int? = null,
    /** Subtitle track for "subtitles" ([SubtitleOption.id], or "off"). */
    val track: String? = null,
)

@Serializable
data class RemoteCommands(val commands: List<RemoteCommand> = emptyList())

/** What this TV is doing, shown in the phone's remote control. */
@Serializable
data class DeviceState(
    val status: String = "idle",
    val watchUrl: String? = null,
    val seriesTitle: String? = null,
    val titleJp: String? = null,
    val imageUrl: String? = null,
    val episode: Int? = null,
    val episodeTitle: String? = null,
    val kind: String? = null,
    val position: Double? = null,
    val duration: Double? = null,
    /** "Skip intro"/"Skip outro" (in the TV's language) while that skip is offered. */
    val skip: String? = null,
    /** Subtitle tracks of the episode, and the one shown (null: off). */
    val subtitles: List<SubtitleOption> = emptyList(),
    val subtitle: String? = null,
)

@Serializable
data class SubtitleOption(val id: String, val label: String)

@Serializable
private data class DeviceReport(val name: String, val state: DeviceState, val canOpen: Boolean, val awake: Boolean)

@Serializable
private data class OkResponse(val ok: Boolean = true)

@Serializable
private data class WatchRequest(val watchUrl: String)

@Serializable
private data class PlayRequest(val seriesKey: String, val episode: Int, val serverIndex: Int)

@Serializable
private data class ErrorResponse(val error: String? = null)

class RelayException(message: String, val status: Int? = null, val offline: Boolean = false) : Exception(message)

/** The `/watch/…` path that identifies a series across catalog domains. */
fun catalogPath(watchUrl: String): String = runCatching { URI(watchUrl).path }.getOrNull()?.takeIf { it.isNotEmpty() } ?: watchUrl

fun formatTime(seconds: Double): String {
    val value = if (seconds.isFinite()) seconds.toLong().coerceAtLeast(0) else 0
    val hours = value / 3600
    val minutes = (value % 3600) / 60
    val rest = value % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, rest) else "%d:%02d".format(minutes, rest)
}

/** Calls to one relay with one identity (token). */
class RelayClient(val baseUrl: String, private val token: String) {
    companion object {
        /**
         * Relays whose /health said provider "LOCAL" in this process: a Relay
         * Server with the user's own library. Only those get catalog calls; any
         * other compatible server keeps accounts, profiles, history and TVs.
         */
        private val ownLibraries = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

        fun hasOwnLibrary(baseUrl: String): Boolean = baseUrl in ownLibraries

        /** The library API (latest, filters, series, playback, release status, schedule). */
        fun isCatalogPath(path: String): Boolean =
            listOf("/api/latest", "/api/catalog", "/api/series", "/api/play", "/api/schedule").any { path == it || path.startsWith("$it/") }

        val json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            encodeDefaults = true
        }

        val http: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .build()

        /** `https://host[:port]` from what the user typed, or null. */
        fun canonicalUrl(scheme: String, host: String): String? {
            val cleaned = host.trim().removePrefix("https://").removePrefix("http://").trimEnd('/')
            if (cleaned.isEmpty()) return null
            val url = "$scheme://$cleaned".toHttpUrlOrNull() ?: return null
            val port = if ((url.scheme == "https" && url.port == 443) || (url.scheme == "http" && url.port == 80)) "" else ":${url.port}"
            return "${url.scheme}://${url.host}$port"
        }
    }

    suspend fun health(): Health = get<Health>("/health").also { health ->
        if (health.ok && health.provider == "LOCAL") ownLibraries.add(baseUrl) else ownLibraries.remove(baseUrl)
    }

    /** Whether this relay offers an own library, asking it once per process. */
    suspend fun ownLibrary(): Boolean = hasOwnLibrary(baseUrl) || runCatching { health() }.getOrNull()?.let { it.ok && it.provider == "LOCAL" } == true

    suspend fun latest(page: Int, language: String? = null, keyword: String? = null, filters: CatalogFilters = CatalogFilters()): LatestResponse {
        val query = buildList {
            add("page" to page.toString())
            if (language != null) add("language" to language)
            if (!keyword.isNullOrBlank()) add("q" to keyword)
            addAll(filters.query())
        }
        return get("/api/latest", query)
    }

    /** The relay's filter option lists (null on older relays). */
    suspend fun catalogFilterOptions(): CatalogFilterOptions? =
        runCatching { get<CatalogFilterOptions>("/api/catalog/filters") }.getOrNull()?.takeIf { it.genres.isNotEmpty() }

    suspend fun series(watchUrl: String): Series = post("/api/series", json.encodeToString(WatchRequest.serializer(), WatchRequest(watchUrl)))

    suspend fun servers(seriesKey: String, episode: Int): List<Server> =
        get<ServersResponse>("/api/series/$seriesKey/servers", listOf("episode" to episode.toString())).servers

    suspend fun play(seriesKey: String, episode: Int, serverIndex: Int): Playback =
        post("/api/play", json.encodeToString(PlayRequest.serializer(), PlayRequest(seriesKey, episode, serverIndex)))

    suspend fun history(): HistoryPayload = get("/api/history")

    suspend fun saveHistory(entries: List<HistoryEntry>, deletions: List<HistoryDeletion> = emptyList()): HistoryPayload =
        send("PUT", "/api/history", emptyList(), json.encodeToString(HistoryPayload.serializer(), HistoryPayload(entries, deletions)))

    suspend fun preferences(): Preferences = get<PreferencesEnvelope>("/api/preferences").preferences ?: Preferences()

    /** Saves the identity's preferences; the reply is the copy the relay kept (the newest). */
    suspend fun savePreferences(preferences: Preferences): Preferences =
        send<PreferencesEnvelope>("PUT", "/api/preferences", emptyList(), json.encodeToString(PreferencesEnvelope.serializer(), PreferencesEnvelope(preferences)))
            .preferences ?: preferences

    suspend fun startPairing(): PairingStart = post("/api/pair/start", "{}")

    /** A new identity created by this one (a new profile; no administrator token). */
    suspend fun createIdentity(label: String): CreatedIdentity =
        post("/api/identities", json.encodeToString(IdentityRequest.serializer(), IdentityRequest(label)))

    suspend fun pairingStatus(pairingId: String): PairingStatus = get("/api/pair/status", listOf("pairingId" to pairingId))

    /** Status of up to 50 series (older relays answer 404). */
    suspend fun seriesStatuses(watchUrls: List<String>): List<SeriesStatus> =
        post<SeriesStatusResponse>("/api/series/status", json.encodeToString(SeriesStatusRequest.serializer(), SeriesStatusRequest(watchUrls.take(50)))).statuses

    /** Approves a TV's pairing code with this identity (the TV gets this token). */
    suspend fun approvePairing(code: String, label: String) {
        send<OkResponse>("POST", "/api/pair/approve", emptyList(), json.encodeToString(PairApproval.serializer(), PairApproval(code, label)))
    }

    /** Episodes the catalog expects to release (release reminders). */
    suspend fun schedule(): List<ScheduleEntry> = get<ScheduleReply>("/api/schedule").entries

    /** A temporary, token-free link to one playback for a Google Cast receiver. */
    suspend fun castShare(playbackUrl: String): CastShare =
        post("/api/cast", json.encodeToString(CastShareRequest.serializer(), CastShareRequest(playbackUrl)))

    /** Revokes a cast link once the receiver no longer needs it. */
    suspend fun revokeCast(id: String) {
        send<OkResponse>("DELETE", "/api/cast/$id", emptyList(), null)
    }

    /** The TVs of this identity (phone remote control). */
    suspend fun tvs(): List<RemoteTv> = get<RemoteTvs>("/api/devices").devices

    suspend fun sendCommand(deviceId: String, command: RemoteCommand) {
        send<OkResponse>("POST", "/api/devices/$deviceId/commands", emptyList(), json.encodeToString(RemoteCommand.serializer(), command))
    }

    suspend fun reportDevice(deviceId: String, name: String, state: DeviceState, canOpen: Boolean, awake: Boolean) {
        send<OkResponse>("PUT", "/api/devices/$deviceId", emptyList(), json.encodeToString(DeviceReport.serializer(), DeviceReport(name, state, canOpen, awake)))
    }

    /** Long poll: returns when a command arrives, or empty after about 25 s. */
    suspend fun waitForCommands(deviceId: String, after: Long): List<RemoteCommand> =
        get<RemoteCommands>("/api/devices/$deviceId/commands", listOf("after" to after.toString())).commands

    suspend fun account(): AccountProfile = get<AccountEnvelope>("/api/profile").profile

    /**
     * Saves the profile. Empty strings clear the name, email and emoji; a
     * null photo keeps the current one and an empty one removes it.
     */
    suspend fun saveAccount(name: String, email: String, color: String, emoji: String, photo: String?): AccountProfile {
        val body = kotlinx.serialization.json.buildJsonObject {
            put("name", kotlinx.serialization.json.JsonPrimitive(name))
            put("email", kotlinx.serialization.json.JsonPrimitive(email))
            put("avatar", kotlinx.serialization.json.buildJsonObject {
                put("color", kotlinx.serialization.json.JsonPrimitive(color))
                put("emoji", kotlinx.serialization.json.JsonPrimitive(emoji))
                photo?.let { put("photo", kotlinx.serialization.json.JsonPrimitive(it)) }
            })
        }
        return send<AccountEnvelope>("PUT", "/api/profile", emptyList(), body.toString()).profile
    }

    /** Renames the profile and keeps its email and picture (the TV). */
    suspend fun renameAccount(name: String): AccountProfile =
        send<AccountEnvelope>("PUT", "/api/profile", emptyList(), kotlinx.serialization.json.buildJsonObject { put("name", kotlinx.serialization.json.JsonPrimitive(name)) }.toString()).profile

    private suspend inline fun <reified T> get(path: String, query: List<Pair<String, String>> = emptyList()): T =
        send("GET", path, query, null)

    private suspend inline fun <reified T> post(path: String, body: String): T = send("POST", path, emptyList(), body)

    /**
     * Registers a new identity with the relay's administrator token (the
     * first identity of a phone). The administrator token is not kept.
     */
    suspend fun registerUser(adminToken: String, token: String, label: String) {
        sendRaw("POST", "/api/admin/users", emptyList(), json.encodeToString(RegisterUser.serializer(), RegisterUser(token, label)), adminToken)
    }

    private suspend inline fun <reified T> send(method: String, path: String, query: List<Pair<String, String>>, body: String?): T {
        val text = sendRaw(method, path, query, body)
        return try {
            json.decodeFromString<T>(text)
        } catch (error: Exception) {
            throw RelayException(Strings.get(R.string.error_unreadable_reply))
        }
    }

    private suspend fun sendRaw(method: String, path: String, query: List<Pair<String, String>>, body: String?, adminToken: String? = null): String = withContext(Dispatchers.IO) {
        // The library API only for a server that said it serves the user's
        // own library; never a third-party catalog, whatever screen asks.
        // Asked once per process (a background worker starts without it).
        if (isCatalogPath(path) && !hasOwnLibrary(baseUrl)) {
            val health = runCatching { json.decodeFromString<Health>(sendRaw("GET", "/health", emptyList(), null)) }.getOrNull()
            if (health?.ok == true && health.provider == "LOCAL") ownLibraries.add(baseUrl)
            else throw RelayException(Strings.get(R.string.error_no_own_library))
        }
        val url = ("$baseUrl$path").toHttpUrlOrNull()?.newBuilder()?.apply {
            query.forEach { (name, value) -> addQueryParameter(name, value) }
        }?.build() ?: throw RelayException(Strings.get(R.string.error_invalid_relay_url))
        val request = Request.Builder()
            .url(url)
            .method(method, body?.toRequestBody("application/json".toMediaType()))
            .apply {
                if (adminToken != null) header("X-Bridge-Admin-Token", adminToken)
                else if (token.isNotEmpty()) header("X-Bridge-Token", token)
            }
            .build()
        val response = try {
            http.newCall(request).execute()
        } catch (error: IOException) {
            throw if (!Connectivity.isOnline) {
                RelayException(Strings.get(R.string.error_offline), offline = true)
            } else {
                RelayException(Strings.get(R.string.error_unreachable))
            }
        }
        response.use {
            val text = it.body.string()
            if (!it.isSuccessful) {
                if (it.code == 401) {
                    throw RelayException(Strings.get(if (adminToken != null) R.string.error_admin_token_invalid else R.string.error_identity_required), 401)
                }
                val message = runCatching { json.decodeFromString(ErrorResponse.serializer(), text).error }.getOrNull()
                throw RelayException(message ?: Strings.get(R.string.error_http, it.code), it.code)
            }
            text
        }
    }
}
