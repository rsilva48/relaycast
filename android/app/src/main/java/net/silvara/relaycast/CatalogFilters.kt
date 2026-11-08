@file:OptIn(ExperimentalTvMaterial3Api::class, ExperimentalLayoutApi::class)

package net.silvara.relaycast

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.FilterChip
import androidx.tv.material3.Icon
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import java.util.Calendar
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable

/**
 * Catalog filters, as on the phone: several values per category (a series
 * matches any of them), sent to the relay as repeated query parameters.
 * The minimum score is applied here, to the results already loaded.
 */
data class CatalogFilters(
    val genres: Set<String> = emptySet(),
    val seasons: Set<String> = emptySet(),
    val years: Set<String> = emptySet(),
    val statuses: Set<String> = emptySet(),
    val mediaTypes: Set<String> = emptySet(),
    val sources: Set<String> = emptySet(),
    val ratings: Set<String> = emptySet(),
    /** Catalog order ("" = the relay's default). */
    val sort: String = "",
    val episodes: EpisodeRange? = null,
    val minimumScore: Int = 0,
) {
    val isActive: Boolean get() = this != CatalogFilters()

    /** Chosen values, shown on the filter button. */
    val activeCount: Int
        get() = genres.size + seasons.size + years.size + statuses.size + mediaTypes.size + sources.size + ratings.size +
            listOf(sort.isNotEmpty(), episodes != null, minimumScore > 0).count { it }

    fun query(): List<Pair<String, String>> = buildList {
        listOf("genre" to genres, "season" to seasons, "year" to years, "status" to statuses, "mediaType" to mediaTypes, "source" to sources, "rating" to ratings)
            .forEach { (name, values) -> values.sorted().forEach { add(name to it) } }
        if (sort.isNotEmpty()) add("sort" to sort)
        episodes?.min?.let { add("minEpisodes" to it.toString()) }
        episodes?.max?.let { add("maxEpisodes" to it.toString()) }
    }

    fun allows(anime: Anime): Boolean = minimumScore == 0 || (anime.score ?: 0.0) >= minimumScore
}

/** Episode-count presets: typing numbers with a TV remote is slow. */
enum class EpisodeRange(val min: Int, val max: Int?) {
    Single(1, 1),
    Short(2, 13),
    Season(12, 26),
    Long(24, 52),
    VeryLong(50, null);

    val label: String
        get() = when {
            this == Single -> Strings.get(R.string.episodes_single)
            max == null -> Strings.get(R.string.episodes_at_least, min)
            else -> Strings.get(R.string.episodes_range, min, max)
        }
}

/** An option; the relay sends `label` in Spanish and `labelEn` in English. */
@Serializable
data class FilterOption(val id: String, val label: String, val adult: Boolean = false, val labelEn: String? = null) {
    val displayLabel: String get() = if (Strings.spanish) label else labelEn ?: label
}

/** Option lists published by the relay; genre ids belong to the catalog. */
@Serializable
data class CatalogFilterOptions(
    val genres: List<FilterOption> = emptyList(),
    val mediaTypes: List<FilterOption> = emptyList(),
    val sources: List<FilterOption> = emptyList(),
    val sorts: List<FilterOption> = emptyList(),
)

internal object BuiltInFilters {
    // Labels in the app's language.
    val seasons: List<FilterOption>
        get() = listOf("winter" to R.string.season_winter, "spring" to R.string.season_spring, "summer" to R.string.season_summer, "fall" to R.string.season_fall)
            .map { FilterOption(it.first, Strings.get(it.second)) }
    val statuses: List<FilterOption>
        get() = listOf("currently-airing" to R.string.status_airing, "finished-airing" to R.string.status_finished, "not-yet-aired" to R.string.status_upcoming)
            .map { FilterOption(it.first, Strings.get(it.second)) }
    val ratings = listOf("G", "PG", "PG-13", "R", "R+", "Rx").map { FilterOption(it, it) }
    // For relays without the option lists.
    val mediaTypes = listOf("TV", "Movie", "ONA", "OVA", "Special").map { FilterOption(it, it) }
    val years: List<FilterOption>
        get() = (Calendar.getInstance().get(Calendar.YEAR) + 1 downTo 1980).map { FilterOption(it.toString(), it.toString()) }
}

/** Short description of the active filters, shown next to the Filters button. */
fun CatalogFilters.summary(options: CatalogFilterOptions?): String {
    fun labels(values: Set<String>, list: List<FilterOption>) = list.filter { it.id in values }.map { it.displayLabel }
    val parts = labels(genres, options?.genres.orEmpty()) + labels(seasons, BuiltInFilters.seasons) + years.sortedDescending() +
        labels(statuses, BuiltInFilters.statuses) + labels(mediaTypes, options?.mediaTypes ?: BuiltInFilters.mediaTypes) +
        labels(sources, options?.sources.orEmpty()) + ratings.sorted() + listOfNotNull(
            options?.sorts?.firstOrNull { it.id == sort }?.displayLabel,
            episodes?.let { Strings.get(R.string.filters_summary_episodes, it.label) },
            minimumScore.takeIf { it > 0 }?.let { "★ $it+" },
        )
    return parts.joinToString(" · ")
}

/**
 * Full-screen filter picker: the categories fold, as on the phone (one open
 * at a time, what is chosen under each name), navigable with the D-pad.
 * Choices apply only with "Apply".
 */
@Composable
fun CatalogFiltersDialog(
    current: CatalogFilters,
    options: CatalogFilterOptions?,
    hideAdult: Boolean,
    onApply: (CatalogFilters) -> Unit,
    onDismiss: () -> Unit,
) {
    var draft by remember { mutableStateOf(current) }
    /** One category open at a time; the others show what is chosen. */
    var open by remember { mutableStateOf<String?>(null) }
    val anyLabel = stringResource(R.string.filter_any)

    /**
     * A category: a focusable header (OK opens or closes it) with what is
     * chosen below its name, and its chips while open; opening moves the
     * focus to the first chip.
     */
    @Composable
    fun Group(title: String, summary: String, active: Boolean, chips: @Composable (first: Modifier) -> Unit) {
        val expanded = open == title
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ListItem(
                selected = expanded,
                onClick = { open = if (expanded) null else title },
                headlineContent = { Text(title, style = MaterialTheme.typography.titleLarge) },
                supportingContent = {
                    Text(
                        summary,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = if (active) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.7f),
                    )
                },
                trailingContent = {
                    Icon(if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, contentDescription = null)
                },
                // Focus as a soft white veil with white text: the default
                // light gray turned the summary (white) unreadable.
                colors = androidx.tv.material3.ListItemDefaults.colors(
                    contentColor = Color.White,
                    selectedContainerColor = Color.White.copy(alpha = 0.08f),
                    selectedContentColor = Color.White,
                    focusedContainerColor = Color.White.copy(alpha = 0.16f),
                    focusedContentColor = Color.White,
                    focusedSelectedContainerColor = Color.White.copy(alpha = 0.2f),
                    focusedSelectedContentColor = Color.White,
                    pressedContainerColor = Color.White.copy(alpha = 0.2f),
                    pressedContentColor = Color.White,
                    pressedSelectedContainerColor = Color.White.copy(alpha = 0.24f),
                    pressedSelectedContentColor = Color.White,
                ),
            )
            if (expanded) {
                val first = remember { FocusRequester() }
                LaunchedEffect(Unit) {
                    delay(60)
                    runCatching { first.requestFocus() }
                }
                FlowRow(
                    Modifier.padding(start = 16.dp, bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) { chips(Modifier.focusRequester(first)) }
            }
        }
    }

    @Composable
    fun Section(title: String, list: List<FilterOption>, selected: Set<String>, update: (Set<String>) -> Unit) {
        if (list.isEmpty()) return
        val visible = list.filter { !(hideAdult && it.adult) }
        val chosen = visible.filter { it.id in selected }.joinToString(", ") { it.displayLabel }
        Group(title, chosen.ifEmpty { anyLabel }, selected.isNotEmpty()) { first ->
            visible.forEachIndexed { index, option ->
                FilterChip(selected = option.id in selected, modifier = if (index == 0) first else Modifier, onClick = {
                    update(if (option.id in selected) selected - option.id else selected + option.id)
                }) { Text(option.displayLabel) }
            }
        }
    }

    @Composable
    fun <T> SingleChoice(title: String, choices: List<Pair<T, String>>, selected: T, update: (T) -> Unit) {
        Group(title, choices.firstOrNull { it.first == selected }?.second ?: anyLabel, selected != choices.firstOrNull()?.first) { first ->
            choices.forEachIndexed { index, (value, label) ->
                FilterChip(selected = value == selected, modifier = if (index == 0) first else Modifier, onClick = { update(value) }) { Text(label) }
            }
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(Color(0xFF0B0B12)).padding(horizontal = 64.dp, vertical = 40.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.filters), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(end = 24.dp))
                Button(onClick = { onApply(draft) }) { Text(stringResource(R.string.apply)) }
                OutlinedButton(onClick = { draft = CatalogFilters() }, enabled = draft.isActive) { Text(stringResource(R.string.clear)) }
                OutlinedButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            }
            Text(
                stringResource(R.string.filters_hint_tv),
                color = Color.White.copy(alpha = 0.6f),
            )
            val any = stringResource(R.string.filter_any)
            val defaultSort = stringResource(R.string.filter_sort_default)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 48.dp)) {
                item { Section(stringResource(R.string.filter_genre), options?.genres.orEmpty(), draft.genres) { draft = draft.copy(genres = it) } }
                item { Section(stringResource(R.string.filter_season), BuiltInFilters.seasons, draft.seasons) { draft = draft.copy(seasons = it) } }
                item { Section(stringResource(R.string.filter_year), BuiltInFilters.years, draft.years) { draft = draft.copy(years = it) } }
                item { Section(stringResource(R.string.filter_status), BuiltInFilters.statuses, draft.statuses) { draft = draft.copy(statuses = it) } }
                item { Section(stringResource(R.string.filter_type), options?.mediaTypes ?: BuiltInFilters.mediaTypes, draft.mediaTypes) { draft = draft.copy(mediaTypes = it) } }
                item { Section(stringResource(R.string.filter_source), options?.sources.orEmpty(), draft.sources) { draft = draft.copy(sources = it) } }
                item { Section(stringResource(R.string.filter_rating), BuiltInFilters.ratings, draft.ratings) { draft = draft.copy(ratings = it) } }
                if (options != null) item {
                    SingleChoice(stringResource(R.string.filter_episodes), listOf<EpisodeRange?>(null) .plus(EpisodeRange.entries).map { it to (it?.label ?: any) }, draft.episodes) {
                        draft = draft.copy(episodes = it)
                    }
                }
                item {
                    SingleChoice(stringResource(R.string.filter_min_score), listOf(0, 5, 6, 7, 8, 9).map { it to if (it == 0) any else "$it.0+" }, draft.minimumScore) {
                        draft = draft.copy(minimumScore = it)
                    }
                }
                if (options != null && options.sorts.isNotEmpty()) item {
                    SingleChoice(stringResource(R.string.filter_sort), listOf("" to defaultSort) + options.sorts.map { it.id to it.displayLabel }, draft.sort) {
                        draft = draft.copy(sort = it)
                    }
                }
            }
        }
    }
}
