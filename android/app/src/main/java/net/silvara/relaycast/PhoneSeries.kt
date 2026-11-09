@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package net.silvara.relaycast

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch

private val extraSeason = Regex("\\b(specials?|mini anime|recap|ova|oad|movie|film)\\b", RegexOption.IGNORE_CASE)

/** Episodes of a series and what to play next; seasons replace the screen in place. */
@Composable
internal fun PhoneSeries(session: Session, anime: Anime, onBack: () -> Unit) {
    val app = LocalPhone.current
    val scope = rememberCoroutineScope()
    var current by remember(anime.watchUrl) { mutableStateOf(anime) }
    var series by remember { mutableStateOf<Series?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var serverChoice by remember { mutableStateOf<Pair<Episode, List<Server>>?>(null) }
    var synopsisExpanded by remember { mutableStateOf(false) }
    var reload by remember { mutableStateOf(0) }
    val context = LocalContext.current
    val downloads by rememberDownloads()
    /** Without a connection: the series is its downloaded episodes. */
    var offline by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(current.watchUrl, reload) {
        series = null
        error = null
        offline = false
        notice = null
        try {
            series = session.client.series(current.watchUrl)
        } catch (failure: RelayException) {
            val stored = PhoneDownloads.offlineSeries(current.watchUrl)
            if (stored != null) {
                series = stored
                offline = true
                notice = Strings.get(R.string.offline_relay_notice)
            } else {
                error = failure.message
            }
        }
    }

    /** The download of an episode in a kind (any kind with null). */
    fun recordOf(number: Int, recordKind: String?): EpisodeDownload? =
        downloads.filter { it.matches(current.watchUrl, number, recordKind) }.minByOrNull { if (it.isCompleted) 0 else if (it.isActive) 1 else 2 }

    /** A completed download: the preferred kind first, then the other. */
    fun stored(number: Int, preferred: String): EpisodeDownload? =
        recordOf(number, preferred)?.takeIf { it.isCompleted } ?: downloads.firstOrNull { it.isCompleted && it.matches(current.watchUrl, number) }

    fun playStored(download: EpisodeDownload) {
        val local = PhoneDownloads.playback(download)
        // Online, previous and next keep the whole series.
        app.playing = if (offline) local else local.copy(anime = current, series = series ?: local.series)
    }

    val entry = session.entry(current.watchUrl)
    val kind = entry?.serverKind ?: session.preferences.defaultTrackType
    val title = current.displayTitle(session.japanese)

    // Series sessions on the relay expire after 20 minutes (HTTP 410).
    suspend fun <T> withSeries(block: suspend (Series) -> T): T {
        val loaded = series ?: throw RelayException(Strings.get(R.string.series_not_loaded))
        if (offline) throw RelayException(Strings.get(R.string.offline_only_downloads))
        return try {
            block(loaded)
        } catch (failure: RelayException) {
            if (failure.status != 410) throw failure
            val fresh = session.client.series(current.watchUrl)
            series = fresh
            block(fresh)
        }
    }

    fun play(episode: Episode, preferredServer: String?, playKind: String) {
        val loaded = series ?: return
        if (busy != null) return
        if (offline) {
            error = Strings.get(R.string.offline_not_downloaded, episode.number)
            return
        }
        busy = Strings.get(R.string.preparing_playback)
        scope.launch {
            try {
                app.playing = session.resolve(current, loaded, episode, playKind, preferredServer)
            } catch (failure: RelayException) {
                error = failure.message
            } finally {
                busy = null
            }
        }
    }

    fun chooseServer(episode: Episode) {
        if (busy != null) return
        busy = Strings.get(R.string.loading_servers, episode.number)
        scope.launch {
            try {
                serverChoice = episode to withSeries { session.client.servers(it.seriesKey, episode.number) }
            } catch (failure: RelayException) {
                error = failure.message
            } finally {
                busy = null
            }
        }
    }

    fun playServer(episode: Episode, server: Server) {
        busy = Strings.get(R.string.preparing_playback)
        scope.launch {
            try {
                val playback = withSeries { session.client.play(it.seriesKey, episode.number, server.index) }
                app.playing = PlaybackRequest(current, series ?: return@launch, episode, playback.serverKind ?: server.type, playback)
            } catch (failure: RelayException) {
                error = failure.message
            } finally {
                busy = null
            }
        }
    }

    fun tvCommand(episode: Episode) = RemoteCommand(type = "play", watchUrl = current.watchUrl, episode = episode.number, kind = kind)

    /** Here: the downloaded copy if there is one, else the server picker. */
    fun openHere(episode: Episode) {
        stored(episode.number, kind)?.let { playStored(it) } ?: chooseServer(episode)
    }

    /** With a TV connected (and a connection) the episode goes there; otherwise here. */
    fun open(episode: Episode) {
        if (offline || !app.playOnTv(tvCommand(episode), Strings.get(R.string.series_episode, title, episode.number)) { openHere(episode) }) openHere(episode)
    }

    /** Continue and next: the download, or the saved server and kind straight away (or on the TV). */
    fun resume(episode: Episode, preferredServer: String?, playKind: String) {
        fun here() = recordOf(episode.number, playKind)?.takeIf { it.isCompleted }?.let { playStored(it) } ?: play(episode, preferredServer, playKind)
        if (offline || !app.playOnTv(tvCommand(episode), Strings.get(R.string.series_episode, title, episode.number)) { here() }) here()
    }

    /** Downloads episodes one at a time, each from a working server of the kind. */
    fun download(episodes: List<Episode>, downloadKind: String) {
        if (busy != null || episodes.isEmpty() || series == null || offline) return
        notice = null
        error = null
        scope.launch {
            val notes = mutableListOf<String>()
            for (episode in episodes) {
                busy = Strings.get(R.string.preparing_download, episode.number)
                try {
                    val request = session.resolve(current, series ?: break, episode, downloadKind, entry?.takeIf { it.serverKind == downloadKind }?.serverName)
                    series = request.series
                    if (request.kind != downloadKind) {
                        notes += Strings.get(R.string.note_no_kind_servers, episode.number, kindLabel(downloadKind))
                        continue
                    }
                    PhoneDownloads.start(context, request)
                } catch (failure: RelayException) {
                    notes += Strings.get(R.string.note_episode_error, episode.number, failure.message.orEmpty())
                    if (failure.offline) break
                }
            }
            busy = null
            notice = notes.joinToString("\n").ifEmpty {
                if (episodes.size == 1) Strings.get(R.string.downloading_one, episodes.first().number, kindLabel(downloadKind))
                else Strings.get(R.string.downloading_many, episodes.size, kindLabel(downloadKind))
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back)) } },
            actions = {
                val loaded = series
                if (loaded != null && !offline) {
                    // "Download": the next episodes of the kind being followed.
                    var menu by remember { mutableStateOf(false) }
                    val start = entry?.let { if (it.isCompleted) it.episode + 1 else it.episode } ?: loaded.episodes.firstOrNull()?.number ?: 1
                    val pending = loaded.episodes.filter { it.number >= start && it.isAvailable(kind) && recordOf(it.number, kind)?.isFailed != false }
                    Box {
                        IconButton(onClick = { menu = true }, enabled = pending.isNotEmpty() && busy == null) { Icon(PhoneIcons.Download, contentDescription = stringResource(R.string.download)) }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            Text(
                                stringResource(R.string.download_menu_title, kindLabel(kind)),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                            pending.firstOrNull()?.let { first ->
                                DropdownMenuItem(text = { Text(if (entry != null) stringResource(R.string.episode_next, first.number) else stringResource(R.string.episode_number, first.number)) }, onClick = {
                                    menu = false
                                    download(listOf(first), kind)
                                })
                            }
                            if (pending.size > 3) DropdownMenuItem(text = { Text(stringResource(R.string.next_3)) }, onClick = {
                                menu = false
                                download(pending.take(3), kind)
                            })
                            if (pending.size > 1) DropdownMenuItem(
                                text = { Text(if (entry == null) stringResource(R.string.whole_series, pending.size) else stringResource(R.string.remaining, pending.first().number, pending.last().number)) },
                                onClick = {
                                    menu = false
                                    download(pending, kind)
                                },
                            )
                        }
                    }
                }
                if (!offline) DevicesButton(app)
            },
        )
        busy?.let {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(it, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        }
        val loaded = series
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    AsyncImage(
                        model = current.imageUrl ?: loaded?.imageUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.width(120.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(loaded?.title ?: current.title, style = MaterialTheme.typography.titleLarge)
                        if (session.japanese) (loaded?.titleJp ?: current.titleJp)?.let {
                            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (loaded?.adult == true || current.isAdult) PhoneBadge("+18", androidx.compose.ui.graphics.Color(0xFFDC2626))
                        if (loaded != null) {
                            val meta = listOfNotNull(
                                loaded.mediaType,
                                seriesStatusLabel(loaded.status),
                                loaded.score?.let { "★ %.1f".format(it) },
                                // The catalog sends "?" when it has no rating.
                                loaded.rating?.takeUnless { it == "?" },
                            ).joinToString(" · ")
                            if (meta.isNotEmpty()) Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            val total = loaded.totalEpisodes
                            Text(
                                if (total != null && total > loaded.episodes.size) stringResource(R.string.episodes_of_total, loaded.episodes.size, total)
                                else pluralStringResource(R.plurals.episode_count, loaded.episodes.size, loaded.episodes.size),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            airingLabel(loaded.nextEpisodeAt)?.let {
                                Text(stringResource(R.string.next_episode_airing, it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }
            if (loaded == null) {
                item {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(error ?: stringResource(R.string.loading_episodes), color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                        if (error != null) OutlinedButton(onClick = { reload++ }) { Icon(Icons.Filled.Refresh, null); Text(" " + stringResource(R.string.retry)) }
                    }
                }
                return@LazyColumn
            }
            item {
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PrimaryAction(session, loaded, current, entry, kind, playIcon = app.connectedTv != null,
                        onResume = ::resume, onOpen = ::open, onSeason = { current = it.asAnime() })
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    notice?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
                }
            }
            loaded.synopsis?.let { synopsis ->
                item {
                    // Translated on the phone (ML Kit), shown in place of the original.
                    var translated by remember(synopsis) { mutableStateOf<String?>(null) }
                    var showingTranslation by remember(synopsis) { mutableStateOf(false) }
                    var translating by remember { mutableStateOf(false) }
                    var translationFailed by remember(synopsis) { mutableStateOf(false) }
                    val translationScope = androidx.compose.runtime.rememberCoroutineScope()
                    Column(Modifier.padding(16.dp).clickable { synopsisExpanded = !synopsisExpanded }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.synopsis), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                            // The catalog's synopses are in English.
                            if (Strings.locale.language != "en") {
                                TextButton(enabled = !translating, onClick = {
                                    if (translated != null) {
                                        showingTranslation = !showingTranslation
                                        return@TextButton
                                    }
                                    translating = true
                                    translationFailed = false
                                    translationScope.launch {
                                        translated = translateSynopsis(synopsis)
                                        showingTranslation = translated != null
                                        translationFailed = translated == null
                                        translating = false
                                    }
                                }) {
                                    Text(stringResource(when { translating -> R.string.translating; showingTranslation -> R.string.show_original; else -> R.string.translate }))
                                }
                            }
                        }
                        Text(
                            (if (showingTranslation) translated else null) ?: synopsis,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = if (synopsisExpanded || showingTranslation) Int.MAX_VALUE else 4,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (translationFailed) Text(stringResource(R.string.translate_failed), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            if (loaded.seasons.size > 1) item {
                var menu by remember { mutableStateOf(false) }
                val selected = loaded.seasons.firstOrNull { catalogPath(it.watchUrl) == catalogPath(current.watchUrl) }
                Box(Modifier.padding(horizontal = 16.dp)) {
                    OutlinedButton(onClick = { menu = true }) {
                        Text(stringResource(R.string.season_selected, selected?.displayLabel ?: "—"), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        loaded.seasons.forEach { season ->
                            DropdownMenuItem(
                                text = { Text(season.displayLabel) },
                                leadingIcon = { if (season == selected) Icon(Icons.Filled.Check, contentDescription = null) },
                                onClick = { menu = false; current = season.asAnime() },
                            )
                        }
                    }
                }
            }
            item { Text(stringResource(R.string.filter_episodes), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 16.dp, top = 16.dp)) }
            items(loaded.episodes, key = { it.number }) { episode ->
                var menu by remember { mutableStateOf(false) }
                val watched = entry != null && (episode.number < entry.episode || (episode.number == entry.episode && entry.isCompleted))
                val progress = entry?.takeIf { it.episode == episode.number && !it.isCompleted }?.positionSeconds
                val records = downloads.filter { it.matches(current.watchUrl, episode.number) }
                val active = records.firstOrNull { it.isActive }
                // Offline only the downloaded episodes play.
                val playable = !offline || records.any { it.isCompleted }
                Box {
                    ListItem(
                        headlineContent = { Text("${episode.number}. ${episode.subtitle ?: stringResource(R.string.episode_number, episode.number)}", maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        supportingContent = {
                            val notYet = stringResource(R.string.not_yet_in, if (kind == "dub") "Dub" else "Sub")
                            val notes = listOfNotNull(
                                progress?.let { stringResource(R.string.watched_until, formatTime(it)) },
                                if (episode.number == current.episode) stringResource(R.string.new_badge) else null,
                                if (!offline && !episode.isAvailable(kind)) notYet else null,
                            ) + records.sortedBy { it.info.kind }.map { downloadStatus(context, it) }
                            if (notes.isNotEmpty()) Text(notes.joinToString(" · "))
                        },
                        trailingContent = {
                            if (active != null) {
                                CircularProgressIndicator(progress = { active.percent / 100f }, modifier = Modifier.size(24.dp), strokeWidth = 3.dp)
                            } else {
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                                    if (records.any { it.isCompleted }) Icon(PhoneIcons.Download, contentDescription = stringResource(R.string.downloaded), tint = MaterialTheme.colorScheme.primary)
                                    Icon(if (watched) Icons.Filled.Check else if (app.connectedTv != null && !offline) PhoneIcons.Tv else Icons.Filled.PlayArrow, contentDescription = null)
                                }
                            }
                        },
                        modifier = Modifier.alpha(if (playable) 1f else 0.45f)
                            .combinedClickable(enabled = playable, onClick = { open(episode) }, onLongClick = { menu = true }),
                    )
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        // Per kind: download, watch offline, cancel or delete.
                        listOf("sub", "dub").filter { episode.isAvailable(it) || recordOf(episode.number, it) != null }.forEach { recordKind ->
                            val record = recordOf(episode.number, recordKind)
                            val label = kindLabel(recordKind)
                            when {
                                record == null || record.isFailed -> if (!offline) DropdownMenuItem(
                                    text = { Text(if (record != null) stringResource(R.string.retry_download_in, label) else stringResource(R.string.download_in, label)) },
                                    leadingIcon = { Icon(PhoneIcons.Download, null) },
                                    onClick = {
                                        menu = false
                                        record?.let { PhoneDownloads.remove(context, it) }
                                        download(listOf(episode), recordKind)
                                    },
                                )
                                record.isCompleted -> {
                                    DropdownMenuItem(text = { Text(stringResource(R.string.watch_offline_kind, label)) }, leadingIcon = { Icon(Icons.Filled.PlayArrow, null) }, onClick = {
                                        menu = false
                                        playStored(record)
                                    })
                                    DropdownMenuItem(text = { Text(stringResource(R.string.delete_download_kind, label)) }, onClick = { menu = false; PhoneDownloads.remove(context, record) })
                                }
                                else -> DropdownMenuItem(text = { Text(stringResource(R.string.cancel_download_kind, label)) }, onClick = { menu = false; PhoneDownloads.remove(context, record) })
                            }
                            if (record?.isFailed == true) DropdownMenuItem(text = { Text(stringResource(R.string.delete_download_kind, label)) }, onClick = { menu = false; PhoneDownloads.remove(context, record) })
                        }
                        if (!offline) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.watch_on_tv)) }, leadingIcon = { Icon(PhoneIcons.Tv, null) }, onClick = {
                                menu = false
                                app.devicesCastOnly = false
                                app.devicesOpen = true
                            })
                            if (app.connectedTv != null) DropdownMenuItem(text = { Text(stringResource(R.string.watch_on_this_phone)) }, leadingIcon = { Icon(Icons.Filled.PlayArrow, null) }, onClick = {
                                menu = false
                                openHere(episode)
                            })
                            DropdownMenuItem(text = { Text(stringResource(R.string.choose_server_online)) }, onClick = { menu = false; chooseServer(episode) })
                        }
                    }
                }
            }
        }
    }

    serverChoice?.let { (episode, servers) ->
        PhoneServerPicker(episode, servers, defaultKind = kind, onDismiss = { serverChoice = null }) { server ->
            serverChoice = null
            playServer(episode, server)
        }
    }
}

@Composable
private fun PrimaryAction(
    session: Session,
    series: Series,
    anime: Anime,
    entry: HistoryEntry?,
    kind: String,
    playIcon: Boolean,
    onResume: (Episode, String?, String) -> Unit,
    onOpen: (Episode) -> Unit,
    onSeason: (Season) -> Unit,
) {
    val icon = if (playIcon) PhoneIcons.Tv else Icons.Filled.PlayArrow
    val watchedEpisode = entry?.let { saved -> series.episodes.firstOrNull { it.number == saved.episode } }
    if (entry != null && watchedEpisode != null) {
        val playKind = entry.serverKind ?: session.preferences.defaultTrackType
        if (!entry.isCompleted) {
            Button(onClick = { onResume(watchedEpisode, entry.serverName, playKind) }, modifier = Modifier.fillMaxWidth()) {
                Icon(icon, null)
                Text(" " + stringResource(R.string.continue_episode_at, entry.episode, formatTime(entry.positionSeconds)))
            }
            return
        }
        val next = series.episodeAfter(entry.episode, kind)
        if (next != null) {
            Button(onClick = { onResume(next, entry.serverName, kind) }, modifier = Modifier.fillMaxWidth()) {
                Icon(icon, null)
                Text(" " + stringResource(R.string.watch_episode_next, next.number))
            }
            return
        }
        val latest = series.episodes.lastOrNull()?.number
        val total = series.totalEpisodes
        val finished = if (total != null) entry.episode >= total else series.status == "finished" && entry.episode >= (latest ?: entry.episode)
        val following = series.seasons.indexOfFirst { catalogPath(it.watchUrl) == catalogPath(anime.watchUrl) }.takeIf { it >= 0 }
            ?.let { index -> series.seasons.drop(index + 1) }.orEmpty()
        val nextSeason = following.firstOrNull { !extraSeason.containsMatchIn(it.title) } ?: following.firstOrNull()
        if (finished && nextSeason != null) {
            Button(onClick = { onSeason(nextSeason) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.watch_season, nextSeason.displayLabel)) }
        }
        Text(
            if (finished) pluralStringResource(R.plurals.series_completed, entry.episode, entry.episode)
            else stringResource(R.string.up_to_date_phone, entry.episode, if (kind == "dub") "Dub" else "Sub"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FilledTonalButton(onClick = { onResume(watchedEpisode, entry.serverName, playKind) }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.rewatch, entry.episode))
        }
        return
    }
    val first = series.episodes.firstOrNull { it.number == anime.episode } ?: series.episodes.firstOrNull() ?: return
    Button(onClick = { onOpen(first) }, modifier = Modifier.fillMaxWidth()) {
        Icon(icon, null)
        Text(" " + if (first.number == anime.episode) stringResource(R.string.watch_episode_new, first.number) else stringResource(R.string.start_with_episode, first.number))
    }
}

/** Servers of an episode, Sub or Dub; playback starts once the sheet closes. */
@Composable
internal fun PhoneServerPicker(episode: Episode, servers: List<Server>, defaultKind: String, onDismiss: () -> Unit, onPick: (Server) -> Unit) {
    val kinds = listOf("sub", "dub")
    var selected by remember { mutableStateOf(if (servers.any { it.type == defaultKind }) defaultKind else servers.firstOrNull()?.type ?: "sub") }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.episode_number, episode.number), style = MaterialTheme.typography.titleLarge)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                kinds.forEachIndexed { index, value ->
                    SegmentedButton(
                        selected = selected == value,
                        onClick = { selected = value },
                        shape = SegmentedButtonDefaults.itemShape(index, kinds.size),
                    ) { Text(if (value == "dub") "Dub" else "Sub") }
                }
            }
            Text(
                stringResource(if (selected == "dub") R.string.audio_english else R.string.audio_japanese),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val ofKind = servers.filter { it.type == selected }
            if (ofKind.isEmpty()) {
                Text(stringResource(R.string.no_sources, if (selected == "dub") "Dub" else "Sub"), modifier = Modifier.padding(vertical = 24.dp))
            }
            ofKind.forEach { server ->
                ListItem(
                    headlineContent = { Text(server.name) },
                    trailingContent = { Icon(Icons.Filled.PlayArrow, null) },
                    modifier = Modifier.clickable { onPick(server) },
                )
            }
            Box(Modifier.padding(bottom = 24.dp))
        }
    }
}

