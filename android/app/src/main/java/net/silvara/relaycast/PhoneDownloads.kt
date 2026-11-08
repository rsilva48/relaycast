@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package net.silvara.relaycast

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadHelper
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.Scheduler
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import coil3.compose.AsyncImage
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.Serializable

/** What a download needs to be listed and played offline; stored in its DownloadRequest. */
@Serializable
internal data class DownloadInfo(
    val watchUrl: String,
    val seriesTitle: String,
    val titleJp: String? = null,
    val imageUrl: String? = null,
    val episode: Int,
    val episodeTitle: String = "",
    val kind: String,
    val serverName: String,
    val intro: SkipRange? = null,
    val outro: SkipRange? = null,
    val totalEpisodes: Int? = null,
    /** Milliseconds since 1970. */
    val createdAt: Long,
)

/** A download as the screens show it. */
internal data class EpisodeDownload(val id: String, val uri: String, val info: DownloadInfo, val state: Int, val percent: Float, val bytes: Long) {
    val isCompleted: Boolean get() = state == Download.STATE_COMPLETED
    val isFailed: Boolean get() = state == Download.STATE_FAILED
    val isActive: Boolean get() = state == Download.STATE_QUEUED || state == Download.STATE_DOWNLOADING || state == Download.STATE_RESTARTING

    fun matches(watchUrl: String, episode: Int, kind: String? = null): Boolean =
        info.episode == episode && (kind == null || info.kind == kind) && catalogPath(info.watchUrl) == catalogPath(watchUrl)
}

/**
 * Offline episodes, as on iOS: the HLS stream with every subtitle language,
 * kept per device (not per identity) in the app's storage. Media3 downloads
 * them in DownloadService; the player reads them from the same cache.
 */
internal object PhoneDownloads {
    private const val CHANNEL = "downloads"
    private const val KEY_AUTO_DELETE = "downloadsAutoDelete"
    private const val KEY_WATCHED = "downloadsWatched"
    private var manager: DownloadManager? = null
    private var cache: SimpleCache? = null
    val all = MutableStateFlow<List<EpisodeDownload>>(emptyList())

    private fun prefs(context: Context) = context.getSharedPreferences("relaycast", Context.MODE_PRIVATE)

    private fun http() = DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true)

    /** The download manager; created once, on the main thread. */
    @Synchronized
    fun manager(context: Context): DownloadManager {
        manager?.let { return it }
        val app = context.applicationContext
        val database = StandaloneDatabaseProvider(app)
        val stored = SimpleCache(File(app.getExternalFilesDir(null) ?: app.filesDir, "downloads"), NoOpCacheEvictor(), database)
        cache = stored
        val created = DownloadManager(app, database, stored, http(), Executors.newFixedThreadPool(4))
        created.maxParallelDownloads = 2
        created.addListener(object : DownloadManager.Listener {
            override fun onInitialized(downloadManager: DownloadManager) = publish()
            override fun onDownloadChanged(downloadManager: DownloadManager, download: Download, finalException: Exception?) = publish()
            override fun onDownloadRemoved(downloadManager: DownloadManager, download: Download) = publish()
        })
        manager = created
        publish()
        return created
    }

    /** Re-reads the downloads; the progress of running ones is not an event, so screens poll this. */
    fun publish() {
        val current = manager ?: return
        val found = mutableListOf<Download>()
        runCatching { current.downloadIndex.getDownloads().use { cursor -> while (cursor.moveToNext()) found += cursor.download } }
        // Running downloads carry fresher progress than the index.
        val live = current.currentDownloads.associateBy { it.request.id }
        all.value = found.mapNotNull { stored ->
            val download = live[stored.request.id] ?: stored
            val info = runCatching { RelayClient.json.decodeFromString(DownloadInfo.serializer(), String(download.request.data)) }.getOrNull() ?: return@mapNotNull null
            EpisodeDownload(download.request.id, download.request.uri.toString(), info, download.state, download.percentDownloaded.coerceAtLeast(0f), download.bytesDownloaded)
        }
    }

    /** The player's sources: a downloaded stream is read from storage, anything else from the network. */
    fun mediaSourceFactory(context: Context): MediaSource.Factory {
        manager(context)
        val upstream = DefaultDataSource.Factory(context, http())
        return DefaultMediaSourceFactory(
            CacheDataSource.Factory().setCache(cache!!).setUpstreamDataSourceFactory(upstream).setCacheWriteDataSinkFactory(null),
        )
    }

    /**
     * The media item of a downloaded stream: only the renditions stored
     * (video quality and subtitles), so nothing is asked of the network.
     */
    fun mediaItem(uri: String): MediaItem? {
        val target = all.value.firstOrNull { it.isCompleted && it.uri == uri } ?: return null
        return runCatching { manager?.downloadIndex?.getDownload(target.id)?.request?.toMediaItem() }.getOrNull()
    }

    /** The download of an episode: completed first, then running, then failed. */
    fun find(watchUrl: String, episode: Int, kind: String?): EpisodeDownload? =
        all.value.filter { it.matches(watchUrl, episode, kind) }.minByOrNull { if (it.isCompleted) 0 else if (it.isActive) 1 else 2 }

    fun completed(watchUrl: String, episode: Int, kind: String?): EpisodeDownload? =
        all.value.firstOrNull { it.isCompleted && it.matches(watchUrl, episode, kind) }

    fun hasAny(watchUrl: String): Boolean = all.value.any { it.isCompleted && catalogPath(it.info.watchUrl) == catalogPath(watchUrl) }

    /** Downloads an episode just resolved on the relay (it keeps the source for two hours). */
    suspend fun start(context: Context, request: PlaybackRequest) {
        manager(context)
        if (find(request.anime.watchUrl, request.episode.number, request.kind)?.let { !it.isFailed } == true) return
        val info = DownloadInfo(
            watchUrl = request.anime.watchUrl,
            seriesTitle = request.series.title,
            titleJp = request.series.titleJp ?: request.anime.titleJp,
            imageUrl = request.anime.imageUrl ?: request.series.imageUrl,
            episode = request.episode.number,
            episodeTitle = request.episode.title,
            kind = request.kind,
            serverName = request.playback.serverName,
            intro = request.playback.intro,
            outro = request.playback.outro,
            totalEpisodes = request.series.totalEpisodes,
            createdAt = System.currentTimeMillis(),
        )
        val helper = DownloadHelper.Factory()
            .setDataSourceFactory(http())
            .setRenderersFactory(DefaultRenderersFactory(context))
            .create(MediaItem.fromUri(request.playback.playbackUrl))
        try {
            suspendCancellableCoroutine { continuation ->
                helper.prepare(object : DownloadHelper.Callback {
                    override fun onPrepared(helper: DownloadHelper, tracksInfoAvailable: Boolean) = continuation.resume(Unit)
                    override fun onPrepareError(helper: DownloadHelper, e: IOException) =
                        continuation.resumeWithException(RelayException(Strings.get(R.string.download_prepare_failed, e.message.orEmpty())))
                })
            }
            // Every subtitle language of the stream, as on iOS.
            val languages = (0 until helper.periodCount).flatMap { period ->
                helper.getTracks(period).groups.filter { it.type == C.TRACK_TYPE_TEXT }
                    .flatMap { group -> (0 until group.length).mapNotNull { group.getTrackFormat(it).language } }
            }.distinct()
            helper.addTextLanguagesToSelection(true, *languages.toTypedArray())
            // A fresh id per attempt: a retry never merges with the failed copy.
            val id = "${catalogPath(info.watchUrl)}#${info.episode}#${info.kind}#${info.createdAt}"
            val download = helper.getDownloadRequest(id, RelayClient.json.encodeToString(DownloadInfo.serializer(), info).toByteArray())
            DownloadService.sendAddDownload(context, PhoneDownloadService::class.java, download, false)
        } finally {
            helper.release()
        }
    }

    /** Downloads the episode again, preferring another server of the same kind. */
    suspend fun retry(context: Context, session: Session, target: EpisodeDownload) {
        val info = target.info
        val anime = Anime(title = info.seriesTitle, watchUrl = info.watchUrl, imageUrl = info.imageUrl, titleJp = info.titleJp)
        val series = session.client.series(info.watchUrl)
        val episode = series.episodes.firstOrNull { it.number == info.episode } ?: throw RelayException(Strings.get(R.string.episode_gone, info.episode))
        val other = session.client.servers(series.seriesKey, episode.number).firstOrNull { it.type == info.kind && it.name != info.serverName }?.name
        val request = session.resolve(anime, series, episode, info.kind, other ?: info.serverName)
        if (request.kind != info.kind) throw RelayException(Strings.get(R.string.episode_no_kind_servers, info.episode, kindLabel(info.kind)))
        remove(context, target)
        start(context, request)
    }

    fun remove(context: Context, target: EpisodeDownload) {
        DownloadService.sendRemoveDownload(context, PhoneDownloadService::class.java, target.id, false)
        prefs(context).edit().putStringSet(KEY_WATCHED, watched(context) - target.id).apply()
    }

    fun removeAll(context: Context) {
        DownloadService.sendRemoveAllDownloads(context, PhoneDownloadService::class.java, false)
        prefs(context).edit().remove(KEY_WATCHED).apply()
    }

    fun totalBytes(): Long = all.value.sumOf { it.bytes }

    /** "Delete watched downloads" (on by default, as on iOS). */
    fun autoDelete(context: Context) = prefs(context).getBoolean(KEY_AUTO_DELETE, true)

    fun setAutoDelete(context: Context, value: Boolean) = prefs(context).edit().putBoolean(KEY_AUTO_DELETE, value).apply()

    private fun watched(context: Context): Set<String> = prefs(context).getStringSet(KEY_WATCHED, null).orEmpty()

    /** The player finished this episode here. */
    fun markWatched(context: Context, watchUrl: String, episode: Int) {
        val ids = all.value.filter { it.isCompleted && it.matches(watchUrl, episode) }.map { it.id }
        if (ids.isNotEmpty()) prefs(context).edit().putStringSet(KEY_WATCHED, watched(context) + ids).apply()
    }

    /**
     * Deletes the downloads already watched: finished here, or finished on
     * another device with the same identity. Opening a later episode does
     * not delete earlier ones.
     */
    fun cleanUp(context: Context, session: Session?) {
        manager(context)
        if (!autoDelete(context)) return
        val watched = watched(context)
        all.value.filter { download ->
            download.isCompleted && (download.id in watched ||
                session?.entry(download.info.watchUrl)?.let { it.episode == download.info.episode && it.isCompleted } == true)
        }.forEach { remove(context, it) }
    }

    /**
     * A downloaded episode ready to play. Its series is the downloaded
     * episodes of the same kind (seriesKey empty), so previous and next move
     * between downloads.
     */
    fun playback(target: EpisodeDownload): PlaybackRequest {
        val info = target.info
        val episodes = all.value
            .filter { it.isCompleted && it.info.kind == info.kind && catalogPath(it.info.watchUrl) == catalogPath(info.watchUrl) }
            .distinctBy { it.info.episode }.sortedBy { it.info.episode }
            .map { Episode(it.info.episode, it.info.episodeTitle, sub = info.kind == "sub", dub = info.kind == "dub") }
        val series = Series(seriesKey = "", title = info.seriesTitle, titleJp = info.titleJp, episodes = episodes, totalEpisodes = info.totalEpisodes, imageUrl = info.imageUrl)
        return PlaybackRequest(
            anime = Anime(title = info.seriesTitle, watchUrl = info.watchUrl, imageUrl = info.imageUrl, titleJp = info.titleJp),
            series = series,
            episode = Episode(info.episode, info.episodeTitle, sub = info.kind == "sub", dub = info.kind == "dub"),
            kind = info.kind,
            playback = Playback(playbackUrl = target.uri, serverName = info.serverName, serverKind = info.kind, intro = info.intro, outro = info.outro),
            downloaded = true,
        )
    }

    /** The series screen without a connection: its downloaded episodes, in both kinds. */
    fun offlineSeries(watchUrl: String): Series? {
        val stored = all.value.filter { it.isCompleted && catalogPath(it.info.watchUrl) == catalogPath(watchUrl) }
        val first = stored.firstOrNull()?.info ?: return null
        val episodes = stored.groupBy { it.info.episode }.toSortedMap().map { (number, copies) ->
            Episode(number, copies.first().info.episodeTitle, sub = copies.any { it.info.kind == "sub" }, dub = copies.any { it.info.kind == "dub" })
        }
        return Series(seriesKey = "", title = first.seriesTitle, titleJp = first.titleJp, episodes = episodes, totalEpisodes = first.totalEpisodes, imageUrl = first.imageUrl)
    }

    fun notificationChannel() = CHANNEL
}

internal fun kindLabel(kind: String) = if (kind == "dub") "Dub" else "Sub"

/** The downloads, refreshed every second while one is running. */
@Composable
internal fun rememberDownloads(): State<List<EpisodeDownload>> {
    val context = LocalContext.current
    val state = PhoneDownloads.all.collectAsState()
    LaunchedEffect(Unit) {
        PhoneDownloads.manager(context)
        while (true) {
            delay(1000)
            if (PhoneDownloads.all.value.any { it.isActive }) PhoneDownloads.publish()
        }
    }
    return state
}

/** "Downloading · Sub · server · 45 %", "Sub · server · 230 MB", … */
internal fun downloadStatus(context: Context, download: EpisodeDownload): String {
    val kind = kindLabel(download.info.kind)
    return when {
        download.isCompleted -> listOf(kind, download.info.serverName, Formatter.formatShortFileSize(context, download.bytes)).joinToString(" · ")
        download.isFailed -> context.getString(R.string.status_download_failed, kind, download.info.serverName)
        download.percent <= 0f -> context.getString(R.string.status_waiting, kind, download.info.serverName)
        else -> context.getString(R.string.status_downloading, kind, download.info.serverName, download.percent.toInt())
    }
}

/** Downloads in the background with a progress notification; Media3 runs it. */
class PhoneDownloadService : DownloadService(
    FOREGROUND_NOTIFICATION_ID,
    DEFAULT_FOREGROUND_NOTIFICATION_UPDATE_INTERVAL,
    PhoneDownloads.notificationChannel(),
    R.string.downloads_channel,
    0,
) {
    override fun getDownloadManager(): DownloadManager = PhoneDownloads.manager(this)

    override fun getScheduler(): Scheduler? = null

    override fun getForegroundNotification(downloads: MutableList<Download>, notMetRequirements: Int): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val title = downloads.firstNotNullOfOrNull { download ->
            runCatching { RelayClient.json.decodeFromString(DownloadInfo.serializer(), String(download.request.data)) }.getOrNull()
                ?.let { getString(R.string.series_episode, it.seriesTitle, it.episode) }
        }
        return DownloadNotificationHelper(this, PhoneDownloads.notificationChannel())
            .buildProgressNotification(this, android.R.drawable.stat_sys_download, open, title, downloads, notMetRequirements)
    }

    private companion object {
        const val FOREGROUND_NOTIFICATION_ID = 4201
    }
}

/** The Downloads tab: grouped by series, playable without a connection. */
@Composable
internal fun PhoneDownloadsScreen(session: Session?, onPlay: (PlaybackRequest) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val downloads by rememberDownloads()
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(session) { PhoneDownloads.cleanUp(context, session) }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.tab_downloads)) },
            actions = {
                if (downloads.isNotEmpty()) Text(
                    Formatter.formatShortFileSize(context, downloads.sumOf { it.bytes }),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 16.dp),
                )
            },
        )
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }
        if (downloads.isEmpty()) {
            PhoneMessage(stringResource(R.string.no_downloads), stringResource(R.string.no_downloads_text), icon = PhoneIcons.Download)
            return@Column
        }
        val japanese = session?.japanese == true
        val groups = downloads.groupBy { catalogPath(it.info.watchUrl) }.values
            .sortedByDescending { group -> group.maxOf { it.info.createdAt } }
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
            groups.forEach { group ->
                val first = group.first().info
                item(key = "header:${catalogPath(first.watchUrl)}") {
                    Text(
                        (if (japanese) first.titleJp else null) ?: first.seriesTitle,
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp),
                    )
                }
                items(group.sortedWith(compareBy({ it.info.episode }, { it.info.kind })), key = { it.id }) { download ->
                    var menu by remember { mutableStateOf(false) }
                    val info = download.info
                    val watchedUntil = session?.entry(info.watchUrl)?.takeIf { download.isCompleted && it.episode == info.episode && !it.isCompleted }?.positionSeconds
                    Box {
                        ListItem(
                            leadingContent = {
                                AsyncImage(
                                    model = info.imageUrl,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.width(40.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
                                )
                            },
                            headlineContent = {
                                val title = info.episodeTitle.trim().takeUnless { it.isEmpty() || it == "Episode ${info.episode}" }
                                Text(listOfNotNull(stringResource(R.string.episode_number, info.episode), title).joinToString(" · "), maxLines = 2, overflow = TextOverflow.Ellipsis)
                            },
                            supportingContent = {
                                Column {
                                    Text(
                                        listOfNotNull(downloadStatus(context, download), watchedUntil?.let { stringResource(R.string.watched_until, formatTime(it)) }).joinToString(" · "),
                                        color = if (download.isFailed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    if (download.isActive) {
                                        Spacer(Modifier.height(6.dp))
                                        LinearProgressIndicator(progress = { download.percent / 100f }, modifier = Modifier.fillMaxWidth())
                                    }
                                }
                            },
                            trailingContent = { IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.options)) } },
                            modifier = Modifier
                                .alpha(if (download.isCompleted || download.isActive) 1f else 0.7f)
                                .combinedClickable(
                                    onClick = { if (download.isCompleted && session != null) onPlay(PhoneDownloads.playback(download)) else if (!download.isActive) menu = true },
                                    onLongClick = { menu = true },
                                ),
                        )
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            if (download.isCompleted && session != null) DropdownMenuItem(text = { Text(stringResource(R.string.watch_offline)) }, onClick = {
                                menu = false
                                onPlay(PhoneDownloads.playback(download))
                            })
                            if (download.isFailed && session != null) DropdownMenuItem(text = { Text(stringResource(R.string.retry_download)) }, onClick = {
                                menu = false
                                error = null
                                scope.launch {
                                    try {
                                        PhoneDownloads.retry(context, session, download)
                                    } catch (failure: RelayException) {
                                        error = failure.message
                                    }
                                }
                            })
                            DropdownMenuItem(text = { Text(stringResource(if (download.isActive) R.string.cancel_download else R.string.delete_download)) }, onClick = {
                                menu = false
                                PhoneDownloads.remove(context, download)
                            })
                        }
                    }
                }
            }
        }
    }
}
