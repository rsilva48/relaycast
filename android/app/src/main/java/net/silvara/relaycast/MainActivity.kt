@file:OptIn(ExperimentalTvMaterial3Api::class)

package net.silvara.relaycast

import android.app.PictureInPictureParams
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.content.pm.PackageManager
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.drawable.ColorDrawable
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

class MainActivity : ComponentActivity() {
    var playerKeyHandler: ((KeyEvent) -> Boolean)? = null
    /** Phone: the volume buttons go to the connected TV while nothing plays here. */
    var phoneKeyHandler: ((KeyEvent) -> Boolean)? = null
    /** Phone: picture-in-picture for the playing episode when the user leaves the app. */
    var phonePip: (() -> PictureInPictureParams?)? = null

    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        if (playerKeyHandler?.invoke(event) == true || phoneKeyHandler?.invoke(event) == true) true else super.dispatchKeyEvent(event)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Connectivity.start(applicationContext)
        val isTv = packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
        if (isTv) requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        // The launch screen (icon included) gives way to a plain background, seen while rotating.
        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        window.setBackgroundDrawable(ColorDrawable(if (isTv || night) 0xFF0B0B12.toInt() else android.graphics.Color.WHITE))
        if (savedInstanceState == null) {
            forwardRemotePlay(intent)
            forwardLink(intent)
        }
        val state = AppState(applicationContext)
        setContent {
            if (!isTv) {
                PhoneApp()
            } else {
            MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF818CF8), secondary = Color(0xFF6366F1))) {
                // Text inherits white; screens have no Surface to provide it.
                CompositionLocalProvider(LocalContentColor provides Color.White) {
                    Box(Modifier.fillMaxSize().background(Color(0xFF0B0B12))) {
                        App(state)
                    }
                }
            }
            }
        }
    }

    // RemoteService opens (or brings back) this activity for a "play" from a phone.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        forwardRemotePlay(intent)
        forwardLink(intent)
    }

    override fun onResume() {
        super.onResume()
        Remote.foreground.value = true
    }

    // Android 12+ enters picture-in-picture by itself (setAutoEnterEnabled).
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT < 31) phonePip?.invoke()?.let { runCatching { enterPictureInPictureMode(it) } }
    }

    override fun onPause() {
        Remote.foreground.value = false
        super.onPause()
    }

    // Phone: a TV's QR opened from the camera (relaycast://identity or pair).
    private fun forwardLink(intent: Intent?) {
        // A tapped reminder of a new episode opens its series.
        intent?.getStringExtra(ReleaseReminders.EXTRA_SERIES)?.let { text ->
            intent.removeExtra(ReleaseReminders.EXTRA_SERIES)
            runCatching { RelayClient.json.decodeFromString(Anime.serializer(), text) }.getOrNull()?.let { ReleaseReminders.opened.value = it }
        }
        val data = intent?.data ?: return
        if (data.scheme != "relaycast") return
        intent.data = null
        ScannedLink.parse(data.toString())?.let { PhoneLinks.pending.value = it }
    }

    private fun forwardRemotePlay(intent: Intent?) {
        val text = intent?.getStringExtra(RemoteService.EXTRA_PLAY) ?: return
        intent.removeExtra(RemoteService.EXTRA_PLAY)
        runCatching { RelayClient.json.decodeFromString(RemoteCommand.serializer(), text) }.getOrNull()?.let { Remote.playRequests.trySend(it) }
    }
}

sealed interface Route {
    data object Setup : Route
    data object Profiles : Route
    data object Pairing : Route
    data object Home : Route
    data class Details(val anime: Anime) : Route
    data class Watch(val request: PlaybackRequest) : Route
}

@Composable
private fun App(state: AppState) {
    // "Who is watching" every time the app opens, also with one profile (it
    // offers creating another). The last profile keeps the focus, and stays
    // active meanwhile: an episode sent from a phone plays with it right
    // away, and leaving the player comes back here.
    val stack = remember {
        mutableStateListOf<Route>(
            when {
                state.relayUrl == null -> Route.Setup
                state.profiles.isEmpty() -> Route.Pairing
                else -> Route.Profiles
            },
        )
    }
    // One session per profile: its preferences and its watch history.
    val profile = state.activeProfile ?: state.profiles.singleOrNull()
    val context = LocalContext.current
    val session = remember(profile) { profile?.let { Session(it, state.client(it), context) } }

    fun push(route: Route) = stack.add(route)
    fun reset(vararg routes: Route) {
        stack.clear()
        stack.addAll(routes)
    }

    BackHandler(enabled = stack.size > 1) { stack.removeAt(stack.lastIndex) }

    // A TV usually leaves the app in the background instead of closing it:
    // back after a while, it asks who is watching again (not over the player,
    // which keeps its episode).
    var stoppedAt by remember { mutableLongStateOf(0L) }
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_STOP) { stoppedAt = System.currentTimeMillis() }
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_START) {
        val away = stoppedAt > 0 && System.currentTimeMillis() - stoppedAt > 15 * 60 * 1000L
        stoppedAt = 0L
        if (away && state.profiles.isNotEmpty() && stack.last() !is Route.Watch && stack.last() != Route.Profiles) reset(Route.Profiles)
    }

    // Remote control runs in RemoteService (also while the app is closed);
    // it follows the active profile. "play" from a phone opens that episode
    // here, replacing the episode on screen, unless it is already the one
    // showing; the other commands go to the player.
    LaunchedEffect(session) {
        RemoteService.start(context)
        val current = session ?: return@LaunchedEffect
        for (command in Remote.playRequests) {
            val showing = Remote.state.value
            if (showing.status != "idle" && showing.episode == command.episode &&
                showing.watchUrl != null && command.watchUrl != null && catalogPath(showing.watchUrl) == catalogPath(command.watchUrl)
            ) continue
            val request = remotePlayback(current, command) ?: continue
            if (stack.last() is Route.Watch) stack[stack.lastIndex] = Route.Watch(request) else stack.add(Route.Watch(request))
        }
    }

    when (val route = stack.last()) {
        Route.Setup -> SetupScreen(state) { reset(if (state.profiles.isEmpty()) Route.Pairing else Route.Profiles) }
        Route.Profiles -> ProfilesScreen(
            state = state,
            onSelect = {
                state.selectProfile(it)
                reset(Route.Profiles, Route.Home)
            },
            onAdd = { push(Route.Pairing) },
            onChangeRelay = { push(Route.Setup) },
        )
        Route.Pairing -> PairingScreen(
            state = state,
            onPaired = { reset(Route.Profiles, Route.Home) },
            onChangeRelay = { push(Route.Setup) },
        )
        Route.Home -> session?.let { current ->
            HomeScreen(
                session = current,
                onOpen = { push(Route.Details(it)) },
                onSwitchProfile = {
                    state.selectProfile(null)
                    reset(Route.Profiles)
                },
            )
        } ?: LaunchedEffect(Unit) { reset(Route.Profiles) }
        is Route.Details -> session?.let { current ->
            DetailsScreen(session = current, anime = route.anime, onPlay = { push(Route.Watch(it)) })
        }
        is Route.Watch -> session?.let { current ->
            // A new request (from the remote) starts a fresh player.
            key(route.request) {
                PlayerScreen(session = current, initial = route.request, onExit = { stack.removeAt(stack.lastIndex) })
            }
        }
    }
}

/** Resolves a remote "play" command; null (with the reason in the TV state) when it fails. */
private suspend fun remotePlayback(session: Session, command: RemoteCommand): PlaybackRequest? {
    val watchUrl = command.watchUrl ?: return null
    Remote.state.value = DeviceState(status = "loading", watchUrl = watchUrl, episode = command.episode, kind = command.kind)
    return try {
        val series = session.client.series(watchUrl)
        val episode = series.episodes.firstOrNull { it.number == command.episode } ?: series.episodes.firstOrNull() ?: return null
        val entry = session.entry(watchUrl)
        val kind = command.kind ?: entry?.serverKind ?: session.preferences.defaultTrackType
        val anime = Anime(title = series.title, watchUrl = watchUrl, imageUrl = series.imageUrl, titleJp = series.titleJp)
        session.resolve(anime, series, episode, kind, entry?.serverName?.takeIf { entry.serverKind == kind })
    } catch (error: RelayException) {
        Remote.state.value = DeviceState(status = "idle")
        null
    }
}
