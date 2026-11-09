@file:OptIn(ExperimentalTvMaterial3Api::class)

package net.silvara.relaycast

import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.Button
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Tab
import androidx.tv.material3.TabRow
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The TV's home: Sources first (no server needed); Continue, Recent and
 * Search too when the Relay Server offers the user's own library.
 */
@Composable
internal fun HomeScreen(
    session: Session?,
    relayHost: String?,
    library: RelayLibrary,
    onOpen: (Anime) -> Unit,
    onSwitchProfile: () -> Unit,
    sources: SourcesBrowser,
    onPlaySource: (SourcePlayback) -> Unit,
    onConnectRelay: () -> Unit,
) {
    val withLibrary = session != null && library == RelayLibrary.Available
    val tabs = listOf(stringResource(R.string.tab_sources)) +
        if (withLibrary) listOf(stringResource(R.string.tab_continue), stringResource(R.string.tab_recent), stringResource(R.string.search)) else emptyList()
    var selected by remember { mutableIntStateOf(0) }
    LaunchedEffect(withLibrary) { if (!withLibrary) selected = 0 }

    var initialLoading by remember(session, withLibrary) { mutableStateOf(withLibrary && session?.history?.isEmpty() == true) }
    LaunchedEffect(session, withLibrary) {
        if (session == null || !withLibrary) return@LaunchedEffect
        try {
            if (withTimeoutOrNull(8_000) { session.refresh(); true } == null) session.initialLoadTimedOut()
        } finally {
            initialLoading = false
        }
    }
    val context = LocalContext.current
    val historyImages = session?.history.orEmpty().mapNotNull { it.imageUrl?.takeIf(String::isNotBlank) }.distinct()
    LaunchedEffect(historyImages) {
        val loader = SingletonImageLoader.get(context)
        for (url in historyImages) {
            loader.execute(ImageRequest.Builder(context).data(url).size(360, 540).build())
        }
    }

    if (initialLoading) {
        StartupScreen()
        return
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TabRow(selectedTabIndex = selected, modifier = Modifier.weight(1f)) {
                tabs.forEachIndexed { index, title ->
                    Tab(selected = selected == index, onFocus = { selected = index }, onClick = { selected = index }) {
                        Text(title, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                    }
                }
            }
            if (session != null) {
                RemoteAccessControl()
                TVPlaybackSettings()
                Spacer(Modifier.width(12.dp))
                // The profile as a badge: its picture and name (switches profile).
                OutlinedButton(
                    onClick = onSwitchProfile,
                    contentPadding = PaddingValues(start = 6.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
                ) {
                    Avatar(session.account, session.profile.name, 28.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(session.account?.displayName ?: session.profile.name, maxLines = 1)
                }
            }
        }
        Spacer(Modifier.height(20.dp))
        if (selected == 0 || session == null) TvSourcesTab(
            browser = sources,
            relayHost = relayHost,
            library = library,
            onPlay = onPlaySource,
            onOpenLibrary = { selected = 2 },
            onConnectRelay = onConnectRelay,
        ) else when (selected) {
            1 -> ContinueTab(session, onOpen)
            2 -> CatalogTab(session, onOpen, searching = false)
            else -> CatalogTab(session, onOpen, searching = true)
        }
    }
}

@Composable
private fun StartupScreen() {
    Box(Modifier.fillMaxSize().background(Color(0xFF0B0B12)), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Image(
                painter = painterResource(R.drawable.app_icon_square),
                contentDescription = null,
                modifier = Modifier.size(96.dp).clip(RoundedCornerShape(24.dp)),
            )
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium, color = Color.White)
            Text(stringResource(R.string.startup_preparing), style = MaterialTheme.typography.bodyLarge, color = Color.White.copy(alpha = 0.65f))
            AndroidView(
                factory = { context -> android.widget.ProgressBar(context).apply {
                    isIndeterminate = true
                    indeterminateTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.rgb(129, 140, 248))
                } },
                modifier = Modifier.size(32.dp),
            )
        }
    }
}

/** Continue watching, in the same grid as Recents and Search; holding OK on a series opens its menu. */
@Composable
private fun ContinueTab(session: Session, onOpen: (Anime) -> Unit) {
    val entries = session.history
    val scope = rememberCoroutineScope()
    var menuFor by remember { mutableStateOf<HistoryEntry?>(null) }
    fun title(entry: HistoryEntry) = if (session.japanese) entry.titleJp ?: entry.seriesTitle else entry.seriesTitle
    val finished = entries.filter { it.isCompleted }.map { catalogPath(it.watchUrl) }.toSet()
    LaunchedEffect(finished) { session.loadStatuses() }
    val states = entries.associate { it.watchUrl to continueState(session, it) }
    val sections = listOf(
        Triple(stringResource(R.string.continue_new_episodes), null as String?, entries.filter { states[it.watchUrl]?.section == 0 }),
        Triple(stringResource(R.string.continue_in_progress), null, entries.filter { states[it.watchUrl]?.section == 1 }),
        Triple(stringResource(R.string.continue_up_to_date), stringResource(R.string.continue_up_to_date_footer), entries.filter { states[it.watchUrl]?.section == 2 }),
        Triple(stringResource(R.string.continue_completed), null, entries.filter { states[it.watchUrl]?.section == 3 }),
    ).filter { it.third.isNotEmpty() }
    // One row per section, scrolling sideways, as on the Google TV home: a
    // section with one series takes one row and the next starts right
    // below (no empty space beside it); long ones scroll instead of wrapping.
    // The cached history shows first; New episodes appears above once the
    // episode counts arrive. Then the list goes back to the top, and the
    // focus to its first poster if it was in the list, so it is not hidden.
    val listState = rememberLazyListState()
    val firstPoster = remember { FocusRequester() }
    var listFocused by remember { mutableStateOf(false) }
    val firstSection = sections.firstOrNull()?.first
    LaunchedEffect(firstSection) {
        if (firstSection == null) return@LaunchedEffect
        listState.scrollToItem(0)
        if (listFocused) runCatching { firstPoster.requestFocus() }
    }
    when {
        entries.isEmpty() && session.historyError != null -> Message(session.historyError.orEmpty())
        entries.isEmpty() -> Message(stringResource(R.string.continue_empty_tv))
        else -> LazyColumn(
            state = listState,
            verticalArrangement = Arrangement.spacedBy(28.dp),
            contentPadding = PaddingValues(top = 4.dp, bottom = 48.dp),
            modifier = Modifier.onFocusChanged { listFocused = it.hasFocus },
        ) {
            sections.forEachIndexed { sectionIndex, (heading, footer, group) ->
                item(key = "section:$heading") {
                    // Focus lands on a poster's image; the whole row (titles
                    // and dates under the posters too) scrolls into view.
                    val rowInView = remember { androidx.compose.foundation.relocation.BringIntoViewRequester() }
                    Column(
                        Modifier
                            .bringIntoViewRequester(rowInView)
                            .onFocusChanged { if (it.hasFocus) scope.launch { rowInView.bringIntoView() } },
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("$heading  ·  ${group.size}", style = MaterialTheme.typography.titleLarge)
                            footer?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.6f)) }
                        }
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(20.dp),
                            // Room for the focused card's outline.
                            contentPadding = PaddingValues(vertical = 4.dp, horizontal = 4.dp),
                        ) {
                            items(group.size, key = { group[it].watchUrl }) { index ->
                                val entry = group[index]
                                val state = states.getValue(entry.watchUrl)
                                PosterCard(
                                    title = title(entry),
                                    subtitle = state.label(entry),
                                    highlight = state.isNews,
                                    imageUrl = entry.imageUrl,
                                    onClick = { onOpen(entry.asAnime()) },
                                    onLongClick = { menuFor = entry },
                                    modifier = Modifier.width(150.dp).then(if (sectionIndex == 0 && index == 0) Modifier.focusRequester(firstPoster) else Modifier),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    menuFor?.let { entry ->
        Dialog(onDismissRequest = { menuFor = null }) {
            Surface(shape = RoundedCornerShape(24.dp)) {
                Column(Modifier.padding(32.dp).ignoringHeldKey(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(title(entry), style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        stringResource(R.string.continue_remove_note_tv),
                        color = Color.White.copy(alpha = 0.7f),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = {
                            menuFor = null
                            onOpen(entry.asAnime())
                        }) { Text(stringResource(R.string.open)) }
                        OutlinedButton(onClick = {
                            menuFor = null
                            scope.launch { session.remove(entry) }
                        }) { Text(stringResource(R.string.remove_from_continue)) }
                        OutlinedButton(onClick = { menuFor = null }) { Text(stringResource(R.string.cancel)) }
                    }
                }
            }
        }
    }
}

/** Posters in rows that scroll down: Continue, Recents and Search. */
@Composable
private fun PosterGrid(
    state: androidx.compose.foundation.lazy.grid.LazyGridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState(),
    modifier: Modifier = Modifier,
    content: androidx.compose.foundation.lazy.grid.LazyGridScope.() -> Unit,
) {
    LazyVerticalGrid(
        state = state,
        modifier = modifier,
        // Five posters per row on a 1080p TV.
        columns = GridCells.Adaptive(140.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
        contentPadding = PaddingValues(top = 4.dp, bottom = 48.dp),
        content = content,
    )
}

/**
 * Recents (Sub/Dub/both, the configured kind first) or search results, paged.
 * Search starts from the filters: pick them, optionally type a title, and
 * search; filters alone browse the catalog.
 */
@Composable
private fun CatalogTab(session: Session, onOpen: (Anime) -> Unit, searching: Boolean) {
    var language by remember { mutableStateOf(session.preferences.defaultTrackType) }
    var query by remember { mutableStateOf("") }
    var filters by remember { mutableStateOf(CatalogFilters()) }
    var options by remember { mutableStateOf<CatalogFilterOptions?>(null) }
    var showFilters by remember { mutableStateOf(false) }
    /** The search that ran (title and filters); null before the first one. */
    var searched by remember { mutableStateOf<Pair<String, CatalogFilters>?>(null) }
    LaunchedEffect(session) { options = session.client.catalogFilterOptions() }
    var items by remember { mutableStateOf<List<Anime>>(emptyList()) }
    var page by remember { mutableIntStateOf(0) }
    var hasNext by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    suspend fun load(next: Int) {
        val search = searched
        if (searching && search == null) return
        loading = true
        error = null
        try {
            val reply = if (searching && search != null) {
                session.client.latest(next, keyword = search.first, filters = search.second)
            } else {
                session.client.latest(next, language = language, filters = filters)
            }
            items = if (next == 1) reply.results else (items + reply.results).distinctBy { it.watchUrl }
            page = next
            hasNext = reply.hasNext
        } catch (failure: RelayException) {
            error = failure.message
        } finally {
            loading = false
        }
    }

    LaunchedEffect(language, if (searching) searched else filters) {
        items = emptyList()
        load(1)
    }

    val applied = if (searching) searched?.second ?: filters else filters
    val visible = items.filter { !(session.preferences.hideAdult && it.isAdult) && applied.allows(it) }

    fun search() {
        val title = query.trim()
        if (title.isEmpty() && !filters.isActive) return
        searched = title to filters
    }

    @Composable
    fun FiltersButton() {
        OutlinedButton(onClick = { showFilters = true }) { Text(if (filters.isActive) stringResource(R.string.filters_count, filters.activeCount) else stringResource(R.string.filters)) }
    }

    @Composable
    fun FiltersSummary() {
        if (filters.isActive) {
            Text(filters.summary(options), color = Color.White.copy(alpha = 0.7f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (searching) {
            // Filters first, on the left of the title.
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                FiltersButton()
                TvTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = stringResource(R.string.search_placeholder_tv),
                    imeAction = ImeAction.Search,
                    onSubmit = ::search,
                    modifier = Modifier.width(640.dp),
                )
                Button(onClick = ::search, enabled = query.isNotBlank() || filters.isActive) { Text(stringResource(R.string.search)) }
            }
            FiltersSummary()
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                listOf("sub" to "Sub", "dub" to "Dub", "all" to stringResource(R.string.sub_and_dub)).forEach { (value, label) ->
                    if (language == value) Button(onClick = { language = value }) { Text(label) }
                    else OutlinedButton(onClick = { language = value }) { Text(label) }
                }
                Spacer(Modifier.width(12.dp))
                FiltersButton()
                FiltersSummary()
            }
        }
        if (showFilters) CatalogFiltersDialog(
            current = filters,
            options = options,
            hideAdult = session.preferences.hideAdult,
            onApply = { chosen ->
                filters = chosen
                showFilters = false
                // A search with the new filters runs right away once there is one.
                if (searching && searched != null) searched = searched!!.first to chosen
            },
            onDismiss = { showFilters = false },
        )
        when {
            visible.isEmpty() && loading -> Message(stringResource(R.string.loading))
            visible.isEmpty() && error != null -> Message(error.orEmpty())
            visible.isEmpty() && searching && searched == null -> Message(stringResource(R.string.search_prompt_tv))
            visible.isEmpty() -> Message(stringResource(R.string.no_results_tv))
            else -> PosterGrid {
                itemsIndexed(visible, key = { _, anime -> anime.watchUrl }) { index, anime ->
                    if (index == visible.lastIndex && hasNext && !loading) {
                        LaunchedEffect(page) { scope.launch { load(page + 1) } }
                    }
                    PosterCard(
                        title = anime.displayTitle(session.japanese),
                        // In Recents with Dub selected, the Dub counter instead of the Sub one.
                        subtitle = listOfNotNull(
                            if (!searching && language == "dub") anime.dubEpisodes?.let { "DUB $it" } else anime.episode?.let { "EP $it" },
                            if (anime.isAdult) "+18" else null,
                            anime.mediaType,
                        ).joinToString(" · "),
                        imageUrl = anime.imageUrl,
                        onClick = { onOpen(anime) },
                    )
                }
            }
        }
    }
}

@Composable
fun PosterCard(
    title: String,
    subtitle: String,
    imageUrl: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    /** Shows the subtitle in the accent color (new episodes or seasons). */
    highlight: Boolean = false,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Card(onClick = onClick, onLongClick = onLongClick, modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f), scale = CardDefaults.scale(focusedScale = 1f)) {
            AsyncImage(model = imageUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (subtitle.isNotEmpty()) {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = if (highlight) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.6f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun Message(text: String) {
    Box(Modifier.fillMaxWidth().padding(vertical = 48.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.titleMedium, color = Color.White.copy(alpha = 0.7f))
    }
}

/** Series: synopsis, the next episode to watch, seasons and episodes. */
@Composable
fun DetailsScreen(session: Session, anime: Anime, onPlay: (PlaybackRequest) -> Unit) {
    var current by remember { mutableStateOf(anime) }
    var series by remember { mutableStateOf<Series?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val playFocus = remember { FocusRequester() }

    LaunchedEffect(current.watchUrl) {
        series = null
        error = null
        try {
            series = session.client.series(current.watchUrl)
        } catch (failure: RelayException) {
            error = failure.message
        }
    }

    val entry = session.entry(current.watchUrl)
    val kind = entry?.serverKind ?: session.preferences.defaultTrackType

    fun play(episode: Episode) {
        val loaded = series ?: return
        if (busy != null) return
        busy = Strings.get(R.string.preparing_episode, episode.number)
        scope.launch {
            try {
                val preferred = entry?.serverName?.takeIf { entry.serverKind == kind }
                onPlay(session.resolve(current, loaded, episode, kind, preferred))
            } catch (failure: RelayException) {
                error = failure.message
            } finally {
                busy = null
            }
        }
    }

    Row(Modifier.fillMaxSize().padding(horizontal = 64.dp, vertical = 40.dp), horizontalArrangement = Arrangement.spacedBy(48.dp)) {
        Column(Modifier.width(300.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            AsyncImage(
                model = current.imageUrl ?: series?.imageUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f).background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(12.dp)),
            )
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            val loaded = series
            Text(loaded?.title ?: current.title, style = MaterialTheme.typography.headlineMedium, maxLines = 2)
            if (session.japanese) (loaded?.titleJp ?: current.titleJp)?.let { Text(it, color = Color.White.copy(alpha = 0.6f)) }
            if (loaded != null) {
                val meta = listOfNotNull(
                    loaded.mediaType,
                    seriesStatusLabel(loaded.status),
                    loaded.score?.let { "★ %.1f".format(it) },
                    loaded.rating,
                    pluralStringResource(
                        R.plurals.episode_count_text,
                        loaded.totalEpisodes ?: loaded.episodes.size,
                        "${loaded.episodes.size}${loaded.totalEpisodes?.let { "/$it" } ?: ""}",
                    ),
                )
                Text(meta.joinToString(" · "), color = Color.White.copy(alpha = 0.6f))
                loaded.synopsis?.let { Text(it, maxLines = 4, overflow = TextOverflow.Ellipsis, color = Color.White.copy(alpha = 0.85f)) }
                val next = when {
                    entry == null -> loaded.episodes.firstOrNull()
                    !entry.isCompleted -> loaded.episodes.firstOrNull { it.number == entry.episode }
                    else -> loaded.episodeAfter(entry.episode, kind)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (next != null) {
                        val label = when {
                            entry != null && !entry.isCompleted && next.number == entry.episode -> stringResource(R.string.continue_episode_at, next.number, formatTime(entry.positionSeconds))
                            entry != null -> stringResource(R.string.watch_episode_next, next.number)
                            else -> stringResource(R.string.start_with_episode, next.number)
                        }
                        Button(onClick = { play(next) }, modifier = Modifier.focusRequester(playFocus)) { Text(label) }
                        LaunchedEffect(current.watchUrl, next.number) { playFocus.requestFocus() }
                    } else if (entry != null) {
                        Text(stringResource(R.string.up_to_date_watched, entry.episode), color = Color.White.copy(alpha = 0.7f))
                    }
                }
                if (loaded.seasons.size > 1) {
                    SeasonRow(loaded.seasons, current) { current = it.asAnime() }
                }
                busy?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(bottom = 48.dp)) {
                    items(loaded.episodes, key = { it.number }) { episode ->
                        val notYet = stringResource(R.string.not_yet_in, if (kind == "dub") "Dub" else "Sub")
                        val watched = entry != null && (episode.number < entry.episode || (episode.number == entry.episode && entry.isCompleted))
                        val progress = entry?.takeIf { it.episode == episode.number && !it.isCompleted }?.positionSeconds
                        ListItem(
                            selected = false,
                            onClick = { play(episode) },
                            headlineContent = { Text("${episode.number}. ${episode.subtitle ?: stringResource(R.string.episode_number, episode.number)}", maxLines = 1) },
                            supportingContent = {
                                val notes = listOfNotNull(
                                    progress?.let { stringResource(R.string.watched_until, formatTime(it)) },
                                    if (watched) stringResource(R.string.watched) else null,
                                    if (!episode.isAvailable(kind)) notYet else null,
                                )
                                if (notes.isNotEmpty()) Text(notes.joinToString(" · "))
                            },
                        )
                    }
                }
            } else {
                Text(error ?: stringResource(R.string.loading_episodes), color = if (error != null) MaterialTheme.colorScheme.error else Color.White.copy(alpha = 0.7f))
            }
        }
    }
}

/**
 * Seasons and related entries. The padding leaves room for the enlarged
 * focused pill; fades with arrows at the edges show there are more.
 */
@Composable
private fun SeasonRow(seasons: List<Season>, current: Anime, onSelect: (Season) -> Unit) {
    val state = rememberLazyListState()
    val index = seasons.indexOfFirst { catalogPath(it.watchUrl) == catalogPath(current.watchUrl) }
    LaunchedEffect(seasons, index) { if (index > 0) state.scrollToItem(index) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            if (index >= 0) stringResource(R.string.seasons_position, index + 1, seasons.size) else stringResource(R.string.seasons_count, seasons.size),
            style = MaterialTheme.typography.labelLarge,
            color = Color.White.copy(alpha = 0.6f),
        )
        Box {
            LazyRow(
                state = state,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
            ) {
                items(seasons, key = { it.watchUrl }) { season ->
                    // Long names (movies, specials) are shortened.
                    val label = @Composable { Text(season.displayLabel, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 320.dp)) }
                    if (catalogPath(season.watchUrl) == catalogPath(current.watchUrl)) {
                        Button(onClick = {}) { label() }
                    } else {
                        OutlinedButton(onClick = { onSelect(season) }) { label() }
                    }
                }
            }
            if (state.canScrollBackward) EdgeHint(start = true, Modifier.matchParentSize())
            if (state.canScrollForward) EdgeHint(start = false, Modifier.matchParentSize())
        }
    }
}

@Composable
private fun EdgeHint(start: Boolean, modifier: Modifier) {
    val background = Color(0xFF0B0B12)
    Box(modifier, contentAlignment = if (start) Alignment.CenterStart else Alignment.CenterEnd) {
        Box(
            Modifier.fillMaxHeight().width(96.dp).background(
                Brush.horizontalGradient(if (start) listOf(background, Color.Transparent) else listOf(Color.Transparent, background)),
            ),
            contentAlignment = if (start) Alignment.CenterStart else Alignment.CenterEnd,
        ) {
            Text(if (start) "‹" else "›", style = MaterialTheme.typography.headlineMedium, color = Color.White.copy(alpha = 0.8f))
        }
    }
}

/**
 * Where a series stands for the viewer (as on the phone), from its history
 * entry and the relay's episode counts for the Sub/Dub kind they watch.
 */
internal sealed interface ContinueState {
    data object InProgress : ContinueState
    /** The finished episode has successors of the same kind. */
    data class NewEpisodes(val count: Int) : ContinueState
    /** The series is complete and the watch order has a season not started yet. */
    data class NextSeason(val season: Season) : ContinueState
    /** Watched up to the newest episode of a series still airing. */
    data class CaughtUp(val nextAt: Double?) : ContinueState
    data object Completed : ContinueState

    /** New episodes first, then in progress, caught up and completed. */
    val section: Int
        get() = when (this) {
            is NewEpisodes -> 0
            InProgress, is NextSeason -> 1
            is CaughtUp -> 2
            Completed -> 3
        }

    val isNews: Boolean get() = this is NewEpisodes || this is NextSeason

    fun label(entry: HistoryEntry): String = when (this) {
        InProgress -> Strings.get(R.string.state_in_progress, entry.episode, formatTime(entry.positionSeconds))
        is NewEpisodes -> if (count == 1) Strings.get(R.string.state_new_one, entry.episode + 1) else Strings.get(R.string.state_new_many, count, entry.episode + 1)
        is NextSeason -> Strings.get(R.string.state_next_season, season.displayLabel)
        is CaughtUp -> airingLabel(nextAt)?.let { Strings.get(R.string.state_caught_up_next, entry.episode, it) } ?: Strings.get(R.string.state_caught_up, entry.episode)
        Completed -> Strings.plural(R.plurals.state_completed, entry.episode, entry.episode)
    }
}

internal fun continueState(session: Session, entry: HistoryEntry): ContinueState {
    if (!entry.isCompleted) return ContinueState.InProgress
    val kind = entry.serverKind ?: session.preferences.defaultTrackType
    val status = session.statuses[catalogPath(entry.watchUrl)]
    val latest = status?.latest(kind)
    if (latest != null && latest > entry.episode) return ContinueState.NewEpisodes(latest - entry.episode)
    val total = status?.totalEpisodes ?: entry.totalEpisodes
    val isFinal = if (total != null) entry.episode >= total else status?.status == "finished" && entry.episode >= (latest ?: entry.episode)
    if (!isFinal) return ContinueState.CaughtUp(status?.nextEpisodeAt)
    status?.nextSeason?.takeIf { session.entry(it.watchUrl) == null }?.let { return ContinueState.NextSeason(it) }
    return ContinueState.Completed
}

/** "jue 5 nov, 11:28" ("Thu, Nov 5, 11:28 AM") in the device's time zone, for a release still in the future. */
internal fun airingLabel(unixSeconds: Double?): String? {
    val millis = unixSeconds?.takeIf { it > 0 }?.let { (it * 1000).toLong() } ?: return null
    if (millis <= System.currentTimeMillis()) return null
    val locale = Strings.locale
    val pattern = if (Strings.spanish) "EEE d MMM, HH:mm" else android.text.format.DateFormat.getBestDateTimePattern(locale, "EEEdMMMjmm")
    return java.text.SimpleDateFormat(pattern, locale).format(java.util.Date(millis)).replace(".", "")
}

/** The catalog's airing status ("airing", "finished", "upcoming"), or null. */
@Composable
internal fun seriesStatusLabel(status: String?): String? = when (status) {
    "airing" -> stringResource(R.string.status_airing)
    "finished" -> stringResource(R.string.status_finished)
    "upcoming" -> stringResource(R.string.status_upcoming)
    else -> null
}

/**
 * This TV's playback settings (a gear in the header): for now "Traducir
 * subtítulos", stored on the TV like on the phone.
 */
@Composable
private fun TVPlaybackSettings() {
    val context = androidx.compose.ui.platform.LocalContext.current
    var open by remember { mutableStateOf(false) }
    var translate by remember { mutableStateOf(SubtitleTranslationSetting.isEnabled(context)) }
    androidx.tv.material3.OutlinedIconButton(onClick = { open = true }) {
        androidx.tv.material3.Icon(PhoneIcons.Settings, contentDescription = stringResource(R.string.playback), modifier = Modifier.size(22.dp))
    }
    Spacer(Modifier.width(12.dp))
    if (open) Dialog(onDismissRequest = { open = false }) {
        Surface(shape = RoundedCornerShape(24.dp)) {
            Column(Modifier.padding(32.dp).width(560.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringResource(R.string.playback), style = MaterialTheme.typography.titleLarge)
                Button(onClick = {
                    translate = !translate
                    SubtitleTranslationSetting.setEnabled(context, translate)
                }) {
                    Text("${stringResource(R.string.translate_subtitles)}: ${stringResource(if (translate) R.string.settings_on else R.string.settings_off)}")
                }
                Text(stringResource(R.string.info_translate_subtitles), color = Color.White.copy(alpha = 0.7f))
                OutlinedButton(onClick = { open = false }) { Text(stringResource(R.string.close)) }
            }
        }
    }
}
