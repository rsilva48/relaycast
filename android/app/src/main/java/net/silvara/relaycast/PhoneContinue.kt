@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package net.silvara.relaycast

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch

/**
 * Continue watching, in the same sections as on iOS and the TV: new
 * episodes first, then in progress, caught up and completed. Holding a
 * series offers to remove it (also on the other devices).
 */
@Composable
internal fun PhoneContinue(session: Session, onOpen: (Anime) -> Unit) {
    val app = LocalPhone.current
    val scope = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<HistoryEntry?>(null) }
    val entries = session.history
    val finished = entries.filter { it.isCompleted }.map { catalogPath(it.watchUrl) }.toSet()
    LaunchedEffect(finished) { session.loadStatuses() }
    val states = entries.associate { it.watchUrl to continueState(session, it) }
    fun title(entry: HistoryEntry) = if (session.japanese) entry.titleJp ?: entry.seriesTitle else entry.seriesTitle
    // Each section folds (tap its title); Completed always goes last, folded at first.
    val context = androidx.compose.ui.platform.LocalContext.current
    val folding = remember { context.getSharedPreferences("relaycast", android.content.Context.MODE_PRIVATE) }
    var folded by remember { mutableStateOf(folding.getStringSet("continue.folded", setOf("completed")).orEmpty()) }
    val sections = listOf(
        ContinueSection("new", stringResource(R.string.continue_new_episodes), null, entries.filter { states[it.watchUrl]?.section == 0 }),
        ContinueSection("progress", stringResource(R.string.continue_in_progress), null, entries.filter { states[it.watchUrl]?.section == 1 }),
        ContinueSection("current", stringResource(R.string.continue_up_to_date), stringResource(R.string.continue_up_to_date_footer), entries.filter { states[it.watchUrl]?.section == 2 }),
        ContinueSection("completed", stringResource(R.string.continue_completed), null, entries.filter { states[it.watchUrl]?.section == 3 }),
    ).filter { it.entries.isNotEmpty() }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.continue_watching)) },
            actions = {
                DevicesButton(app)
            },
        )
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = { scope.launch { refreshing = true; session.refresh(); session.loadStatuses(); refreshing = false } },
            modifier = Modifier.fillMaxSize(),
        ) {
            if (entries.isEmpty()) {
                PhoneMessage(stringResource(R.string.nothing_to_continue), session.historyError ?: stringResource(R.string.continue_empty_phone), icon = PhoneIcons.Play)
                return@PullToRefreshBox
            }
            LazyColumn(Modifier.fillMaxSize()) {
                for ((key, heading, footer, group) in sections) {
                    val isFolded = key in folded
                    item(key = "section:$key") {
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                folded = if (isFolded) folded - key else folded + key
                                folding.edit().putStringSet("continue.folded", folded).apply()
                            }.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(heading, style = MaterialTheme.typography.titleMedium)
                            Text("  ${group.size}", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                            Icon(if (isFolded) Icons.Filled.KeyboardArrowDown else Icons.Filled.KeyboardArrowUp, contentDescription = null)
                        }
                    }
                    if (isFolded) continue
                    items(group, key = { it.watchUrl }) { entry ->
                        val state = states.getValue(entry.watchUrl)
                        var menu by remember { mutableStateOf(false) }
                        Box {
                            ListItem(
                                headlineContent = { Text(title(entry), maxLines = 2, overflow = TextOverflow.Ellipsis) },
                                supportingContent = {
                                    Column {
                                        Text(
                                            state.label(entry),
                                            color = if (state.isNews) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        Text(
                                            "${entry.serverName} · ${if (entry.serverKind == "dub") "Dub" else "Sub"}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                },
                                leadingContent = {
                                    AsyncImage(
                                        model = entry.imageUrl,
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.width(56.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
                                    )
                                },
                                modifier = Modifier.combinedClickable(
                                    onClick = {
                                        // A finished series with a new season opens that season.
                                        val next = (state as? ContinueState.NextSeason)?.season
                                        onOpen(next?.asAnime() ?: entry.asAnime())
                                    },
                                    onLongClick = { menu = true },
                                ),
                            )
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.remove_from_continue)) },
                                    leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                                    onClick = { menu = false; removing = entry },
                                )
                            }
                        }
                    }
                    footer?.let { text ->
                        item(key = "footer:$heading") {
                            Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                        }
                    }
                }
            }
        }
    }

    removing?.let { entry ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text(stringResource(R.string.remove_title, title(entry))) },
            text = { Text(stringResource(R.string.remove_continue_text)) },
            confirmButton = {
                TextButton(onClick = {
                    removing = null
                    scope.launch { session.remove(entry) }
                }) { Text(stringResource(R.string.remove)) }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/** A section of Continue: its key (for folding), title, footer and series. */
private data class ContinueSection(val key: String, val title: String, val footer: String?, val entries: List<HistoryEntry>)
