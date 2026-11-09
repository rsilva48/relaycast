@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package net.silvara.relaycast

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import java.security.SecureRandom
import java.util.Locale
import kotlinx.coroutines.launch

private val subtitleLanguages = listOf("en", "es", "pt", "fr", "de", "it")

/** The areas of the settings, each on its own page (as in WhatsApp). */
private enum class SettingsPage(val title: Int) {
    Account(R.string.profile_title), Relay(R.string.settings_relay), Identity(R.string.private_identity),
    Catalog(R.string.catalog), Playback(R.string.playback), Notifications(R.string.settings_notifications),
    Storage(R.string.settings_storage), About(R.string.settings_about),
}

/**
 * The profile on top, then one row per area, each with its own page;
 * explanations sit behind (i) buttons. Relay, private identity, synced
 * preferences, and this device.
 */
/** The privacy policy (GitHub Pages, docs/privacy.html). */
internal const val PRIVACY_POLICY_URL = "https://rsilva48.github.io/relaycast/privacy.html"
/** The project's license and the third-party software it uses. */
internal const val LICENSES_URL = "https://github.com/rsilva48/relaycast/blob/main/THIRD_PARTY_NOTICES.md"

@Composable
internal fun PhoneSettings(app: PhoneState) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val session = app.session
    var scheme by remember { mutableStateOf(if (app.relay?.startsWith("http://") == true) "http" else "https") }
    var host by remember { mutableStateOf(app.relay?.substringAfter("://").orEmpty()) }
    var testing by remember { mutableStateOf(false) }
    var relayMessage by remember { mutableStateOf<String?>(null) }
    var dialog by remember { mutableStateOf<IdentityDialog?>(null) }
    var page by rememberSaveable { mutableStateOf<SettingsPage?>(null) }
    // Unfolded (or on a tablet) the list keeps the chosen page beside it.
    val layout = rememberPhoneLayout()
    BackHandler(enabled = page != null) { page = null }
    var scanError by remember { mutableStateOf<String?>(null) }
    // The same codes from an image (a screenshot on this phone).
    val pickCodeImage = rememberCodeImagePicker(onLink = { PhoneLinks.pending.value = it }, onError = { scanError = it })
    var scanMenu by remember { mutableStateOf(false) }
    fun scan() {
        scanError = null
        scope.launch {
            try {
                scanTvCode(context)?.let { PhoneLinks.pending.value = it }
            } catch (failure: RelayException) {
                scanError = failure.message
            }
        }
    }
    /** The connection summary while the test's "needs an identity" message shows. */
    var awaitingIdentity by remember { mutableStateOf<String?>(null) }
    // Once an identity exists (created, imported or from a code), that
    // message is no longer true.
    LaunchedEffect(app.token) {
        val connected = awaitingIdentity ?: return@LaunchedEffect
        if (app.token == null) return@LaunchedEffect
        relayMessage = Strings.get(R.string.relay_ready, connected)
        awaitingIdentity = null
    }

    fun test() {
        val url = RelayClient.canonicalUrl(scheme, host.trim()) ?: run {
            relayMessage = Strings.get(R.string.relay_address_invalid)
            return
        }
        testing = true
        relayMessage = null
        scope.launch {
            try {
                val health = RelayClient(url, "").health()
                if (!health.ok) throw RelayException(Strings.get(R.string.relay_unexpected))
                app.useRelay(url)
                val connected = listOfNotNull(health.version?.let { "relay $it" }, health.provider).joinToString(" · ").let { if (it.isEmpty()) Strings.get(R.string.connected) else Strings.get(R.string.connected_details, it) }
                val needsIdentity = app.token == null && runCatching { RelayClient(url, "").history() }.exceptionOrNull().let { it is RelayException && it.status == 401 }
                relayMessage = Strings.get(if (needsIdentity) R.string.needs_identity else R.string.relay_ready, connected)
                awaitingIdentity = connected.takeIf { needsIdentity }
                app.session?.refresh()
            } catch (error: RelayException) {
                relayMessage = error.message
            } finally {
                testing = false
            }
        }
    }

    fun closePage() { page = null }

    /** The list of areas; with two panes, [selected] is the one beside it. */
    val list: @Composable (SettingsPage?) -> Unit = { selected ->
        scanError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp)) }
        AccountHeader(app, onEdit = { page = SettingsPage.Account })
        HorizontalDivider()
        SettingsRow(Icons.Filled.Build, stringResource(R.string.settings_relay), app.relay?.substringAfter("://")?.substringBefore('/') ?: stringResource(R.string.settings_pending), selected == SettingsPage.Relay) { page = SettingsPage.Relay }
        SettingsRow(Icons.Filled.Lock, stringResource(R.string.private_identity), stringResource(if (app.token != null) R.string.settings_set_up else R.string.settings_pending), selected == SettingsPage.Identity) { page = SettingsPage.Identity }
        HorizontalDivider()
        SettingsRow(Icons.AutoMirrored.Filled.List, stringResource(R.string.catalog), selected = selected == SettingsPage.Catalog) { page = SettingsPage.Catalog }
        SettingsRow(Icons.Filled.PlayArrow, stringResource(R.string.playback), selected = selected == SettingsPage.Playback) { page = SettingsPage.Playback }
        SettingsRow(Icons.Filled.Notifications, stringResource(R.string.settings_notifications), stringResource(if (ReleaseReminders.isEnabled(context)) R.string.settings_on else R.string.settings_off), selected == SettingsPage.Notifications) { page = SettingsPage.Notifications }
        SettingsRow(PhoneIcons.Storage, stringResource(R.string.settings_storage), selected = selected == SettingsPage.Storage) { page = SettingsPage.Storage }
        HorizontalDivider()
        SettingsRow(Icons.Filled.Info, stringResource(R.string.settings_about), remember { context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty() }, selected == SettingsPage.About) { page = SettingsPage.About }
    }
    // As in WhatsApp: the codes, and the pencil to edit the profile.
    val listActions: @Composable () -> Unit = {
        Box {
            IconButton(onClick = { scanMenu = true }) { Icon(PhoneIcons.QrScanner, contentDescription = stringResource(R.string.scan_tv_code)) }
            DropdownMenu(expanded = scanMenu, onDismissRequest = { scanMenu = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.scan_with_camera)) }, onClick = { scanMenu = false; scan() })
                DropdownMenuItem(text = { Text(stringResource(R.string.choose_code_image)) }, onClick = { scanMenu = false; scanError = null; pickCodeImage() })
            }
        }
        if (app.token != null) IconButton(onClick = { dialog = IdentityDialog.ShowCode }) { Icon(PhoneIcons.QrCode, contentDescription = stringResource(R.string.show_profile_code)) }
        if (app.token != null && session != null) IconButton(onClick = { page = SettingsPage.Account }) { Icon(PhoneIcons.Edit, contentDescription = stringResource(R.string.edit_profile)) }
    }

    /** One area's page. */
    val pageContent: @Composable (SettingsPage) -> Unit = { page ->
            if (page == SettingsPage.Account && session != null) AccountEditor(session) { if (!layout.twoPane) closePage() }
            if (page == SettingsPage.Relay) {
            SettingsNote(stringResource(R.string.note_relay))
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                app.relay?.let { Text(stringResource(R.string.active_relay, it), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium) }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    var menu by remember { mutableStateOf(false) }
                    Box {
                        OutlinedButton(onClick = { menu = true }) { Text("$scheme://") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            listOf("https", "http").forEach { option -> DropdownMenuItem(text = { Text("$option://") }, onClick = { scheme = option; menu = false }) }
                        }
                    }
                    OutlinedTextField(
                        value = host,
                        onValueChange = { value ->
                            // A pasted scheme moves to the picker.
                            when {
                                value.startsWith("https://") -> { scheme = "https"; host = value.removePrefix("https://") }
                                value.startsWith("http://") -> { scheme = "http"; host = value.removePrefix("http://") }
                                else -> host = value
                            }
                        },
                        placeholder = { Text(stringResource(R.string.relay_placeholder)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = { test() }),
                        modifier = Modifier.weight(1f),
                    )
                }
                Button(onClick = ::test, enabled = !testing && host.isNotBlank()) { Text(stringResource(if (testing) R.string.testing_connection else R.string.test_and_save)) }
                relayMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                // The guided setup again (relay, account, profile, alerts).
                TextButton(onClick = { app.setupOpen = true }) { Text(stringResource(R.string.setup_assistant_title)) }
            }
            }

            if (page == SettingsPage.Identity) {
            SettingsNote(stringResource(R.string.note_account))
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    app.relay == null -> Text(stringResource(R.string.test_relay_first), color = MaterialTheme.colorScheme.error)
                    app.token != null -> {
                        Text(stringResource(R.string.identity_configured), color = MaterialTheme.colorScheme.primary)
                        // The same QR a TV shows, for another phone.
                        OutlinedButton(onClick = { dialog = IdentityDialog.ShowCode }) { Text(stringResource(R.string.show_profile_code)) }
                        OutlinedButton(onClick = { dialog = IdentityDialog.Backup }) { Text(stringResource(R.string.backup_token_ellipsis)) }
                        OutlinedButton(onClick = { dialog = IdentityDialog.Change }) { Text(stringResource(R.string.change_identity_ellipsis)) }
                    }
                    else -> {
                        OutlinedButton(onClick = { dialog = IdentityDialog.Create }) { Text(stringResource(R.string.create_identity_ellipsis)) }
                        OutlinedButton(onClick = { dialog = IdentityDialog.Import }) { Text(stringResource(R.string.import_token_ellipsis)) }
                    }
                }
                // A profile from a TV or another phone, a TV pairing code, or a relay's setup code.
                OutlinedButton(onClick = ::scan) { Text(stringResource(R.string.scan_tv_code)) }
                OutlinedButton(onClick = { scanError = null; pickCodeImage() }) { Text(stringResource(R.string.choose_code_image)) }
                scanError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
            }

            val preferences = session?.preferences
            if (page == SettingsPage.Catalog) {
            ChoiceRow(stringResource(R.string.title_language), listOf("en" to stringResource(R.string.english), "jp" to stringResource(R.string.japanese)), preferences?.titleLanguage ?: "en", enabled = session != null, info = stringResource(R.string.info_title_language)) { value ->
                scope.launch { session?.updatePreferences { it.copy(titleLanguage = value) } }
            }
            SwitchRow(stringResource(R.string.hide_adult), preferences?.hideAdult ?: false, enabled = session != null, info = stringResource(R.string.info_hide_adult)) { value ->
                scope.launch { session?.updatePreferences { it.copy(hideAdult = value) } }
            }
            }

            if (page == SettingsPage.Playback) {
            SettingsNote(stringResource(R.string.note_sync))
            ChoiceRow(stringResource(R.string.default_servers), listOf("sub" to "Sub", "dub" to "Dub"), preferences?.defaultTrackType ?: "sub", enabled = session != null, info = stringResource(R.string.info_default_servers)) { value ->
                scope.launch { session?.updatePreferences { it.copy(defaultTrackType = value) } }
            }
            val languages = listOf("" to stringResource(R.string.subtitles_auto)) +
                subtitleLanguages.map { it to Locale.forLanguageTag(it).getDisplayLanguage(Strings.locale).replaceFirstChar(Char::titlecase) } +
                listOf("off" to stringResource(R.string.subtitles_off))
            ChoiceRow(stringResource(R.string.subtitles_in_sub), languages, preferences?.subtitleLanguage ?: "", enabled = session != null) { value ->
                scope.launch { session?.updatePreferences { it.copy(subtitleLanguage = value) } }
            }
            // A setting of this phone (iOS translates subtitles by itself).
            var translateSubtitles by remember { mutableStateOf(SubtitleTranslationSetting.isEnabled(context)) }
            SwitchRow(stringResource(R.string.translate_subtitles), translateSubtitles, enabled = true, info = stringResource(R.string.info_translate_subtitles)) { value ->
                SubtitleTranslationSetting.setEnabled(context, value)
                translateSubtitles = value
            }
            SwitchRow(stringResource(R.string.auto_next), preferences?.autoNext ?: true, enabled = session != null) { value ->
                scope.launch { session?.updatePreferences { it.copy(autoNext = value) } }
            }
            SwitchRow(stringResource(R.string.auto_skip_intro), preferences?.autoSkipIntro ?: false, enabled = session != null) { value ->
                scope.launch { session?.updatePreferences { it.copy(autoSkipIntro = value) } }
            }
            SwitchRow(stringResource(R.string.auto_skip_outro), preferences?.autoSkipOutro ?: false, enabled = session != null) { value ->
                scope.launch { session?.updatePreferences { it.copy(autoSkipOutro = value) } }
            }
            }

            if (page == SettingsPage.Notifications) {
            var reminders by remember { mutableStateOf(ReleaseReminders.isEnabled(context) && ReleaseReminders.hasPermission(context)) }
            var reminderError by remember { mutableStateOf<String?>(null) }
            val permission = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { granted ->
                ReleaseReminders.setEnabled(context, granted)
                reminders = granted
                reminderError = if (granted) null else resources.getString(R.string.notifications_denied)
                scope.launch { ReleaseReminders.refresh(context, app.session) }
            }
            SwitchRow(stringResource(R.string.new_episode_alerts), reminders, enabled = session != null, info = stringResource(R.string.info_alerts)) { value ->
                if (value && !ReleaseReminders.hasPermission(context) && android.os.Build.VERSION.SDK_INT >= 33) {
                    permission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    ReleaseReminders.setEnabled(context, value)
                    reminders = value
                    scope.launch { ReleaseReminders.refresh(context, app.session) }
                }
            }
            reminderError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp)) }
            }

            if (page == SettingsPage.Storage) {
            var autoDelete by remember { mutableStateOf(PhoneDownloads.autoDelete(context)) }
            SwitchRow(stringResource(R.string.delete_watched_downloads), autoDelete, enabled = true, info = stringResource(R.string.info_delete_watched)) { value ->
                PhoneDownloads.setAutoDelete(context, value)
                autoDelete = value
                if (value) PhoneDownloads.cleanUp(context, session)
            }
            val downloads by rememberDownloads()
            var confirmRemoveAll by remember { mutableStateOf(false) }
            ListItem(
                headlineContent = { Text(stringResource(R.string.storage_used)) },
                trailingContent = { Text(android.text.format.Formatter.formatShortFileSize(context, downloads.sumOf { it.bytes })) },
            )
            OutlinedButton(
                onClick = { confirmRemoveAll = true },
                enabled = downloads.isNotEmpty(),
                modifier = Modifier.padding(horizontal = 16.dp),
            ) { Text(stringResource(R.string.delete_all_downloads), color = if (downloads.isNotEmpty()) MaterialTheme.colorScheme.error else androidx.compose.ui.graphics.Color.Unspecified) }
            if (confirmRemoveAll) AlertDialog(
                onDismissRequest = { confirmRemoveAll = false },
                title = { Text(pluralStringResource(R.plurals.delete_downloads_title, downloads.size, downloads.size)) },
                text = { Text(stringResource(R.string.delete_downloads_text)) },
                confirmButton = {
                    TextButton(onClick = { PhoneDownloads.removeAll(context); confirmRemoveAll = false }) {
                        Text(stringResource(R.string.delete_all), color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = { TextButton(onClick = { confirmRemoveAll = false }) { Text(stringResource(R.string.cancel)) } },
            )
            }

            if (page == SettingsPage.About) {
            ListItem(
                headlineContent = { Text(stringResource(R.string.installed_version)) },
                supportingContent = { Text(remember { context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty() }) },
            )
            // The privacy policy and the licenses (docs/privacy.html on
            // GitHub Pages, THIRD_PARTY_NOTICES.md); the stores ask for the
            // policy inside the app too.
            val uriHandler = LocalUriHandler.current
            ListItem(
                leadingContent = { Icon(Icons.Filled.Lock, contentDescription = null) },
                headlineContent = { Text(stringResource(R.string.privacy_policy)) },
                modifier = Modifier.clickable { uriHandler.openUri(PRIVACY_POLICY_URL) },
            )
            ListItem(
                leadingContent = { Icon(Icons.AutoMirrored.Filled.List, contentDescription = null) },
                headlineContent = { Text(stringResource(R.string.open_source_licenses)) },
                modifier = Modifier.clickable { uriHandler.openUri(LICENSES_URL) },
            )
            SettingsNote(stringResource(R.string.about_no_data))
            }
    }

    if (layout.twoPane) {
        val shown = page
        TwoPane(
            hinge = layout.verticalHinge,
            first = {
                Column(Modifier.fillMaxSize()) {
                    TopAppBar(title = { Text(stringResource(R.string.tab_settings)) }, actions = { listActions() })
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) { list(shown) }
                }
            },
            second = {
                // Nothing chosen yet, as in Recent.
                if (shown == null) PhoneMessage(stringResource(R.string.choose_setting_title), stringResource(R.string.choose_series_text), icon = PhoneIcons.Settings)
                else Column(Modifier.fillMaxSize()) {
                    TopAppBar(title = { Text(stringResource(shown.title)) })
                    key(shown) {
                        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) { pageContent(shown) }
                    }
                }
            },
        )
    } else {
        Column(Modifier.fillMaxSize()) {
            TopAppBar(
                title = { Text(stringResource(page?.title ?: R.string.tab_settings)) },
                navigationIcon = {
                    if (page != null) IconButton(onClick = { page = null }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back)) }
                },
                actions = { if (page == null) listActions() },
            )
            key(page) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
                    page?.let { pageContent(it) } ?: list(null)
                }
            }
        }
    }

    when (dialog) {
        IdentityDialog.Backup -> TokenBackupDialog(app.token.orEmpty()) { dialog = null }
        IdentityDialog.ShowCode -> ProfileCodeDialog(app.relay.orEmpty(), app.token.orEmpty(), app.session?.account?.displayName) { dialog = null }
        IdentityDialog.Change -> ChangeIdentityDialog(onDismiss = { dialog = null }) { dialog = it }
        IdentityDialog.Import -> ImportTokenDialog(replaces = app.token != null, onDismiss = { dialog = null }) { token ->
            app.useToken(token)
            dialog = null
            scope.launch { app.session?.refresh() }
        }
        IdentityDialog.Create -> CreateIdentityDialog(app.relay.orEmpty(), onDismiss = { dialog = null }) { token ->
            app.useToken(token)
            dialog = IdentityDialog.Backup
            scope.launch { app.session?.refresh() }
        }
        null -> Unit
    }
}

private enum class IdentityDialog { Backup, ShowCode, Change, Import, Create }

/** A section title with its explanation behind an (i) button. */
@Composable
private fun SectionTitle(text: String, info: String? = null) {
    var showing by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
        if (info != null) IconButton(onClick = { showing = true }) {
            Icon(Icons.Outlined.Info, contentDescription = stringResource(R.string.settings_more_info), tint = MaterialTheme.colorScheme.primary)
        }
    }
    if (showing && info != null) AlertDialog(
        onDismissRequest = { showing = false },
        title = { Text(text) },
        text = { Text(info) },
        confirmButton = { TextButton(onClick = { showing = false }) { Text(stringResource(R.string.close)) } },
    )
}

/** One area of the settings: icon, title and its current value. */
@Composable
private fun SettingsRow(icon: ImageVector, title: String, detail: String? = null, selected: Boolean = false, onClick: () -> Unit) {
    ListItem(
        leadingContent = { Icon(icon, contentDescription = null) },
        headlineContent = { Text(title) },
        trailingContent = detail?.let { { Text(it, maxLines = 1, color = MaterialTheme.colorScheme.onSurfaceVariant) } },
        // The page shown beside the list (two panes).
        colors = if (selected) androidx.compose.material3.ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.secondaryContainer) else androidx.compose.material3.ListItemDefaults.colors(),
        modifier = Modifier.clickable(onClick = onClick),
    )
}

/** The profile's picture, name and email; tapping it edits them. */
@Composable
private fun AccountHeader(app: PhoneState, onEdit: () -> Unit) {
    val session = app.session
    val account = session?.account
    Column(
        Modifier.fillMaxWidth().clickable(enabled = session != null && app.token != null, onClick = onEdit).padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Avatar(account, null, 96.dp)
        Text(account?.displayName ?: stringResource(R.string.settings_your_profile), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        val subtitle = when {
            app.token == null -> stringResource(R.string.settings_profile_needs_identity)
            account?.email != null -> account.email
            account?.name == null -> stringResource(R.string.settings_profile_hint)
            else -> null
        }
        subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

/** Name, recovery email and picture of the identity, kept on the relay. */
@Composable
internal fun AccountEditor(session: Session, onDone: () -> Unit) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val account = session.account
    var name by remember { mutableStateOf(account?.name ?: account?.label.orEmpty()) }
    var email by remember { mutableStateOf(account?.email.orEmpty()) }
    var color by remember { mutableStateOf(account?.avatar?.color ?: AVATAR_COLORS[0]) }
    var emoji by remember { mutableStateOf(account?.avatar?.emoji) }
    var photo by remember { mutableStateOf(account?.avatar?.photo) }
    var photoChanged by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        val reduced = avatarPhoto(context, uri)
        if (reduced == null) {
            error = resources.getString(R.string.profile_image_failed)
        } else {
            photo = reduced
            photoChanged = true
            error = null
        }
    }
    val preview = AccountProfile(name.ifBlank { null }, null, AccountAvatar(color, emoji, photo), account?.label)
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Avatar(preview, null, 120.dp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) { Text(stringResource(R.string.profile_choose_photo)) }
                if (photo != null) TextButton(onClick = { photo = null; photoChanged = true }) { Text(stringResource(R.string.profile_remove_photo), color = MaterialTheme.colorScheme.error) }
            }
        }
        SectionTitle(stringResource(R.string.profile_avatar))
        Row(Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            AVATAR_COLORS.forEach { option ->
                Box(
                    Modifier.size(40.dp).background(avatarColor(option), CircleShape).clickable { color = option },
                    contentAlignment = Alignment.Center,
                ) { if (option == color) Text("✓", color = androidx.compose.ui.graphics.Color.White) }
            }
        }
        Row(Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val initial = (name.ifBlank { null } ?: account?.label)?.firstOrNull()?.uppercase() ?: "A"
            (listOf<String?>(null) + AVATAR_EMOJIS).forEach { option ->
                Box(
                    Modifier.size(44.dp)
                        .background(if (emoji == option) MaterialTheme.colorScheme.primaryContainer else androidx.compose.ui.graphics.Color.Transparent, CircleShape)
                        .clickable { emoji = option },
                    contentAlignment = Alignment.Center,
                ) { Text(option ?: initial, style = MaterialTheme.typography.titleLarge) }
            }
        }
        SectionTitle(stringResource(R.string.profile_title))
        OutlinedTextField(value = name, onValueChange = { name = it.take(40) }, label = { Text(stringResource(R.string.profile_field_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(
            value = email,
            onValueChange = { email = it.take(254) },
            label = { Text(stringResource(R.string.profile_email)) },
            trailingIcon = { InfoIcon(stringResource(R.string.info_email)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            modifier = Modifier.fillMaxWidth(),
        )
        SettingsNote(stringResource(R.string.note_profile))
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        Button(enabled = !saving, onClick = {
            saving = true
            error = null
            scope.launch {
                try {
                    session.saveAccount(name.trim(), email.trim(), color, emoji.orEmpty(), if (photoChanged) photo.orEmpty() else null)
                    onDone()
                } catch (failure: RelayException) {
                    error = failure.message
                } finally {
                    saving = false
                }
            }
        }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(if (saving) R.string.profile_saving else R.string.save)) }
    }
}

/** One or two sentences under a page's title (instead of long footers). */
@Composable
private fun SettingsNote(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
}

/** A small (i) next to an option; its explanation in a dialog. */
@Composable
private fun InfoIcon(text: String) {
    var showing by remember { mutableStateOf(false) }
    IconButton(onClick = { showing = true }, modifier = Modifier.size(32.dp)) {
        Icon(Icons.Outlined.Info, contentDescription = stringResource(R.string.settings_more_info), modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (showing) AlertDialog(
        onDismissRequest = { showing = false },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = { showing = false }) { Text(stringResource(R.string.close)) } },
    )
}

@Composable
private fun OptionTitle(label: String, info: String?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label)
        if (info != null) InfoIcon(info)
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, enabled: Boolean, info: String? = null, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { OptionTitle(label, info) },
        trailingContent = { Switch(checked = checked, onCheckedChange = onChange, enabled = enabled) },
        modifier = Modifier.clickable(enabled = enabled) { onChange(!checked) },
    )
}

@Composable
private fun ChoiceRow(label: String, choices: List<Pair<String, String>>, selected: String, enabled: Boolean, info: String? = null, onChange: (String) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Box {
        ListItem(
            headlineContent = { OptionTitle(label, info) },
            supportingContent = { Text(choices.firstOrNull { it.first == selected }?.second ?: selected) },
            modifier = Modifier.clickable(enabled = enabled) { menu = true },
        )
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            choices.forEach { (value, text) -> DropdownMenuItem(text = { Text(text) }, onClick = { menu = false; onChange(value) }) }
        }
    }
}

@Composable
internal fun TokenBackupDialog(token: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val resources = LocalResources.current
    var understood by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.backup_token)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.backup_warning))
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { understood = !understood }) {
                    Checkbox(checked = understood, onCheckedChange = { understood = it })
                    Text(stringResource(R.string.understand_risk))
                }
                if (understood) Text(token, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(enabled = understood, onClick = {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText(resources.getString(R.string.clip_label), token))
            }) { Text(stringResource(R.string.copy)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
    )
}

@Composable
private fun ChangeIdentityDialog(onDismiss: () -> Unit, onChoose: (IdentityDialog) -> Unit) {
    var saved by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.change_identity)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.change_identity_text))
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { saved = !saved }) {
                    Checkbox(checked = saved, onCheckedChange = { saved = it })
                    Text(stringResource(R.string.saved_current_token))
                }
                TextButton(onClick = { onChoose(IdentityDialog.Backup) }) { Text(stringResource(R.string.backup_current_token)) }
            }
        },
        confirmButton = {
            Column {
                TextButton(enabled = saved, onClick = { onChoose(IdentityDialog.Create) }) { Text(stringResource(R.string.create_new_identity)) }
                TextButton(enabled = saved, onClick = { onChoose(IdentityDialog.Import) }) { Text(stringResource(R.string.use_existing_token)) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
internal fun ImportTokenDialog(replaces: Boolean, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var token by remember { mutableStateOf("") }
    var confirmed by remember { mutableStateOf(!replaces) }
    var error by remember { mutableStateOf<String?>(null) }
    val invalidToken = stringResource(R.string.token_invalid)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.import_token)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.import_text))
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it.trim() },
                    label = { Text(stringResource(R.string.token_64)) },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                )
                if (replaces) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { confirmed = !confirmed }) {
                    Checkbox(checked = confirmed, onCheckedChange = { confirmed = it })
                    Text(stringResource(R.string.understand_replace))
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(enabled = confirmed && token.isNotEmpty(), onClick = {
                if (!Regex("^[A-Fa-f0-9]{64}$").matches(token)) error = invalidToken else onSave(token.lowercase())
            }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** The first identity of a phone: the relay's administrator token registers a new random token (the admin token is not kept). */
@Composable
internal fun CreateIdentityDialog(relay: String, initialAdminToken: String = "", onDismiss: () -> Unit, onCreated: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    // Filled in from a relay's setup code.
    var adminToken by remember { mutableStateOf(initialAdminToken) }
    var label by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.new_identity)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.new_identity_text))
                OutlinedTextField(value = adminToken, onValueChange = { adminToken = it.trim() }, label = { Text(stringResource(R.string.admin_token_label)) }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
                OutlinedTextField(value = label, onValueChange = { label = it.take(80) }, label = { Text(stringResource(R.string.optional_label)) }, singleLine = true)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && adminToken.isNotEmpty(), onClick = {
                busy = true
                error = null
                scope.launch {
                    try {
                        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
                        val token = bytes.joinToString("") { "%02x".format(it) }
                        RelayClient(relay, "").registerUser(adminToken, token, label.ifBlank { "Android" })
                        onCreated(token)
                    } catch (failure: RelayException) {
                        error = failure.message
                    } finally {
                        busy = false
                    }
                }
            }) { Text(stringResource(if (busy) R.string.registering else R.string.register_and_use)) }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
