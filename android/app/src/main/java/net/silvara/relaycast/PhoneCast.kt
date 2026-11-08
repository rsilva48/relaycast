package net.silvara.relaycast

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.mediarouter.media.MediaRouter
import com.google.android.gms.cast.Cast
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.HlsSegmentFormat
import com.google.android.gms.cast.HlsVideoSegmentFormat
import com.google.android.gms.cast.MediaError
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaSeekOptions
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.MediaTrack
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.SessionProvider
import com.google.android.gms.cast.framework.media.CastMediaOptions
import com.google.android.gms.cast.framework.media.MediaIntentReceiver
import com.google.android.gms.cast.framework.media.NotificationOptions
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import com.google.android.gms.common.images.WebImage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Google Cast with the Default Media Receiver (no receiver of our own to register). */
class CastOptionsProvider : OptionsProvider {
    override fun getCastOptions(context: Context): CastOptions = CastOptions.Builder()
        .setReceiverApplicationId(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID)
        // While casting: the framework's media notification and media
        // session (lock screen, notification shade, headphones), with
        // play/pause, ±10 s and stop; tapping it opens the app.
        .setCastMediaOptions(
            CastMediaOptions.Builder()
                .setMediaSessionEnabled(true)
                .setNotificationOptions(
                    NotificationOptions.Builder()
                        .setActions(
                            listOf(MediaIntentReceiver.ACTION_REWIND, MediaIntentReceiver.ACTION_TOGGLE_PLAYBACK, MediaIntentReceiver.ACTION_FORWARD, MediaIntentReceiver.ACTION_STOP_CASTING),
                            intArrayOf(0, 1, 2),
                        )
                        .setSkipStepMs(10_000)
                        .setTargetActivityClassName(MainActivity::class.java.name)
                        .build(),
                )
                .build(),
        )
        .build()

    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null
}

/**
 * The episode this phone last sent to the receiver, with its cast link (which
 * identifies the media there): the player takes over what the receiver plays
 * after the player closed, or the app was closed or updated, instead of
 * loading it again.
 */
@Serializable
internal data class CastedEpisode(val share: CastShare, val anime: Anime, val episode: Int, val kind: String, val server: String? = null)

/** A temporary link the relay makes so a receiver loads one playback without the identity token. */
@Serializable
data class CastShare(val id: String, val url: String, val contentType: String? = null, val subtitles: List<CastSubtitle> = emptyList())

/** A subtitle file of a cast link; `id` is the receiver's track id. */
@Serializable
data class CastSubtitle(
    val id: Long,
    val name: String,
    val language: String,
    val url: String,
    val forced: Boolean = false,
    @SerialName("default") val isDefault: Boolean = false,
)

@Serializable
data class CastShareRequest(val playbackUrl: String)

internal enum class CastPlayback { Idle, Loading, Buffering, Playing, Paused }

/**
 * The phone's Cast session and the receiver's media status, for the player.
 * The Cast framework owns discovery and the session; the device picker is
 * ours (Material 3, see [CastDialog]) on top of MediaRouter, so the app does
 * not need an AppCompat activity. Only the phone and tablet app uses it; the
 * TV app's own remote control through the relay is separate.
 */
internal object PhoneCast {
    /** Play services answered and Cast can be used on this device. */
    val available = MutableStateFlow(false)
    val connected = MutableStateFlow(false)
    val deviceName = MutableStateFlow<String?>(null)
    val state = MutableStateFlow(CastPlayback.Idle)
    /** Seconds, from the framework's progress updates (twice a second). */
    val position = MutableStateFlow(0.0)
    val duration = MutableStateFlow(0.0)
    /** The receiver's device volume, 0..1. */
    val volume = MutableStateFlow(0f)
    /** The content the receiver has loaded (a cast link). */
    val loadedUrl = MutableStateFlow<String?>(null)
    /** What the receiver plays, from the media's metadata (whoever sent it). */
    val mediaTitle = MutableStateFlow<String?>(null)
    val mediaSubtitle = MutableStateFlow<String?>(null)
    val mediaImage = MutableStateFlow<String?>(null)
    /** Increases each time the loaded media plays to its end. */
    val finished = MutableStateFlow(0)
    val failure = MutableStateFlow<String?>(null)
    /** Increases when a session starts or resumes; a suspension changes nothing. */
    val sessionGeneration = MutableStateFlow(0)
    /** Increases when a session really ends. */
    val endedGeneration = MutableStateFlow(0)

    private var castContext: CastContext? = null
    private var castSession: CastSession? = null
    private var client: RemoteMediaClient? = null
    private var starting = false

    fun init(context: Context) {
        if (castContext != null || starting) return
        starting = true
        val task = runCatching { CastContext.getSharedInstance(context.applicationContext, ContextCompat.getMainExecutor(context)) }.getOrNull()
        if (task == null) {
            starting = false
            return
        }
        task.addOnSuccessListener { cast ->
            castContext = cast
            available.value = true
            cast.sessionManager.addSessionManagerListener(sessions, CastSession::class.java)
            // A session that survived the app being closed resumes here.
            follow(cast.sessionManager.currentCastSession)
        }.addOnFailureListener {
            starting = false
            available.value = false
        }
    }

    val selector get() = castContext?.mergedSelector

    // MARK: Session

    private val sessions = object : SessionManagerListener<CastSession> {
        override fun onSessionStarting(session: CastSession) = Unit
        override fun onSessionStarted(session: CastSession, sessionId: String) {
            follow(session)
            sessionGeneration.value++
        }
        override fun onSessionStartFailed(session: CastSession, error: Int) {
            failure.value = Strings.get(R.string.cast_connect_failed)
            follow(null)
        }
        override fun onSessionEnding(session: CastSession) = Unit
        override fun onSessionEnded(session: CastSession, error: Int) {
            follow(null)
            endedGeneration.value++
        }
        override fun onSessionResuming(session: CastSession, sessionId: String) = Unit
        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) {
            follow(session)
            sessionGeneration.value++
        }
        override fun onSessionResumeFailed(session: CastSession, error: Int) {
            follow(null)
            endedGeneration.value++
        }
        override fun onSessionSuspended(session: CastSession, reason: Int) = Unit
    }

    private val deviceListener = object : Cast.Listener() {
        override fun onVolumeChanged() {
            castSession?.let { volume.value = runCatching { it.volume.toFloat() }.getOrDefault(volume.value) }
        }
    }

    private val mediaCallback = object : RemoteMediaClient.Callback() {
        override fun onStatusUpdated() = statusChanged()
        override fun onMediaError(error: MediaError) {
            failure.value = Strings.get(R.string.cast_receiver_failed)
        }
    }

    private val progress = RemoteMediaClient.ProgressListener { progressMs, durationMs ->
        position.value = progressMs / 1000.0
        if (durationMs > 0) duration.value = durationMs / 1000.0
    }

    private fun follow(session: CastSession?) {
        if (castSession !== session) {
            castSession?.removeCastListener(deviceListener)
            castSession = session
            session?.addCastListener(deviceListener)
        }
        val newClient = session?.remoteMediaClient
        if (newClient !== client) {
            client?.unregisterCallback(mediaCallback)
            client?.removeProgressListener(progress)
            client = newClient
            newClient?.registerCallback(mediaCallback)
            newClient?.addProgressListener(progress, 500)
        }
        connected.value = session?.isConnected == true
        deviceName.value = session?.castDevice?.friendlyName
        volume.value = session?.let { runCatching { it.volume.toFloat() }.getOrNull() } ?: 0f
        if (!connected.value) {
            state.value = CastPlayback.Idle
            loadedUrl.value = null
            mediaTitle.value = null
            mediaSubtitle.value = null
            mediaImage.value = null
        }
        statusChanged()
    }

    private fun prefs(context: Context) = context.getSharedPreferences("relaycast", Context.MODE_PRIVATE)

    /** Remembers what this phone sent (see [castedEpisode]). */
    fun remember(context: Context, episode: CastedEpisode) {
        prefs(context).edit().putString("cast.episode", RelayClient.json.encodeToString(CastedEpisode.serializer(), episode)).apply()
    }

    /** The episode on the receiver, when this phone sent it. */
    fun castedEpisode(context: Context): CastedEpisode? {
        val url = loadedUrl.value ?: return null
        val raw = prefs(context).getString("cast.episode", null) ?: return null
        return runCatching { RelayClient.json.decodeFromString(CastedEpisode.serializer(), raw) }.getOrNull()?.takeIf { it.share.url == url }
    }

    private fun statusChanged() {
        val status = client?.mediaStatus ?: return
        val previous = state.value
        state.value = when (status.playerState) {
            MediaStatus.PLAYER_STATE_PLAYING -> CastPlayback.Playing
            MediaStatus.PLAYER_STATE_PAUSED -> CastPlayback.Paused
            MediaStatus.PLAYER_STATE_BUFFERING -> CastPlayback.Buffering
            MediaStatus.PLAYER_STATE_LOADING -> CastPlayback.Loading
            else -> CastPlayback.Idle
        }
        if (state.value == CastPlayback.Idle && previous != CastPlayback.Idle && status.idleReason == MediaStatus.IDLE_REASON_FINISHED) finished.value++
        if (status.playerState == MediaStatus.PLAYER_STATE_IDLE && status.idleReason == MediaStatus.IDLE_REASON_ERROR) {
            failure.value = Strings.get(R.string.cast_receiver_failed)
        }
        status.mediaInfo?.let { info ->
            loadedUrl.value = info.contentUrl ?: info.contentId
            info.metadata?.let { metadata ->
                mediaTitle.value = metadata.getString(MediaMetadata.KEY_SERIES_TITLE) ?: metadata.getString(MediaMetadata.KEY_TITLE)
                mediaSubtitle.value = metadata.getString(MediaMetadata.KEY_SUBTITLE)
                mediaImage.value = metadata.images.firstOrNull()?.url?.toString()
            }
            if (info.streamDuration > 0) duration.value = info.streamDuration / 1000.0
        }
    }

    // MARK: Commands

    /**
     * Loads one episode: the cast link's HLS stream (MPEG-TS segments) and
     * its subtitle files as side-loaded WebVTT tracks, from [startSeconds].
     */
    fun load(share: CastShare, title: String, subtitle: String, imageUrl: String?, startSeconds: Double, subtitleId: Long?): Boolean {
        val client = client ?: return false
        failure.value = null
        val metadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_TV_SHOW).apply {
            putString(MediaMetadata.KEY_SERIES_TITLE, title)
            putString(MediaMetadata.KEY_TITLE, title)
            putString(MediaMetadata.KEY_SUBTITLE, subtitle)
            imageUrl?.let { addImage(WebImage(Uri.parse(it))) }
        }
        val tracks = share.subtitles.map { track ->
            MediaTrack.Builder(track.id, MediaTrack.TYPE_TEXT)
                .setSubtype(MediaTrack.SUBTYPE_SUBTITLES)
                .setContentId(track.url)
                .setContentType("text/vtt")
                .setName(track.name)
                .setLanguage(track.language)
                .build()
        }
        val info = MediaInfo.Builder(share.url)
            .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
            .setContentType(share.contentType ?: "application/x-mpegURL")
            .setHlsSegmentFormat(HlsSegmentFormat.TS)
            .setHlsVideoSegmentFormat(HlsVideoSegmentFormat.MPEG2_TS)
            .setMetadata(metadata)
            .setMediaTracks(tracks)
            .build()
        val request = MediaLoadRequestData.Builder()
            .setMediaInfo(info)
            .setAutoplay(true)
            .setCurrentTime((startSeconds.coerceAtLeast(0.0) * 1000).toLong())
            .apply { subtitleId?.let { setActiveTrackIds(longArrayOf(it)) } }
            .build()
        client.load(request)
        loadedUrl.value = share.url
        state.value = CastPlayback.Loading
        position.value = startSeconds.coerceAtLeast(0.0)
        return true
    }

    fun play() { client?.play() }

    fun pause() { client?.pause() }

    fun seek(seconds: Double) {
        val target = seconds.coerceAtLeast(0.0)
        client?.seek(MediaSeekOptions.Builder().setPosition((target * 1000).toLong()).build())
        position.value = target
    }

    fun setSubtitle(id: Long?) {
        client?.setActiveMediaTracks(id?.let { longArrayOf(it) } ?: longArrayOf())
    }

    fun setVolume(value: Float) {
        val clamped = value.coerceIn(0f, 1f)
        volume.value = clamped
        castSession?.let { runCatching { it.setVolume(clamped.toDouble()) } }
    }

    /** Ends the session and stops the receiver. */
    fun disconnect() {
        castContext?.sessionManager?.endCurrentSession(true)
    }

    fun clearFailure() {
        failure.value = null
    }
}

/** A Cast device on the network, as shown (a snapshot, so changes recompose). */
internal data class CastRoute(
    val id: String,
    val name: String,
    val description: String?,
    val selected: Boolean,
    val connecting: Boolean,
    val route: MediaRouter.RouteInfo,
)

/**
 * The Cast devices on the network while the caller is shown: MediaRouter
 * with the Cast selector, scanning actively, the callback removed on dispose.
 */
@Composable
internal fun rememberCastRoutes(): List<CastRoute> {
    val context = LocalContext.current
    var routes by remember { mutableStateOf<List<CastRoute>>(emptyList()) }
    DisposableEffect(Unit) {
        val selector = PhoneCast.selector
        val router = MediaRouter.getInstance(context)
        fun refresh() {
            routes = if (selector == null) emptyList() else router.routes
                .filter { !it.isDefault && !it.isBluetooth && it.isEnabled && it.matchesSelector(selector) }
                .map {
                    CastRoute(
                        id = it.id,
                        name = it.name,
                        description = it.description?.takeIf { text -> text.isNotBlank() },
                        selected = it.isSelected,
                        connecting = it.connectionState == MediaRouter.RouteInfo.CONNECTION_STATE_CONNECTING,
                        route = it,
                    )
                }
        }
        val callback = object : MediaRouter.Callback() {
            override fun onRouteAdded(router: MediaRouter, route: MediaRouter.RouteInfo) = refresh()
            override fun onRouteRemoved(router: MediaRouter, route: MediaRouter.RouteInfo) = refresh()
            override fun onRouteChanged(router: MediaRouter, route: MediaRouter.RouteInfo) = refresh()
            override fun onRouteSelected(router: MediaRouter, route: MediaRouter.RouteInfo, reason: Int) = refresh()
            override fun onRouteUnselected(router: MediaRouter, route: MediaRouter.RouteInfo, reason: Int) = refresh()
        }
        if (selector != null) router.addCallback(selector, callback, MediaRouter.CALLBACK_FLAG_PERFORM_ACTIVE_SCAN)
        refresh()
        onDispose { router.removeCallback(callback) }
    }
    return routes
}

/**
 * The Cast devices as rows (name, status, a check on the selected one, a
 * spinner while connecting), or "searching" while there are none.
 * Selecting a route makes the Cast framework start the session.
 */
@Composable
internal fun CastRouteList(routes: List<CastRoute>, onSelected: () -> Unit = {}) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (routes.isEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Text(stringResource(R.string.cast_searching))
            }
            Text(stringResource(R.string.cast_network_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        routes.forEach { item ->
            Row(
                Modifier.fillMaxWidth().clickable(enabled = !item.connecting) {
                    MediaRouter.getInstance(context).selectRoute(item.route)
                    onSelected()
                }.padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(if (item.selected) PhoneIcons.CastConnected else PhoneIcons.Cast, contentDescription = null)
                Column(Modifier.weight(1f)) {
                    Text(item.name, style = MaterialTheme.typography.bodyLarge)
                    item.description?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                when {
                    item.connecting -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    item.selected -> Text("\u2713", color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

/**
 * The connected Cast device with its volume and a way to stop casting, or
 * (opened before connecting) the devices to choose from.
 */
@Composable
internal fun CastDialog(onDismiss: () -> Unit) {
    val connected by PhoneCast.connected.collectAsState()
    val deviceName by PhoneCast.deviceName.collectAsState()
    val volume by PhoneCast.volume.collectAsState()
    val routes = rememberCastRoutes()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (connected) deviceName ?: stringResource(R.string.cast_title) else stringResource(R.string.cast_title)) },
        text = {
            if (connected) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.cast_volume), style = MaterialTheme.typography.labelLarge)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(PhoneIcons.VolumeDown, contentDescription = null)
                        Slider(value = volume, onValueChange = { PhoneCast.setVolume(it) }, modifier = Modifier.weight(1f))
                        Icon(PhoneIcons.VolumeUp, contentDescription = null)
                    }
                }
            } else {
                CastRouteList(routes, onSelected = onDismiss)
            }
        },
        confirmButton = {
            if (connected) {
                TextButton(onClick = {
                    PhoneCast.disconnect()
                    onDismiss()
                }) { Text(stringResource(R.string.cast_stop)) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
    )
}
