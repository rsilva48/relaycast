@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package net.silvara.relaycast

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch

/**
 * Opens what is connected right away (the Cast controls, or the TV remote),
 * or [PhoneDevicesSheet] to choose; green while connected, as on iOS.
 */
@Composable
internal fun DevicesButton(app: PhoneState) {
    val connected by PhoneCast.connected.collectAsState()
    // A linked TV is only remembered, not necessarily connected or playing:
    // the button opens the Devices sheet (Cast and the TV app), or the Cast
    // controls while a Cast session is on; green only for that session.
    IconButton(onClick = {
        app.devicesCastOnly = connected
        app.devicesOpen = true
    }) {
        Icon(
            if (connected) PhoneIcons.CastConnected else PhoneIcons.Tv,
            contentDescription = stringResource(R.string.devices),
            tint = if (connected) androidx.compose.ui.graphics.Color(0xFF30D158) else androidx.compose.material3.LocalContentColor.current,
        )
    }
}

/**
 * Every screen this app plays on, in one place, as on iOS: Google Cast
 * (choose a device, or control what is casting and reopen it in the player,
 * also after the app was closed or updated: the framework resumes the
 * session) and the TV app of this identity. AirPlay is Apple's: Android
 * has no AirPlay sender.
 */
@Composable
internal fun PhoneDevicesSheet(app: PhoneState, session: Session) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val connected by PhoneCast.connected.collectAsState()
    val deviceName by PhoneCast.deviceName.collectAsState()
    val state by PhoneCast.state.collectAsState()
    val position by PhoneCast.position.collectAsState()
    val duration by PhoneCast.duration.collectAsState()
    val volume by PhoneCast.volume.collectAsState()
    val loaded by PhoneCast.loadedUrl.collectAsState()
    val title by PhoneCast.mediaTitle.collectAsState()
    val subtitle by PhoneCast.mediaSubtitle.collectAsState()
    val image by PhoneCast.mediaImage.collectAsState()
    var showPicker by remember { mutableStateOf(false) }
    /** Only the Cast controls when opened for a connected device; the rest on request. */
    var showAll by remember { mutableStateOf(!app.devicesCastOnly) }
    var opening by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    /** Position under the finger while scrubbing. */
    var scrub by remember { mutableStateOf<Float?>(null) }
    val casting = connected && loaded != null && state != CastPlayback.Idle
    // Read again whenever the loaded media changes.
    val casted = remember(loaded) { PhoneCast.castedEpisode(context) }

    fun close() { app.devicesOpen = false }

    /** Opens the casting episode in the player, which takes it over where the receiver is. */
    fun open(episode: CastedEpisode) {
        opening = true
        error = null
        scope.launch {
            try {
                val series = session.client.series(episode.anime.watchUrl)
                val target = series.episodes.firstOrNull { it.number == episode.episode } ?: throw RelayException(Strings.get(R.string.cast_episode_missing))
                val request = session.resolve(episode.anime, series, target, episode.kind, episode.server)
                close()
                app.playing = request
            } catch (failure: RelayException) {
                error = failure.message
            } finally {
                opening = false
            }
        }
    }

    ModalBottomSheet(onDismissRequest = ::close) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(if (showAll) stringResource(R.string.devices) else deviceName ?: "Google Cast", style = MaterialTheme.typography.titleLarge)

            Text("Google Cast", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            if (casting) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    AsyncImage(
                        model = image,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(52.dp, 74.dp).clip(RoundedCornerShape(6.dp)),
                    )
                    Column(Modifier.weight(1f)) {
                        Text(title ?: stringResource(R.string.casting), style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1) }
                        Text(stringResource(R.string.casting_on, deviceName ?: stringResource(R.string.cast_title)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (duration > 0) {
                    Slider(
                        value = scrub ?: position.toFloat().coerceIn(0f, duration.toFloat()),
                        onValueChange = { scrub = it },
                        onValueChangeFinished = { scrub?.let { PhoneCast.seek(it.toDouble()) }; scrub = null },
                        valueRange = 0f..duration.toFloat(),
                    )
                    Row {
                        Text(formatTime((scrub?.toDouble() ?: position)), style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.weight(1f))
                        Text(formatTime(duration), style = MaterialTheme.typography.bodySmall)
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { PhoneCast.seek((position - 10).coerceAtLeast(0.0)) }) { Icon(PhoneIcons.Replay10, contentDescription = stringResource(R.string.rewind_10)) }
                    Spacer(Modifier.width(24.dp))
                    FilledIconButton(onClick = { if (state == CastPlayback.Paused) PhoneCast.play() else PhoneCast.pause() }, modifier = Modifier.size(56.dp)) {
                        Icon(
                            if (state == CastPlayback.Paused) Icons.Filled.PlayArrow else PhoneIcons.Pause,
                            contentDescription = stringResource(if (state == CastPlayback.Paused) R.string.play else R.string.pause),
                        )
                    }
                    Spacer(Modifier.width(24.dp))
                    IconButton(onClick = { PhoneCast.seek(position + 10) }) { Icon(PhoneIcons.Forward10, contentDescription = stringResource(R.string.forward_10)) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(PhoneIcons.VolumeDown, contentDescription = null)
                    Slider(value = volume, onValueChange = { PhoneCast.setVolume(it) }, modifier = Modifier.weight(1f))
                    Icon(PhoneIcons.VolumeUp, contentDescription = stringResource(R.string.volume))
                }
                casted?.let { episode ->
                    OutlinedButton(onClick = { open(episode) }, enabled = !opening, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(if (opening) R.string.cast_opening else R.string.open_in_player))
                    }
                }
                TextButton(onClick = { PhoneCast.disconnect() }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.stop_casting), color = MaterialTheme.colorScheme.error) }
                Text(
                    stringResource(if (casted == null) R.string.cast_other_sender else R.string.cast_keeps_going),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (connected) {
                Text(stringResource(R.string.cast_connected_to, deviceName ?: stringResource(R.string.cast_title)))
                OutlinedButton(onClick = { showPicker = true }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.cast_device_volume)) }
                TextButton(onClick = { PhoneCast.disconnect() }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.disconnect), color = MaterialTheme.colorScheme.error) }
                Text(stringResource(R.string.cast_play_to_send), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else if (PhoneCast.selector != null) {
                // The devices right here, as on iOS (no separate window).
                CastRouteList(rememberCastRoutes())
            } else {
                // Cast unavailable on this phone (no Google Play services).
                Text(stringResource(R.string.cast_none_on_wifi), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

            if (!showAll) {
                TextButton(onClick = { showAll = true }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.other_devices)) }
            } else {
            Text(stringResource(R.string.tv_with_app), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            OutlinedButton(onClick = { close(); app.tvPlay = null; app.remoteOpen = true }, modifier = Modifier.fillMaxWidth()) {
                Icon(PhoneIcons.Tv, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.watch_on_tv_with_app))
            }
            Text(stringResource(R.string.tv_with_app_footer), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    if (showPicker) CastDialog(onDismiss = { showPicker = false })
}
