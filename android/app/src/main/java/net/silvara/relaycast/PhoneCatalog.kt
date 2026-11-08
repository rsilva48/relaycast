@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package net.silvara.relaycast

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch

/** Recent searches (8, newest first), global on the device like on iOS. */
private object RecentSearches {
    private fun prefs(context: Context) = context.getSharedPreferences("relaycast", Context.MODE_PRIVATE)
    fun load(context: Context): List<String> = prefs(context).getString("recentSearches", null)?.split("\n")?.filter { it.isNotBlank() }.orEmpty()
    fun add(context: Context, query: String) {
        val list = (listOf(query) + load(context).filterNot { it.equals(query, ignoreCase = true) }).take(8)
        prefs(context).edit().putString("recentSearches", list.joinToString("\n")).apply()
    }
}

/**
 * Recent (Sub, Dub or both; the configured kind first) or Search, in a
 * paged poster grid, with the same filters as the other apps.
 */
@Composable
internal fun PhoneCatalog(session: Session, searching: Boolean, onOpen: (Anime) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    var language by remember { mutableStateOf(session.preferences.defaultTrackType) }
    var filters by remember { mutableStateOf(CatalogFilters()) }
    var options by remember { mutableStateOf<CatalogFilterOptions?>(null) }
    var showFilters by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var submitted by remember { mutableStateOf<String?>(null) }
    var recent by remember { mutableStateOf(RecentSearches.load(context)) }
    var items by remember { mutableStateOf<List<Anime>>(emptyList()) }
    var page by remember { mutableIntStateOf(0) }
    var hasNext by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(session) { options = session.client.catalogFilterOptions() }

    suspend fun load(next: Int) {
        val keyword = submitted
        if (searching && keyword == null) return
        loading = true
        error = null
        try {
            val reply = if (searching) session.client.latest(next, keyword = keyword, filters = filters)
            else session.client.latest(next, language = language, filters = filters)
            items = if (next == 1) reply.results else (items + reply.results).distinctBy { it.watchUrl }
            page = next
            hasNext = reply.hasNext
        } catch (failure: RelayException) {
            error = failure.message
        } finally {
            loading = false
        }
    }

    LaunchedEffect(language, filters, submitted) {
        items = emptyList()
        load(1)
    }

    fun search(text: String) {
        val title = text.trim()
        if (title.isEmpty() && !filters.isActive) return
        keyboard?.hide()
        query = title
        if (title.isNotEmpty()) {
            RecentSearches.add(context, title)
            recent = RecentSearches.load(context)
        }
        submitted = title
    }

    val visible = items.filter { !(session.preferences.hideAdult && it.isAdult) && filters.allows(it) }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(if (searching) R.string.search else R.string.tab_recent)) },
            actions = {
                if (!searching) {
                    var menu by remember { mutableStateOf(false) }
                    Box {
                        TextButton(onClick = { menu = true }) {
                            Text(when (language) { "dub" -> "Dub"; "all" -> stringResource(R.string.sub_and_dub); else -> "Sub" })
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            listOf("sub" to "Sub", "dub" to "Dub", "all" to stringResource(R.string.sub_and_dub)).forEach { (value, label) ->
                                DropdownMenuItem(text = { Text(label) }, onClick = { language = value; menu = false })
                            }
                        }
                    }
                }
                // Google Cast and the TV app, as on iOS.
                if (!searching) DevicesButton(LocalPhone.current)
                IconButton(onClick = { showFilters = true }) {
                    BadgedBox(badge = { if (filters.isActive) Badge { Text(filters.activeCount.toString()) } }) {
                        Icon(PhoneIcons.Filter, contentDescription = stringResource(R.string.filters))
                    }
                }
            },
        )
        if (searching) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text(stringResource(R.string.search_title_placeholder)) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Clear, contentDescription = stringResource(R.string.clear_search)) } },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { search(query) }),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        if (filters.isActive) {
            Text(
                filters.summary(options),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = { scope.launch { refreshing = true; load(1); refreshing = false } },
            modifier = Modifier.fillMaxSize(),
        ) {
            when {
                searching && submitted == null && query.isEmpty() && recent.isNotEmpty() -> LazyColumn {
                    item { Text(stringResource(R.string.recent_searches), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(16.dp)) }
                    items(recent) { text ->
                        ListItem(
                            headlineContent = { Text(text) },
                            leadingContent = { Icon(Icons.Filled.DateRange, contentDescription = null) },
                            modifier = Modifier.clickable { query = text; search(text) },
                        )
                    }
                }
                visible.isEmpty() && loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                visible.isEmpty() && error != null -> PhoneMessage(stringResource(R.string.load_failed), error.orEmpty(), action = stringResource(R.string.retry) to { scope.launch { load(1) } }, icon = PhoneIcons.CloudOff)
                searching && submitted == null -> PhoneMessage(stringResource(R.string.search_prompt_title), stringResource(R.string.search_prompt_text), icon = PhoneIcons.Search)
                visible.isEmpty() -> PhoneMessage(stringResource(R.string.no_results), stringResource(if (filters.isActive) R.string.no_results_filters else R.string.no_results_latest), icon = PhoneIcons.Sparkle)
                else -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(108.dp),
                    contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    itemsIndexed(visible, key = { _, anime -> anime.watchUrl }) { index, anime ->
                        if (index == visible.lastIndex && hasNext && !loading) LaunchedEffect(page) { load(page + 1) }
                        // Recents shows the selected kind's counter; search shows both.
                        PhonePoster(anime, session.japanese, badges = if (searching) "all" else language) { onOpen(anime) }
                    }
                    if (loading || hasNext || error != null) item(span = { GridItemSpan(maxLineSpan) }) {
                        Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                            when {
                                loading -> CircularProgressIndicator()
                                error != null -> TextButton(onClick = { scope.launch { load(page + 1) } }) { Text(stringResource(R.string.retry)) }
                                // Score filtering can hide a whole page.
                                else -> TextButton(onClick = { scope.launch { load(page + 1) } }) { Text(stringResource(R.string.load_more)) }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showFilters) PhoneFiltersSheet(filters, options, session.preferences.hideAdult, onDismiss = { showFilters = false }) {
        filters = it
        showFilters = false
        if (searching && submitted != null) submitted = query.trim()
    }
}

@Composable
internal fun PhonePoster(anime: Anime, japanese: Boolean, badges: String = "all", onClick: () -> Unit) {
    Column(Modifier.clickable(onClick = onClick), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box {
            AsyncImage(
                model = anime.imageUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
            Row(Modifier.padding(6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                // Only the selected kind's counter ("sub", "dub"; both with "all").
                if (badges != "dub") anime.episode?.let { episode ->
                    val total = anime.totalEpisodes?.takeIf { it > episode }?.let { "/$it" }.orEmpty()
                    PhoneBadge("EP $episode$total", MaterialTheme.colorScheme.primary)
                }
                if (badges != "sub") anime.dubEpisodes?.let { dub ->
                    val total = anime.totalEpisodes?.takeIf { badges == "dub" && it > dub }?.let { "/$it" }.orEmpty()
                    PhoneBadge("DUB $dub$total", MaterialTheme.colorScheme.tertiary)
                }
            }
            if (anime.isAdult) Box(Modifier.align(Alignment.BottomStart).padding(6.dp)) { PhoneBadge("+18", Color(0xFFDC2626)) }
        }
        Text(anime.displayTitle(japanese), style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        val meta = listOfNotNull(anime.mediaType, anime.year?.toString(), anime.score?.let { "★ %.1f".format(it) }, anime.rating?.takeUnless { it == "?" }).joinToString(" · ")
        if (meta.isNotEmpty()) Text(meta, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

@Composable
internal fun PhoneBadge(text: String, color: Color) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = Color.White,
        modifier = Modifier.background(color.copy(alpha = 0.9f), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/** Filters in a bottom sheet: chips per category; nothing applies until "Apply". */
@Composable
private fun PhoneFiltersSheet(current: CatalogFilters, options: CatalogFilterOptions?, hideAdult: Boolean, onDismiss: () -> Unit, onApply: (CatalogFilters) -> Unit) {
    var draft by remember { mutableStateOf(current) }
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    /** One category open at a time; the others show what is chosen. */
    var open by remember { mutableStateOf<String?>(null) }
    val anyLabel = stringResource(R.string.filter_any)

    @Composable
    fun Group(title: String, summary: String, active: Boolean, content: @Composable () -> Unit) {
        val expanded = open == title
        ListItem(
            headlineContent = { Text(title) },
            supportingContent = {
                Text(summary, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            },
            trailingContent = { Icon(if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, contentDescription = null) },
            modifier = Modifier.clickable { open = if (expanded) null else title },
        )
        if (expanded) Box(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp)) { content() }
        HorizontalDivider()
    }

    @Composable
    fun Section(title: String, list: List<FilterOption>, selected: Set<String>, update: (Set<String>) -> Unit) {
        if (list.isEmpty()) return
        val visible = list.filter { !(hideAdult && it.adult) }
        val chosen = visible.filter { it.id in selected }.joinToString(", ") { it.displayLabel }
        Group(title, chosen.ifEmpty { anyLabel }, selected.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                visible.forEach { option ->
                    FilterChip(
                        selected = option.id in selected,
                        onClick = { update(if (option.id in selected) selected - option.id else selected + option.id) },
                        label = { Text(option.displayLabel) },
                    )
                }
            }
        }
    }

    @Composable
    fun <T> Single(title: String, choices: List<Pair<T, String>>, selected: T, update: (T) -> Unit) {
        Group(title, choices.firstOrNull { it.first == selected }?.second ?: anyLabel, selected != choices.firstOrNull()?.first) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                choices.forEach { (value, label) -> FilterChip(selected = value == selected, onClick = { update(value) }, label = { Text(label) }) }
            }
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.filters), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = { draft = CatalogFilters() }, enabled = draft.isActive) { Text(stringResource(R.string.clear)) }
            Button(onClick = { onApply(draft) }) { Text(stringResource(R.string.apply)) }
        }
        HorizontalDivider(Modifier.padding(top = 8.dp))
        val any = stringResource(R.string.filter_any)
        val defaultSort = stringResource(R.string.filter_sort_default)
        LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
            item {
                Text(
                    stringResource(R.string.filters_hint_phone),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            item { Section(stringResource(R.string.filter_genre), options?.genres.orEmpty(), draft.genres) { draft = draft.copy(genres = it) } }
            item { Section(stringResource(R.string.filter_season), BuiltInFilters.seasons, draft.seasons) { draft = draft.copy(seasons = it) } }
            item { Section(stringResource(R.string.filter_year), BuiltInFilters.years, draft.years) { draft = draft.copy(years = it) } }
            item { Section(stringResource(R.string.filter_status), BuiltInFilters.statuses, draft.statuses) { draft = draft.copy(statuses = it) } }
            item { Section(stringResource(R.string.filter_type), options?.mediaTypes ?: BuiltInFilters.mediaTypes, draft.mediaTypes) { draft = draft.copy(mediaTypes = it) } }
            item { Section(stringResource(R.string.filter_source), options?.sources.orEmpty(), draft.sources) { draft = draft.copy(sources = it) } }
            item { Section(stringResource(R.string.filter_rating), BuiltInFilters.ratings, draft.ratings) { draft = draft.copy(ratings = it) } }
            if (options != null) item {
                Single(stringResource(R.string.filter_episodes), listOf<EpisodeRange?>(null).plus(EpisodeRange.entries).map { it to (it?.label ?: any) }, draft.episodes) { draft = draft.copy(episodes = it) }
            }
            item {
                Single(stringResource(R.string.filter_min_score), listOf(0, 5, 6, 7, 8, 9).map { it to if (it == 0) any else "$it.0+" }, draft.minimumScore) { draft = draft.copy(minimumScore = it) }
            }
            if (options != null && options.sorts.isNotEmpty()) item {
                Single(stringResource(R.string.filter_sort), listOf("" to defaultSort) + options.sorts.map { it.id to it.displayLabel }, draft.sort) { draft = draft.copy(sort = it) }
            }
        }
    }
}
