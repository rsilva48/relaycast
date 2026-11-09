@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package net.silvara.relaycast

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

// MARK: State shared by the phone and the TV

/**
 * The sources of this device and where the user is inside one: the open
 * source, the folders walked into, the current listing and what plays.
 */
internal class SourcesBrowser(private val context: Context) {
    private val store = MediaSources(context)
    var sources by mutableStateOf(store.load())
        private set
    var open by mutableStateOf<MediaSource?>(null)
        private set
    /** The folders walked into (path and name), innermost last. */
    val folders = mutableStateListOf<Pair<String, String>>()
    var entries by mutableStateOf<List<SourceEntry>>(emptyList())
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
    var playing by mutableStateOf<SourcePlayback?>(null)
    private var job: Job? = null

    val path: String get() = folders.lastOrNull()?.first.orEmpty()
    val title: String? get() = folders.lastOrNull()?.second ?: open?.name

    fun add(source: MediaSource) = save(sources + source)

    fun remove(source: MediaSource) {
        save(sources.filterNot { it.id == source.id })
        SourcePositions.forget(context, source)
        Thread { ListingConnection.drop() }.start()
        if (source.type in listOf("local", "folder")) runCatching {
            context.contentResolver.releasePersistableUriPermission(Uri.parse(source.address), Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun save(values: List<MediaSource>) {
        store.save(values)
        sources = values
    }

    /** Opens a source: a folder to browse, or the file itself. */
    fun select(source: MediaSource, scope: kotlinx.coroutines.CoroutineScope) {
        error = null
        if (!source.browsable) {
            prepare(source, null, scope)
            return
        }
        open = source
        folders.clear()
        reload(scope)
    }

    fun enter(entry: SourceEntry, scope: kotlinx.coroutines.CoroutineScope) {
        val source = open ?: return
        if (entry.directory) {
            folders.add(entry.path to entry.name)
            reload(scope)
        } else {
            prepare(source, entry, scope)
        }
    }

    /** One level up; false when there is nothing to go back to. */
    fun back(scope: kotlinx.coroutines.CoroutineScope): Boolean = when {
        loading && job?.isActive == true -> { job?.cancel(); loading = false; true }
        folders.isNotEmpty() -> { folders.removeAt(folders.lastIndex); reload(scope); true }
        open != null -> { open = null; entries = emptyList(); error = null; true }
        else -> false
    }

    fun reload(scope: kotlinx.coroutines.CoroutineScope) {
        val source = open ?: return
        val path = path
        job?.cancel()
        entries = emptyList()
        error = null
        loading = true
        job = scope.launch {
            try {
                entries = SourceFiles.list(context, source, path)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = SourceFiles.describe(failure)
            } finally {
                loading = false
            }
        }
    }

    private fun prepare(source: MediaSource, entry: SourceEntry?, scope: kotlinx.coroutines.CoroutineScope) {
        job?.cancel()
        loading = true
        job = scope.launch {
            try {
                playing = SourceFiles.playback(context, source, entry)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = SourceFiles.describe(failure)
            } finally {
                loading = false
            }
        }
    }
}

/** What the source row says under its name. */
@Composable
private fun sourceSummary(source: MediaSource): String {
    val type = stringResource(sourceTypeLabel(source.type))
    val host = runCatching { URI(source.address).host }.getOrNull()
    return if (source.type in listOf("local", "folder") || host.isNullOrBlank()) type else "$type · $host"
}

internal fun sourceTypeLabel(type: String): Int = when (type) {
    "local" -> R.string.source_type_local
    "folder" -> R.string.source_type_folder
    "http" -> R.string.source_type_http
    "webdav" -> R.string.source_type_webdav
    "smb" -> R.string.source_type_smb
    else -> R.string.source_type_sftp
}

private fun sourceIcon(type: String): ImageVector = when (type) {
    "local" -> PhoneIcons.File
    "folder" -> PhoneIcons.Folder
    "http" -> PhoneIcons.Link
    "webdav" -> PhoneIcons.Cloud
    else -> PhoneIcons.Dns
}

/** The Relay Server row: optional, for the user's own library. */
internal enum class RelayLibrary { None, Checking, Available, Unavailable }

// MARK: Phone

@Composable
internal fun SourcesScreen(app: PhoneState, onOpenLibrary: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Kept by the app: the player opens full screen above the tabs, and
    // closing it comes back to the same folder.
    val browser = app.sources
    var adding by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<MediaSource?>(null) }

    BackHandler(enabled = browser.open != null || browser.loading) { browser.back(scope) }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(browser.title ?: stringResource(R.string.tab_sources), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = {
                if (browser.open != null) IconButton(onClick = { browser.back(scope) }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back)) }
            },
            actions = {
                if (browser.open == null) IconButton(onClick = { adding = true }) { Icon(PhoneIcons.Add, contentDescription = stringResource(R.string.source_add)) }
            },
        )
        if (browser.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        browser.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 24.dp)) {
            if (browser.open == null) {
                if (browser.sources.isEmpty()) item {
                    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(Modifier.size(80.dp).background(MaterialTheme.colorScheme.primaryContainer, androidx.compose.foundation.shape.CircleShape), contentAlignment = Alignment.Center) {
                            Icon(PhoneIcons.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(40.dp))
                        }
                        Text(stringResource(R.string.sources_empty_title), style = MaterialTheme.typography.titleLarge)
                        Text(stringResource(R.string.sources_intro), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Button(onClick = { adding = true }) { Text(stringResource(R.string.source_add)) }
                    }
                } else {
                    item { SectionTitle(stringResource(R.string.sources_yours)) }
                    items(browser.sources, key = { it.id }) { source ->
                        ListItem(
                            leadingContent = { Icon(sourceIcon(source.type), contentDescription = null) },
                            headlineContent = { Text(source.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingContent = { Text(sourceSummary(source), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            trailingContent = {
                                IconButton(onClick = { removing = source }) { Icon(PhoneIcons.Delete, contentDescription = stringResource(R.string.source_remove)) }
                            },
                            modifier = Modifier.clickable(enabled = !browser.loading) { browser.select(source, scope) },
                        )
                    }
                }
                item {
                    HorizontalDivider(Modifier.padding(top = 8.dp))
                    SectionTitle(stringResource(R.string.source_relay_section))
                    RelayRow(app, onOpenLibrary)
                }
            } else {
                if (!browser.loading && browser.entries.isEmpty() && browser.error == null) item {
                    Text(stringResource(R.string.source_empty), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(24.dp))
                }
                items(browser.entries, key = { it.path }) { entry ->
                    ListItem(
                        leadingContent = { Icon(if (entry.directory) PhoneIcons.Folder else PhoneIcons.Movie, contentDescription = null) },
                        headlineContent = { Text(entry.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        modifier = Modifier.clickable(enabled = !browser.loading) { browser.enter(entry, scope) },
                    )
                }
            }
        }
    }
    if (adding) AddSourceDialog(onDismiss = { adding = false }, onAdd = { browser.add(it); adding = false })
    removing?.let { source ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text(stringResource(R.string.source_remove)) },
            text = { Text(stringResource(R.string.source_remove_confirm, source.name)) },
            confirmButton = { TextButton(onClick = { browser.remove(source); removing = null }) { Text(stringResource(R.string.source_remove)) } },
            dismissButton = { TextButton(onClick = { removing = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp))
}

@Composable
private fun RelayRow(app: PhoneState, onOpenLibrary: () -> Unit) {
    val host = app.relay?.substringAfter("://")?.substringBefore('/')
    when (app.library) {
        RelayLibrary.None -> ListItem(
            leadingContent = { Icon(PhoneIcons.Dns, contentDescription = null) },
            headlineContent = { Text(stringResource(R.string.source_relay_connect)) },
            supportingContent = { Text(stringResource(R.string.source_relay_connect_text)) },
            modifier = Modifier.clickable { app.setupOpen = true },
        )
        RelayLibrary.Checking -> ListItem(
            leadingContent = { Icon(PhoneIcons.Dns, contentDescription = null) },
            headlineContent = { Text(host.orEmpty()) },
            supportingContent = { Text(stringResource(R.string.source_relay_checking)) },
        )
        RelayLibrary.Available -> ListItem(
            leadingContent = { Icon(PhoneIcons.Movie, contentDescription = null) },
            headlineContent = { Text(stringResource(R.string.source_relay_library)) },
            supportingContent = { Text(host.orEmpty()) },
            modifier = Modifier.clickable(onClick = onOpenLibrary),
        )
        RelayLibrary.Unavailable -> ListItem(
            leadingContent = { Icon(PhoneIcons.Dns, contentDescription = null) },
            headlineContent = { Text(host.orEmpty()) },
            supportingContent = { Text(stringResource(R.string.source_relay_no_library)) },
            modifier = Modifier.clickable { app.checkLibrary() },
        )
    }
}

// MARK: Adding a source (phone and TV)

private val sourceTypes = listOf("local", "folder", "http", "webdav", "smb", "sftp")

@Composable
internal fun AddSourceDialog(onDismiss: () -> Unit, onAdd: (MediaSource) -> Unit) {
    val context = LocalContext.current
    var type by remember { mutableStateOf("folder") }
    var name by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var hostKey by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    fun adopt(uri: Uri?, kind: String) {
        uri ?: return
        try {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val label = name.trim().ifEmpty { displayName(context, uri, kind) }
            onAdd(MediaSource(name = label, type = kind, address = uri.toString()))
        } catch (failure: Exception) {
            error = SourceFiles.describe(failure)
        }
    }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { adopt(it, "local") }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { adopt(it, "folder") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.source_add)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    sourceTypes.forEach { value ->
                        FilterChip(selected = type == value, onClick = { type = value; error = null }, label = { Text(stringResource(sourceTypeLabel(value))) })
                    }
                }
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.source_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                when (type) {
                    "local", "folder" -> Text(stringResource(if (type == "local") R.string.source_local_text else R.string.source_folder_text), style = MaterialTheme.typography.bodyMedium)
                    else -> {
                        OutlinedTextField(
                            address, { address = it },
                            label = { Text(stringResource(R.string.source_address)) },
                            placeholder = { Text(when (type) { "smb" -> "smb://server/share/folder"; "sftp" -> "sftp://server/home/videos"; "webdav" -> "https://server/dav/"; else -> "https://server/video.mp4" }) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(username, { username = it }, label = { Text(stringResource(if (type == "sftp") R.string.source_username else R.string.source_username_optional)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(password, { password = it }, label = { Text(stringResource(R.string.source_password)) }, visualTransformation = PasswordVisualTransformation(), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
                        if (type == "sftp") OutlinedTextField(
                            hostKey, { hostKey = it },
                            label = { Text(stringResource(R.string.source_host_key)) },
                            supportingText = { Text(stringResource(R.string.source_host_key_help)) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        if (type == "smb") Text(stringResource(R.string.source_smb_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                error = null
                when (type) {
                    "local" -> pickFile.launch(arrayOf("video/*", "audio/*", "application/x-mpegURL", "application/vnd.apple.mpegurl"))
                    "folder" -> pickFolder.launch(null)
                    else -> {
                        val source = MediaSource(name = name.trim(), type = type, address = address.trim(), username = username.trim(), password = password, hostKey = hostKey.trim())
                        try {
                            SourceFiles.validate(source)
                            onAdd(source)
                        } catch (failure: Exception) {
                            error = SourceFiles.describe(failure)
                        }
                    }
                }
            }) { Text(stringResource(when (type) { "local" -> R.string.source_pick_file; "folder" -> R.string.source_pick_folder; else -> R.string.source_add_button })) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

private fun displayName(context: Context, uri: Uri, kind: String): String {
    val target = if (kind == "folder") DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri)) else uri
    return runCatching {
        context.contentResolver.query(target, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
    }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast(':')?.substringAfterLast('/') ?: "Videos"
}

// MARK: Player (phone and TV)

/**
 * Plays one file of a source with Media3: subtitles found next to it, every
 * audio and subtitle track of the file, and the position remembered.
 */
@Composable
internal fun SourcePlayerScreen(playback: SourcePlayback, tv: Boolean, onClose: () -> Unit) {
    val context = LocalContext.current
    var error by remember { mutableStateOf<String?>(null) }
    val factory = remember(playback) { SourceFiles.dataSourceFactory(context, playback.source) }
    val player = remember(playback) {
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(factory))
            .setAudioAttributes(AudioAttributes.Builder().setUsage(androidx.media3.common.C.USAGE_MEDIA).setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .build()
            .apply {
                addListener(object : Player.Listener {
                    override fun onPlayerError(failure: PlaybackException) {
                        error = failure.cause?.let(SourceFiles::describe)?.takeIf { it.isNotBlank() } ?: failure.localizedMessage
                    }
                })
                val item = MediaItem.Builder()
                    .setUri(playback.uri)
                    .setSubtitleConfigurations(playback.subtitles)
                    .apply { if (playback.uri.lastPathSegment.orEmpty().endsWith(".m3u8", ignoreCase = true)) setMimeType(MimeTypes.APPLICATION_M3U8) }
                    .build()
                setMediaItem(item, SourcePositions.get(context, playback.source, playback.path))
                prepare()
                playWhenReady = true
            }
    }
    fun remember() {
        if (player.duration > 0) SourcePositions.save(context, playback.source, playback.path, player.currentPosition, player.duration)
    }
    LaunchedEffect(player) {
        while (isActive) {
            delay(10_000)
            remember()
        }
    }
    // Full screen on the phone: the system bars come back when it closes.
    val activity = context.findActivity()
    DisposableEffect(player) {
        val window = activity?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        if (!tv) controller?.apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            remember()
            player.release()
            // Closing an SMB or SFTP connection talks to the server: not on the main thread.
            Thread { factory.close() }.start()
            if (!tv) controller?.show(WindowInsetsCompat.Type.systemBars())
        }
    }
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_STOP) {
        remember()
        player.pause()
    }
    BackHandler(onBack = onClose)
    val focus = remember { FocusRequester() }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = {
                PlayerView(it).apply {
                    this.player = player
                    keepScreenOn = true
                    setShowSubtitleButton(true)
                    setShowNextButton(false)
                    setShowPreviousButton(false)
                    isFocusable = true
                    isFocusableInTouchMode = tv
                }
            },
            modifier = Modifier.fillMaxSize().focusRequester(focus),
        )
        if (!tv) IconButton(onClick = onClose, modifier = Modifier.align(Alignment.TopStart).padding(8.dp)) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back), tint = Color.White)
        }
        error?.let {
            Column(Modifier.align(Alignment.Center).padding(32.dp).background(Color(0xCC000000)).padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(playback.title, color = Color.White, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text(it, color = Color.White)
            }
        }
    }
    LaunchedEffect(Unit) { if (tv) runCatching { focus.requestFocus() } }
}

// MARK: TV

/**
 * The TV's Sources tab: the same sources as the phone app, browsed with the
 * remote. Adding one uses the TV keyboard.
 */
@Composable
internal fun TvSourcesTab(browser: SourcesBrowser, relayHost: String?, library: RelayLibrary, onPlay: (SourcePlayback) -> Unit, onOpenLibrary: () -> Unit, onConnectRelay: () -> Unit) {
    val scope = rememberCoroutineScope()
    // After each listing the remote lands on its first row, not on the tabs.
    val first = remember { FocusRequester() }
    LaunchedEffect(browser.open, browser.folders.size, browser.loading, browser.sources.size) {
        if (browser.loading) return@LaunchedEffect
        delay(80)
        runCatching { first.requestFocus() }
    }
    var adding by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<MediaSource?>(null) }
    LaunchedEffect(browser.playing) {
        browser.playing?.let { onPlay(it); browser.playing = null }
    }
    BackHandler(enabled = browser.open != null || browser.loading) { browser.back(scope) }
    val dim = Color.White.copy(alpha = 0.7f)

    Column(Modifier.fillMaxSize()) {
        browser.title?.let { androidx.tv.material3.Text(it, style = androidx.tv.material3.MaterialTheme.typography.titleLarge, color = Color.White) }
        if (browser.loading) androidx.tv.material3.Text(stringResource(R.string.loading), color = dim, modifier = Modifier.padding(vertical = 8.dp))
        browser.error?.let { androidx.tv.material3.Text(it, color = Color(0xFFFF8A80), modifier = Modifier.padding(vertical = 8.dp)) }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
            if (browser.open == null) {
                if (browser.sources.isEmpty()) item { androidx.tv.material3.Text(stringResource(R.string.sources_intro), color = dim, modifier = Modifier.padding(bottom = 8.dp)) }
                itemsIndexed(browser.sources, key = { _, source -> source.id }) { index, source ->
                    androidx.tv.material3.ListItem(
                        selected = false,
                        onClick = { browser.select(source, scope) },
                        onLongClick = { removing = source },
                        leadingContent = { androidx.tv.material3.Icon(sourceIcon(source.type), contentDescription = null) },
                        headlineContent = { androidx.tv.material3.Text(source.name) },
                        supportingContent = { androidx.tv.material3.Text(sourceSummary(source)) },
                        modifier = if (index == 0) Modifier.focusRequester(first) else Modifier,
                    )
                }
                item {
                    androidx.tv.material3.ListItem(
                        selected = false,
                        onClick = { adding = true },
                        modifier = if (browser.sources.isEmpty()) Modifier.focusRequester(first) else Modifier,
                        leadingContent = { androidx.tv.material3.Icon(PhoneIcons.Add, contentDescription = null) },
                        headlineContent = { androidx.tv.material3.Text(stringResource(R.string.source_add)) },
                        supportingContent = { androidx.tv.material3.Text(stringResource(R.string.source_tv_remove_hint)) },
                    )
                }
                item {
                    androidx.tv.material3.Text(stringResource(R.string.source_relay_section), color = dim, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
                    when (library) {
                        RelayLibrary.Available -> androidx.tv.material3.ListItem(
                            selected = false, onClick = onOpenLibrary,
                            leadingContent = { androidx.tv.material3.Icon(PhoneIcons.Movie, contentDescription = null) },
                            headlineContent = { androidx.tv.material3.Text(stringResource(R.string.source_relay_library)) },
                            supportingContent = { androidx.tv.material3.Text(relayHost.orEmpty()) },
                        )
                        RelayLibrary.None -> androidx.tv.material3.ListItem(
                            selected = false, onClick = onConnectRelay,
                            leadingContent = { androidx.tv.material3.Icon(PhoneIcons.Dns, contentDescription = null) },
                            headlineContent = { androidx.tv.material3.Text(stringResource(R.string.source_relay_connect)) },
                            supportingContent = { androidx.tv.material3.Text(stringResource(R.string.source_relay_connect_text)) },
                        )
                        else -> androidx.tv.material3.ListItem(
                            selected = false, onClick = onConnectRelay,
                            leadingContent = { androidx.tv.material3.Icon(PhoneIcons.Dns, contentDescription = null) },
                            headlineContent = { androidx.tv.material3.Text(relayHost.orEmpty()) },
                            supportingContent = { androidx.tv.material3.Text(stringResource(if (library == RelayLibrary.Checking) R.string.source_relay_checking else R.string.source_relay_no_library)) },
                        )
                    }
                }
            } else {
                if (!browser.loading && browser.entries.isEmpty() && browser.error == null) item { androidx.tv.material3.Text(stringResource(R.string.source_empty), color = dim) }
                itemsIndexed(browser.entries, key = { _, entry -> entry.path }) { index, entry ->
                    androidx.tv.material3.ListItem(
                        selected = false,
                        onClick = { browser.enter(entry, scope) },
                        modifier = if (index == 0) Modifier.focusRequester(first) else Modifier,
                        leadingContent = { androidx.tv.material3.Icon(if (entry.directory) PhoneIcons.Folder else PhoneIcons.Movie, contentDescription = null) },
                        headlineContent = { androidx.tv.material3.Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    )
                }
            }
        }
    }
    // The TV's screens use TV Material; its dialogs, the dark Material 3 theme.
    MaterialTheme(colorScheme = androidx.compose.material3.darkColorScheme(primary = Color(0xFF818CF8), secondary = Color(0xFF6366F1))) {
        if (adding) AddSourceDialog(onDismiss = { adding = false }, onAdd = { browser.add(it); adding = false })
        removing?.let { source ->
            AlertDialog(
                onDismissRequest = { removing = null },
                title = { Text(stringResource(R.string.source_remove)) },
                text = { Text(stringResource(R.string.source_remove_confirm, source.name)) },
                confirmButton = { TextButton(onClick = { browser.remove(source); removing = null }) { Text(stringResource(R.string.source_remove)) } },
                dismissButton = { TextButton(onClick = { removing = null }) { Text(stringResource(R.string.cancel)) } },
            )
        }
    }
}
