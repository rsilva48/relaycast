package net.silvara.relaycast

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.serialization.builtins.ListSerializer

/** An episode ready to play, with what is needed to move to the next one. */
data class PlaybackRequest(
    val anime: Anime,
    val series: Series,
    val episode: Episode,
    val kind: String,
    val playback: Playback,
    /** Phone: played from a download (its series is the downloaded episodes when `seriesKey` is empty). */
    val downloaded: Boolean = false,
)

/** The active profile: its preferences and its watch history (shared with the phone). */
class Session(val profile: Profile, val client: RelayClient, context: Context) {
    private val storage = context.applicationContext.getSharedPreferences("relaycast", Context.MODE_PRIVATE)
    private val historyKey = "history.${profile.id}"
    private val historySerializer = ListSerializer(HistoryEntry.serializer())
    var preferences by mutableStateOf(Preferences())
        private set
    var history by mutableStateOf(loadHistory())
        private set
    var historyError by mutableStateOf<String?>(null)
        private set
    private val accountKey = "account.${profile.id}"
    /** Name, email and picture of the identity (cached for offline use). */
    var account by mutableStateOf(loadAccount())
        private set

    private fun loadAccount(): AccountProfile? = storage.getString(accountKey, null)
        ?.let { runCatching { RelayClient.json.decodeFromString(AccountProfile.serializer(), it) }.getOrNull() }

    private fun storeAccount(value: AccountProfile) {
        account = value
        storage.edit().putString(accountKey, RelayClient.json.encodeToString(AccountProfile.serializer(), value)).apply()
    }

    /** Saves the profile on the relay (see [RelayClient.saveAccount]). */
    suspend fun saveAccount(name: String, email: String, color: String, emoji: String, photo: String?) {
        storeAccount(client.saveAccount(name, email, color, emoji, photo))
    }

    /** Relay episode counts by catalog path; only finished entries need them. */
    var statuses by mutableStateOf<Map<String, SeriesStatus>>(emptyMap())
        private set

    val japanese: Boolean get() = preferences.titleLanguage == "jp"

    private fun loadHistory(): List<HistoryEntry> = storage.getString(historyKey, null)
        ?.let { runCatching { RelayClient.json.decodeFromString(historySerializer, it) }.getOrNull() }
        ?: emptyList()

    private fun persistHistory() {
        storage.edit().putString(historyKey, RelayClient.json.encodeToString(historySerializer, history)).apply()
    }

    fun initialLoadTimedOut() {
        if (history.isEmpty()) historyError = Strings.get(R.string.history_load_failed)
    }

    /** The account's name, email and picture from the relay now (after identifying with an existing account). */
    suspend fun refreshAccount() {
        runCatching { client.account() }.onSuccess(::storeAccount)
    }

    suspend fun refresh() {
        runCatching { client.preferences() }.onSuccess { preferences = it }
        // Relays before 0.3.0 have no profile: the cached one stays.
        runCatching { client.account() }.onSuccess(::storeAccount)
        try {
            val remote = client.history().entries
            // Progress saved here while offline (a download) goes up now.
            val newer = history.filter { local ->
                remote.firstOrNull { catalogPath(it.watchUrl) == catalogPath(local.watchUrl) }?.let { local.updatedAt > it.updatedAt } == true
            }
            if (newer.isNotEmpty()) runCatching { client.saveHistory(newer) }
            history = (newer + remote.filterNot { entry -> newer.any { catalogPath(it.watchUrl) == catalogPath(entry.watchUrl) } })
                .sortedByDescending { it.updatedAt }
            persistHistory()
            historyError = null
        } catch (error: RelayException) {
            historyError = error.message
        }
    }

    fun entry(watchUrl: String): HistoryEntry? = history.firstOrNull { catalogPath(it.watchUrl) == catalogPath(watchUrl) }

    /** Where an episode resumes; a finished episode starts over. */
    fun position(watchUrl: String, episode: Int): Double =
        entry(watchUrl)?.takeIf { it.episode == episode && !it.isCompleted }?.positionSeconds ?: 0.0

    /** Saves progress locally and on the relay (newest wins there). */
    suspend fun record(entry: HistoryEntry) {
        history = listOf(entry) + history.filterNot { catalogPath(it.watchUrl) == catalogPath(entry.watchUrl) }
        persistHistory()
        runCatching { client.saveHistory(listOf(entry)) }
    }

    /** Loads the status of the series whose last watched episode is finished. */
    suspend fun loadStatuses() {
        val urls = history.filter { it.isCompleted }.map { it.watchUrl }.distinctBy(::catalogPath)
        if (urls.isEmpty()) return
        try {
            statuses = statuses + client.seriesStatuses(urls).associateBy { catalogPath(it.watchUrl) }
        } catch (error: RelayException) {
            // Older relays: entries stay grouped by their own details.
        }
    }

    /** Changes the identity's preferences here and on the relay (and so on its other devices). */
    suspend fun updatePreferences(change: (Preferences) -> Preferences) {
        val updated = change(preferences).copy(updatedAt = HistoryEntry.now())
        preferences = updated
        runCatching { client.savePreferences(updated) }.onSuccess { preferences = it }
    }

    /** Removes a series from Continue, here and (as a deletion) on the relay and the phone. */
    suspend fun remove(entry: HistoryEntry) {
        history = history.filterNot { catalogPath(it.watchUrl) == catalogPath(entry.watchUrl) }
        persistHistory()
        runCatching { client.saveHistory(emptyList(), listOf(HistoryDeletion(entry.watchUrl, HistoryEntry.now()))) }
    }

    /**
     * Resolves an episode: the preferred server first, then the other
     * servers of the same kind (servers differ between episodes and can
     * fail), then any. An expired series session is reloaded once.
     */
    suspend fun resolve(anime: Anime, series: Series, episode: Episode, kind: String, preferredServer: String?): PlaybackRequest {
        var current = series
        suspend fun <T> retrying(block: suspend (Series) -> T): T = try {
            block(current)
        } catch (error: RelayException) {
            if (error.status != 410) throw error
            current = client.series(anime.watchUrl)
            block(current)
        }

        val servers = retrying { client.servers(it.seriesKey, episode.number) }
        val ofKind = servers.filter { it.type == kind }
        val ordered = ofKind.filter { it.name == preferredServer } + ofKind.filter { it.name != preferredServer } + servers.filter { it.type != kind }
        if (ordered.isEmpty()) throw RelayException(Strings.get(R.string.no_servers, episode.number))
        var lastError: Exception? = null
        for (server in ordered) {
            try {
                val playback = retrying { client.play(it.seriesKey, episode.number, server.index) }
                return PlaybackRequest(anime, current, episode, playback.serverKind ?: server.type, playback)
            } catch (error: RelayException) {
                if (error.offline) throw error
                lastError = error
            }
        }
        throw lastError ?: RelayException(Strings.get(R.string.open_episode_failed_short, episode.number))
    }
}
