@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package net.silvara.relaycast

import android.app.Activity
import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.runtime.key
import androidx.compose.runtime.movableContentOf
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.launch

/** The phone uses its own relay identity; the token is encrypted with Android Keystore. */
internal class PhoneIdentity(context: Context) {
    private val prefs = context.getSharedPreferences("phone_identity", Context.MODE_PRIVATE)
    private val alias = "relaycast.phone.identity.v1"
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }

    /** The tested relay address, even without a token yet. */
    fun relay(): String? = prefs.getString("relay", null)

    fun token(): String? = runCatching {
        val bytes = Base64.decode(prefs.getString("token", null) ?: return null, Base64.NO_WRAP)
        require(bytes.size > 12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        cipher.doFinal(bytes.copyOfRange(12, bytes.size)).toString(Charsets.UTF_8)
    }.getOrNull()

    fun saveRelay(url: String) {
        // Identities belong to one relay.
        if (url != relay()) prefs.edit().remove("token").apply()
        prefs.edit().putString("relay", url).apply()
    }

    fun saveToken(token: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encoded = Base64.encodeToString(cipher.iv + cipher.doFinal(token.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        prefs.edit().putString("token", encoded).apply()
    }
}

/** The connected TV of the remote control (one, until "Disconnect"). */
internal data class ConnectedTv(val id: String, val name: String)

/** An episode to start on the TV from the remote sheet, sent once. */
internal data class TvPlayRequest(val command: RemoteCommand, val title: String, val autoplay: Boolean, val playHere: (() -> Unit)? = null)

/** What the phone screens share: identity, the playing episode, the TV. */
internal class PhoneState(context: Context) {
    val identity = PhoneIdentity(context)
    private val appContext = context.applicationContext
    private val remotePrefs = context.getSharedPreferences("phone_remote", Context.MODE_PRIVATE)

    var relay by mutableStateOf(identity.relay())
        private set
    var token by mutableStateOf(identity.token())
        private set
    var session by mutableStateOf(makeSession())
        private set

    /** The episode open in the player (full screen). */
    var playing by mutableStateOf<PlaybackRequest?>(null)
    /** The TV remote sheet, optionally with an episode to start there. */
    var remoteOpen by mutableStateOf(false)
    /** The devices sheet: Google Cast and the TV app. */
    var devicesOpen by mutableStateOf(false)
    /** Opened for a connected Cast device: its controls first. */
    var devicesCastOnly by mutableStateOf(false)
    var tvPlay by mutableStateOf<TvPlayRequest?>(null)

    private val setupPrefs = context.getSharedPreferences("relaycast", Context.MODE_PRIVATE)
    /**
     * Asistente de configuración inicial: on a phone without a relay until
     * it is finished or left for later, or when opened from Settings. A
     * phone already set up never sees it by itself.
     */
    var setupOpen by mutableStateOf(relay == null && !setupPrefs.getBoolean("setup.done", false))

    fun finishSetup() {
        setupPrefs.edit().putBoolean("setup.done", true).apply()
        setupOpen = false
    }

    var connectedTv by mutableStateOf(
        remotePrefs.getString("tv", null)?.let { ConnectedTv(it, remotePrefs.getString("tvName", null) ?: "TV") },
    )
        private set

    private fun makeSession(): Session? {
        val url = relay ?: return null
        val token = token.orEmpty()
        // History and preferences are kept per relay and identity.
        val bytes = MessageDigest.getInstance("SHA-256").digest("$url\n$token".toByteArray())
        val scope = bytes.joinToString("") { "%02x".format(it) }.take(24)
        return Session(Profile(scope, appContext.getString(R.string.phone_profile_name), token), RelayClient(url, token), appContext)
    }

    fun useRelay(url: String) {
        identity.saveRelay(url)
        relay = url
        token = identity.token()
        session = makeSession()
    }

    fun useToken(value: String) {
        identity.saveToken(value)
        token = value
        session = makeSession()
    }

    fun connect(tv: ConnectedTv?) {
        connectedTv = tv
        remotePrefs.edit().apply {
            if (tv == null) remove("tv").remove("tvName") else putString("tv", tv.id).putString("tvName", tv.name)
        }.apply()
    }

    /** Sends an episode to the connected TV and shows the remote; false without a TV. */
    fun playOnTv(command: RemoteCommand, title: String, playHere: (() -> Unit)?): Boolean {
        if (connectedTv == null) return false
        tvPlay = TvPlayRequest(command, title, autoplay = true, playHere = playHere)
        remoteOpen = true
        return true
    }
}

internal val LocalPhone = staticCompositionLocalOf<PhoneState> { error("No PhoneState") }

internal enum class PhoneTab(@StringRes val label: Int, val icon: ImageVector) {
    Recents(R.string.tab_recent, Icons.Filled.Star),
    Continue(R.string.tab_continue, Icons.Filled.PlayArrow),
    Downloads(R.string.tab_downloads, PhoneIcons.Download),
    Settings(R.string.tab_settings, Icons.Filled.Settings),
    Search(R.string.search, Icons.Filled.Search),
}

/** The tabs of this app. */
internal val phoneTabs: List<PhoneTab> =
    listOf(PhoneTab.Recents, PhoneTab.Continue, PhoneTab.Downloads, PhoneTab.Settings, PhoneTab.Search)

/** The first tab once a relay is set up. */
private val homeTab: PhoneTab = PhoneTab.Recents

@Composable
fun PhoneApp() {
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    // Material You: the wallpaper's colors on Android 12+.
    val colors = if (Build.VERSION.SDK_INT >= 31) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else if (dark) darkColorScheme(primary = Color(0xFFC4BEFF)) else lightColorScheme(primary = Color(0xFF5146C7))
    MaterialTheme(colorScheme = colors) {
        Surface(Modifier.fillMaxSize()) { PhoneRoot() }
    }
}

@Composable
private fun PhoneRoot() {
    val context = LocalContext.current
    val app = remember { PhoneState(context.applicationContext) }
    val session = app.session
    var tab by remember { mutableStateOf(if (app.relay == null) PhoneTab.Settings else homeTab) }
    // One stack of series per tab, like the tabs on iOS.
    val stacks = remember { PhoneTab.entries.associateWith { mutableStateListOf<Anime>() } }
    val scope = rememberCoroutineScope()
    // Google Cast discovery and a session that survived the app being closed.
    LaunchedEffect(Unit) { PhoneCast.init(context) }
    LaunchedEffect(session) {
        session?.refresh()
        ReleaseReminders.refresh(context, session)
        // Downloads finished on another device with this identity.
        PhoneDownloads.cleanUp(context, session)
    }
    // Reminders are rescheduled with the latest history whenever the app returns.
    androidx.lifecycle.compose.LifecycleResumeEffect(session) {
        scope.launch { ReleaseReminders.refresh(context, session) }
        onPauseOrDispose { }
    }
    // A tapped reminder opens its series in Continue.
    val reminded by ReleaseReminders.opened.collectAsState()
    LaunchedEffect(reminded) {
        val anime = reminded ?: return@LaunchedEffect
        ReleaseReminders.opened.value = null
        tab = PhoneTab.Continue
        stacks.getValue(PhoneTab.Continue).apply { clear(); add(anime) }
    }

    // With a TV connected and nothing playing here, the volume buttons
    // change the TV's volume.
    val activity = context as? MainActivity
    DisposableEffect(activity, app.connectedTv, app.playing, session) {
        val tv = app.connectedTv
        val handler: (KeyEvent) -> Boolean = handler@{ event ->
            val steps = when (event.keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP -> 1
                KeyEvent.KEYCODE_VOLUME_DOWN -> -1
                else -> return@handler false
            }
            if (tv == null || app.playing != null || session == null) return@handler false
            if (event.action == KeyEvent.ACTION_DOWN) {
                scope.launch { runCatching { session.client.sendCommand(tv.id, RemoteCommand(type = "volume", steps = steps)) } }
            }
            true
        }
        activity?.phoneKeyHandler = handler
        onDispose { if (activity?.phoneKeyHandler === handler) activity.phoneKeyHandler = null }
    }

    // Compact windows: bottom bar, one pane. From the medium width class
    // (unfolded foldables, tablets, landscape, split screen): a rail, and the
    // list beside the series, split at the fold when there is one. Folding or
    // rotating keeps the tab, the open series and the playing episode (the
    // activity handles size changes itself and this state is not recreated).
    val layout = rememberPhoneLayout()
    // The list of each tab moves between the one- and two-pane layouts with
    // its state (scroll position, loaded pages, search) instead of restarting.
    val roots = remember(session) {
        PhoneTab.entries.associateWith { item ->
            movableContentOf<(Anime) -> Unit> { open ->
                if (session != null) when (item) {
                    PhoneTab.Recents -> PhoneCatalog(session, searching = false, onOpen = open)
                    PhoneTab.Search -> PhoneCatalog(session, searching = true, onOpen = open)
                    else -> PhoneContinue(session, onOpen = open)
                }
            }
        }
    }
    fun select(item: PhoneTab) {
        // Tapping the current tab goes back to its root.
        if (tab == item) stacks.getValue(item).clear()
        tab = item
    }

    CompositionLocalProvider(LocalPhone provides app) {
        val request = app.playing
        if (request != null && session != null) {
            // The window layout already known here: the player starts in the right
            // posture instead of waiting for WindowManager's first report.
            PhonePlayer(session, request, layout, onExit = { app.playing = null })
            return@CompositionLocalProvider
        }
        if (app.setupOpen) {
            PhoneSetupAssistant(app, onFinish = {
                app.finishSetup()
                tab = if (app.relay == null) PhoneTab.Settings else homeTab
            })
        } else {
            Scaffold(
                bottomBar = {
                    if (!layout.rail) {
                        NavigationBar {
                            phoneTabs.forEach { item ->
                                NavigationBarItem(
                                    selected = tab == item,
                                    onClick = { select(item) },
                                    icon = { Icon(item.icon, contentDescription = null) },
                                    label = { Text(stringResource(item.label), maxLines = 1) },
                                )
                            }
                        }
                    }
                },
            ) { padding ->
                // The bars inside (TopAppBar, rail) would add the status bar again.
                Row(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
                    if (layout.rail) {
                        NavigationRail(Modifier.fillMaxHeight()) {
                            phoneTabs.forEach { item ->
                                NavigationRailItem(
                                    selected = tab == item,
                                    onClick = { select(item) },
                                    icon = { Icon(item.icon, contentDescription = null) },
                                    label = { Text(stringResource(item.label), maxLines = 1) },
                                )
                            }
                        }
                    }
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        val stack = stacks.getValue(tab)
                        BackHandler(enabled = stack.isNotEmpty()) { stack.removeAt(stack.lastIndex) }
                        val series: @Composable () -> Unit = {
                            key(stack.lastOrNull()?.watchUrl) {
                                stack.lastOrNull()?.let { anime -> session?.let { PhoneSeries(it, anime, onBack = { stack.removeAt(stack.lastIndex) }) } }
                            }
                        }
                        when {
                            tab == PhoneTab.Settings -> PhoneSettings(app)
                            // Works without a relay or a connection.
                            tab == PhoneTab.Downloads -> PhoneDownloadsScreen(session) { app.playing = it }
                            session == null -> PhoneMessage(
                                stringResource(R.string.setup_title),
                                stringResource(R.string.phone_setup_text),
                                action = stringResource(R.string.setup_open_assistant) to { app.setupOpen = true },
                                icon = PhoneIcons.Settings,
                            )
                            layout.twoPane -> TwoPane(
                                hinge = layout.verticalHinge,
                                // A series chosen in the list replaces the one beside it.
                                first = { roots.getValue(tab).invoke { anime: Anime -> stack.clear(); stack.add(anime) } },
                                second = {
                                    if (stack.isEmpty()) PhoneMessage(stringResource(R.string.choose_series_title), stringResource(R.string.choose_series_text), icon = PhoneIcons.VerticalSplit)
                                    else series()
                                },
                            )
                            stack.isNotEmpty() -> series()
                            else -> roots.getValue(tab).invoke { anime: Anime -> stack.add(anime) }
                        }
                    }
                }
            }
            if (app.remoteOpen && session != null) PhoneRemoteSheet(app, session)
            if (app.devicesOpen && session != null) PhoneDevicesSheet(app, session)
            // Offers the reminders once, as on iOS, once a relay works.
            if (session != null && app.relay != null && !ReleaseReminders.wasPrompted(context)) ReleasePrompt()
        }
        // A TV's QR (camera or the scanner in Settings).
        val link by PhoneLinks.pending.collectAsState()
        when (val current = link) {
            is ScannedLink.Identity -> IdentityImportDialog(app, current) { PhoneLinks.pending.value = null }
            is ScannedLink.Pairing -> PairTvDialog(app, current) { PhoneLinks.pending.value = null }
            is ScannedLink.Provisioning -> ProvisioningDialog(app, current) { PhoneLinks.pending.value = null }
            null -> Unit
        }
    }
}

@Composable
internal fun PhoneMessage(title: String, text: String, action: Pair<String, () -> Unit>? = null, icon: ImageVector? = null) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // An icon in a tinted circle, so empty screens read at a glance.
        icon?.let {
            Box(
                Modifier.padding(bottom = 4.dp).size(80.dp).background(MaterialTheme.colorScheme.primaryContainer, androidx.compose.foundation.shape.CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(it, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(40.dp))
            }
        }
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        action?.let { (label, run) -> Button(onClick = run) { Text(label) } }
    }
}

/** The activity of a composable, for orientation and system bars. */
internal fun Context.findActivity(): Activity? {
    var current = this
    while (current is android.content.ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

/** "Get notified of new episodes?", shown once. */
@Composable
private fun ReleasePrompt() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var visible by remember { mutableStateOf(true) }
    val permission = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { granted ->
        ReleaseReminders.setEnabled(context, granted)
        visible = false
    }
    if (!visible) return
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { ReleaseReminders.markPrompted(context); visible = false },
        title = { Text(stringResource(R.string.release_prompt_title)) },
        text = { Text(stringResource(R.string.release_prompt_text)) },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = {
                if (Build.VERSION.SDK_INT >= 33 && !ReleaseReminders.hasPermission(context)) {
                    permission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    ReleaseReminders.setEnabled(context, true)
                    visible = false
                }
            }) { Text(stringResource(R.string.enable_alerts)) }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = { ReleaseReminders.markPrompted(context); visible = false }) { Text(stringResource(R.string.not_now)) }
        },
    )
}
