@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package net.silvara.relaycast

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private fun DeviceState.isActive(): Boolean = status != "idle" && seriesTitle != null

private fun DeviceState.isShowing(command: RemoteCommand): Boolean =
    status != "idle" && episode == command.episode && watchUrl != null && command.watchUrl != null &&
        catalogPath(watchUrl) == catalogPath(command.watchUrl)

/**
 * The TV remote, as on iOS: the chosen TV is remembered until
 * "Disconnect"; opening it only shows the TV's state, and an episode chosen
 * with "Watch on TV" is sent once (never restarting what the TV shows).
 */
@Composable
internal fun PhoneRemoteSheet(app: PhoneState, session: Session) {
    val scope = rememberCoroutineScope()
    var tvs by remember { mutableStateOf<List<RemoteTv>>(emptyList()) }
    var receivedAt by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var loaded by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf(app.tvPlay) }
    var scrub by remember { mutableStateOf<Float?>(null) }
    var seekTarget by remember { mutableStateOf<Pair<Double, Long>?>(null) }
    val connected = app.connectedTv
    val tv = tvs.firstOrNull { it.id == connected?.id }

    fun close() {
        app.remoteOpen = false
        app.tvPlay = null
    }

    suspend fun refresh() {
        try {
            tvs = session.client.tvs()
            receivedAt = System.currentTimeMillis()
            error = null
        } catch (failure: RelayException) {
            error = if (failure.status == 404) Strings.get(R.string.remote_unsupported) else failure.message
        }
        loaded = true
    }

    fun send(command: RemoteCommand, to: String) {
        scope.launch {
            try {
                session.client.sendCommand(to, command)
                delay(500)
                refresh()
            } catch (failure: RelayException) {
                error = failure.message
            }
        }
    }

    /** The "Watch on TV" episode, once, unless the TV already shows it. */
    fun startPending(target: RemoteTv) {
        val request = pending ?: return
        if (!request.autoplay) return
        pending = null
        if (target.state?.isShowing(request.command) == true) return
        send(request.command, target.id)
    }

    LaunchedEffect(Unit) {
        while (true) {
            refresh()
            tvs.firstOrNull { it.id == app.connectedTv?.id }?.let(::startPending)
            delay(if (app.connectedTv == null) 3000 else 1500)
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }

    ModalBottomSheet(onDismissRequest = ::close) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(connected?.name ?: stringResource(R.string.watch_on_tv), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                if (connected != null) TextButton(onClick = { app.connect(null) }) { Text(stringResource(R.string.disconnect), color = MaterialTheme.colorScheme.error) }
            }
            // From the Devices button: Cast while a TV is connected.
            if (connected != null) TextButton(onClick = { close(); app.devicesCastOnly = false; app.devicesOpen = true }) { Text(stringResource(R.string.other_devices)) }
            when {
                connected == null -> {
                    Text(stringResource(R.string.connect_to_tv), style = MaterialTheme.typography.titleSmall)
                    if (tvs.isEmpty()) {
                        Text(
                            stringResource(if (loaded) R.string.no_tvs else R.string.searching_tvs),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    tvs.forEach { item ->
                        ListItem(
                            headlineContent = { Text(item.name) },
                            supportingContent = {
                                Column {
                                    Text(summary(item.state))
                                    // Not a condition: commands go through the relay.
                                    if (item.sameNetwork == false) Text(stringResource(R.string.tv_other_network))
                                }
                            },
                            leadingContent = { Icon(PhoneIcons.Tv, contentDescription = null) },
                            modifier = Modifier.clickable {
                                app.connect(ConnectedTv(item.id, item.name))
                                startPending(item)
                            },
                        )
                    }
                    Text(stringResource(R.string.remember_tv), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                tv == null -> {
                    Text(
                        if (loaded) stringResource(R.string.tv_unavailable, connected.name) else stringResource(R.string.connecting),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    val here = pending?.playHere
                    if (loaded && here != null) Button(onClick = { close(); here() }) { Text(stringResource(R.string.play_on_this_phone)) }
                }
                else -> {
                    val state = tv.state
                    val request = pending
                    if (request != null && !request.autoplay && state?.isShowing(request.command) != true) {
                        Button(onClick = { pending = null; send(request.command, tv.id) }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Filled.PlayArrow, null)
                            Text(" " + stringResource(R.string.play_on_tv))
                        }
                        Text(request.title, style = MaterialTheme.typography.bodySmall)
                    }
                    if (state != null && state.isActive()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            AsyncImage(
                                model = state.imageUrl,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.width(56.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
                            )
                            Column {
                                Text(state.seriesTitle.orEmpty(), style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                state.episode?.let { episode ->
                                    Text(state.episodeTitle?.let { stringResource(R.string.episode_with_title, episode, it) } ?: stringResource(R.string.episode_number, episode), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                if (state.status == "loading") Text(stringResource(R.string.loading_on_tv), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                        val duration = state.duration
                        if (duration != null && duration > 0) {
                            // Between reports (every 5 s while playing) the position advances here.
                            var position = state.position ?: 0.0
                            if (state.status == "playing") position += (tv.stateAge ?: 0.0) / 1000 + (now - receivedAt) / 1000.0
                            seekTarget?.let { (target, until) -> if (now < until && kotlin.math.abs(position - target) > 3) position = target }
                            val shown = scrub?.toDouble() ?: position.coerceIn(0.0, duration)
                            Slider(
                                value = shown.toFloat(),
                                onValueChange = { scrub = it },
                                valueRange = 0f..duration.toFloat(),
                                onValueChangeFinished = {
                                    scrub?.let { target ->
                                        seekTarget = target.toDouble() to System.currentTimeMillis() + 6000
                                        send(RemoteCommand(type = "seek", position = target.toDouble()), tv.id)
                                    }
                                    scrub = null
                                },
                            )
                            Row {
                                Text(formatTime(shown), style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                                Text(formatTime(duration), style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { send(RemoteCommand(type = "previous"), tv.id) }) { Icon(PhoneIcons.SkipPrevious, stringResource(R.string.previous_episode)) }
                            IconButton(onClick = { send(RemoteCommand(type = "seek", seconds = -10.0), tv.id) }) { Icon(PhoneIcons.Replay10, stringResource(R.string.rewind_10)) }
                            FilledIconButton(onClick = { send(RemoteCommand(type = "toggle"), tv.id) }, modifier = Modifier.size(56.dp)) {
                                Icon(if (state.status == "playing") PhoneIcons.Pause else Icons.Filled.PlayArrow, stringResource(if (state.status == "playing") R.string.pause else R.string.play))
                            }
                            IconButton(onClick = { send(RemoteCommand(type = "seek", seconds = 10.0), tv.id) }) { Icon(PhoneIcons.Forward10, stringResource(R.string.forward_10)) }
                            IconButton(onClick = { send(RemoteCommand(type = "next"), tv.id) }) { Icon(PhoneIcons.SkipNext, stringResource(R.string.next_episode)) }
                        }
                        state.skip?.let { label -> OutlinedButton(onClick = { send(RemoteCommand(type = "skip"), tv.id) }) { Text(label) } }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (state.subtitles.isNotEmpty()) {
                                var menu by remember { mutableStateOf(false) }
                                Box(Modifier.weight(1f)) {
                                    OutlinedButton(onClick = { menu = true }) {
                                        Icon(PhoneIcons.Subtitles, null)
                                        Text(" " + (state.subtitles.firstOrNull { it.id == state.subtitle }?.label ?: stringResource(R.string.subtitles_off)), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                        DropdownMenuItem(text = { Text(stringResource(R.string.subtitles_off)) }, onClick = { menu = false; send(RemoteCommand(type = "subtitles", track = "off"), tv.id) })
                                        state.subtitles.forEach { track ->
                                            DropdownMenuItem(text = { Text(track.label) }, onClick = { menu = false; send(RemoteCommand(type = "subtitles", track = track.id), tv.id) })
                                        }
                                    }
                                }
                            } else {
                                Box(Modifier.weight(1f))
                            }
                            IconButton(onClick = { send(RemoteCommand(type = "volume", steps = -1), tv.id) }) { Icon(PhoneIcons.VolumeDown, stringResource(R.string.tv_volume_down)) }
                            IconButton(onClick = { send(RemoteCommand(type = "volume", steps = 1), tv.id) }) { Icon(PhoneIcons.VolumeUp, stringResource(R.string.tv_volume_up)) }
                        }
                        TextButton(onClick = { send(RemoteCommand(type = "stop"), tv.id) }) {
                            Icon(PhoneIcons.Stop, null, tint = MaterialTheme.colorScheme.error)
                            Text(" " + stringResource(R.string.stop_on_tv), color = MaterialTheme.colorScheme.error)
                        }
                    } else if (request == null || request.autoplay) {
                        Text(stringResource(R.string.tv_idle_hint), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (tv.canOpen == false && state?.isActive() != true) {
                        Text(
                            stringResource(R.string.tv_cannot_open),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

private fun summary(state: DeviceState?): String {
    if (state == null || !state.isActive()) return Strings.get(R.string.tv_idle)
    val status = Strings.get(when (state.status) { "playing" -> R.string.tv_playing; "loading" -> R.string.tv_loading; else -> R.string.tv_paused })
    return state.episode?.let { Strings.get(R.string.tv_summary_episode, status, state.seriesTitle.orEmpty(), it) }
        ?: Strings.get(R.string.tv_summary, status, state.seriesTitle.orEmpty())
}
