package net.silvara.relaycast

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** The steps of the setup assistant, in order. */
private enum class SetupStep { Welcome, Relay, Account, Profile, Notifications, Done }

/**
 * Asistente de configuración inicial: the relay (its setup code or its
 * address), the account (new, imported or from another device's profile
 * code), the profile's name and picture, and new-episode alerts. It reuses
 * the dialogs of Settings and of scanned codes ([PhoneLinks], shown by the
 * root), and shows only on a phone without a relay, or when opened from
 * Settings; every step can be left for later.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PhoneSetupAssistant(app: PhoneState, onFinish: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var step by rememberSaveable { mutableStateOf(SetupStep.Welcome) }
    val pendingLink by PhoneLinks.pending.collectAsState()
    var scanError by remember { mutableStateOf<String?>(null) }

    fun back() {
        step = when (step) {
            SetupStep.Welcome -> SetupStep.Welcome
            SetupStep.Relay -> SetupStep.Welcome
            SetupStep.Account -> SetupStep.Relay
            SetupStep.Profile -> if (app.token == null) SetupStep.Account else SetupStep.Relay
            // A named account skips Profile (it would move on again by itself).
            SetupStep.Notifications -> when {
                app.token == null || app.session == null -> SetupStep.Account
                !app.session?.account?.name.isNullOrBlank() -> SetupStep.Relay
                else -> SetupStep.Profile
            }
            SetupStep.Done -> SetupStep.Notifications
        }
    }
    BackHandler(enabled = step != SetupStep.Welcome) { back() }

    /** After the relay: the account if it has none yet, else the profile. */
    fun afterRelay() { step = if (app.token == null) SetupStep.Account else SetupStep.Profile }

    // A code read here (camera or image) opens the same dialog as from the
    // camera app (the root shows it); the step advances once the relay or
    // the account changes.
    fun use(link: ScannedLink?) {
        when (link) {
            null -> Unit
            is ScannedLink.Pairing -> scanError = Strings.get(R.string.setup_pairing_code)
            else -> PhoneLinks.pending.value = link
        }
    }
    fun scan() {
        scanError = null
        scope.launch {
            try {
                use(scanTvCode(context))
            } catch (failure: RelayException) {
                scanError = failure.message
            }
        }
    }
    val pickCodeImage = rememberCodeImagePicker(onLink = ::use, onError = { scanError = it })
    fun pickImage() {
        scanError = null
        pickCodeImage()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.setup_assistant_title)) },
                navigationIcon = {
                    if (step != SetupStep.Welcome && step != SetupStep.Done) IconButton(onClick = ::back) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            LinearProgressIndicator(
                progress = { step.ordinal / (SetupStep.entries.size - 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
            // Centered and width-limited on tablets and unfolded foldables.
            Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), contentAlignment = Alignment.TopCenter) {
                Column(
                    Modifier.widthIn(max = 560.dp).fillMaxWidth().padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    when (step) {
                        SetupStep.Welcome -> WelcomeStep(onStart = { step = SetupStep.Relay }, onLater = onFinish)
                        SetupStep.Relay -> RelayStep(app, pendingLink == null, scanError, onScan = ::scan, onPickImage = ::pickImage, onDone = ::afterRelay, onLater = onFinish)
                        SetupStep.Account -> AccountStep(
                            app,
                            pendingLink == null,
                            scanError,
                            onScan = ::scan,
                            onPickImage = ::pickImage,
                            onDone = { step = SetupStep.Profile },
                            onSkip = { step = SetupStep.Notifications },
                        )
                        SetupStep.Profile -> {
                            val session = app.session
                            if (session == null || app.token == null) {
                                LaunchedEffect(Unit) { step = SetupStep.Notifications }
                            } else {
                                // The account's profile from the relay first. One that
                                // already has a name (set on another device) keeps it and
                                // its picture: the step is skipped, so nothing can blank
                                // it; otherwise the editor opens with what the account has.
                                var loadingProfile by remember(session) { mutableStateOf(true) }
                                LaunchedEffect(session) {
                                    session.refreshAccount()
                                    if (!session.account?.name.isNullOrBlank()) step = SetupStep.Notifications
                                    else loadingProfile = false
                                }
                                if (loadingProfile) {
                                    Row(Modifier.fillMaxWidth().padding(vertical = 32.dp), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
                                        androidx.compose.material3.CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
                                        Text(stringResource(R.string.setup_loading_profile))
                                    }
                                } else {
                                    StepHeader(PhoneIcons.Edit, stringResource(R.string.setup_profile_title), stringResource(R.string.setup_profile_text))
                                    AccountEditor(session) { step = SetupStep.Notifications }
                                    TextButton(onClick = { step = SetupStep.Notifications }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                                        Text(stringResource(R.string.setup_skip))
                                    }
                                }
                            }
                        }
                        SetupStep.Notifications -> NotificationsStep(onDone = { step = SetupStep.Done })
                        SetupStep.Done -> {
                            StepHeader(Icons.Filled.CheckCircle, stringResource(R.string.setup_done_title), stringResource(if (app.relay != null) R.string.setup_done_text else R.string.setup_done_later))
                            Button(onClick = onFinish, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.setup_start_watching)) }
                        }
                    }
                }
            }
        }
    }
}

/** An icon in a tinted circle, a title and a line, centered. */
@Composable
private fun StepHeader(icon: ImageVector, title: String, text: String) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(80.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(40.dp))
        }
        Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

@Composable
private fun WelcomeStep(onStart: () -> Unit, onLater: () -> Unit) {
    Spacer(Modifier.height(24.dp))
    StepHeader(PhoneIcons.Tv, stringResource(R.string.setup_welcome_title), stringResource(R.string.setup_welcome_text))
    Spacer(Modifier.height(8.dp))
    Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.setup_begin)) }
    TextButton(onClick = onLater, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.setup_later)) }
}

/**
 * The relay: its setup code (address, and the administrator token to
 * create the account) or the address typed and tested, as in Settings.
 */
@Composable
private fun RelayStep(app: PhoneState, noDialog: Boolean, scanError: String?, onScan: () -> Unit, onPickImage: () -> Unit, onDone: () -> Unit, onLater: () -> Unit) {
    val scope = rememberCoroutineScope()
    val initialRelay = remember { app.relay }
    val initialToken = remember { app.token }
    // A code's dialog changed the relay or the account: next step.
    LaunchedEffect(noDialog, app.relay, app.token) {
        if (noDialog && app.relay != null && (app.relay != initialRelay || app.token != initialToken)) onDone()
    }
    var scheme by remember { mutableStateOf(if (app.relay?.startsWith("http://") == true) "http" else "https") }
    var host by remember { mutableStateOf(app.relay?.substringAfter("://").orEmpty()) }
    var testing by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    fun test() {
        val url = RelayClient.canonicalUrl(scheme, host.trim()) ?: run {
            message = Strings.get(R.string.relay_address_invalid)
            return
        }
        testing = true
        message = null
        scope.launch {
            try {
                if (!RelayClient(url, "").health().ok) throw RelayException(Strings.get(R.string.relay_unexpected))
                app.useRelay(url)
                onDone()
            } catch (error: RelayException) {
                message = error.message
            } finally {
                testing = false
            }
        }
    }

    StepHeader(PhoneIcons.QrScanner, stringResource(R.string.setup_step_relay_title), stringResource(R.string.setup_relay_text))
    FilledTonalButton(onClick = onScan, modifier = Modifier.fillMaxWidth()) {
        Icon(PhoneIcons.QrScanner, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.setup_scan_setup_code))
    }
    // The code may be only on this phone (a screenshot, a message).
    OutlinedButton(onClick = onPickImage, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.choose_code_image)) }
    scanError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        HorizontalDivider(Modifier.weight(1f))
        Text(stringResource(R.string.setup_or_address), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 12.dp))
        HorizontalDivider(Modifier.weight(1f))
    }
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
    Text(stringResource(R.string.note_relay), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Button(onClick = ::test, enabled = !testing && host.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(if (testing) R.string.testing_connection else R.string.setup_test_continue))
    }
    message?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    TextButton(onClick = onLater, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.setup_later)) }
}

/**
 * The account: first an existing one (another device's profile code, by
 * camera or from an image, or its token), then a new one (administrator
 * token).
 */
@Composable
private fun AccountStep(app: PhoneState, noDialog: Boolean, scanError: String?, onScan: () -> Unit, onPickImage: () -> Unit, onDone: () -> Unit, onSkip: () -> Unit) {
    var creating by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var backup by remember { mutableStateOf<String?>(null) }
    // A profile code's dialog set the account: next step.
    LaunchedEffect(noDialog, app.token, backup) {
        if (noDialog && backup == null && app.token != null) onDone()
    }

    StepHeader(Icons.Filled.Lock, stringResource(R.string.setup_account_title), stringResource(R.string.setup_account_text))
    Text(stringResource(R.string.identify_existing), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
    FilledTonalButton(onClick = onScan, modifier = Modifier.fillMaxWidth()) {
        Icon(PhoneIcons.QrScanner, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.setup_scan_profile_code))
    }
    OutlinedButton(onClick = onPickImage, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.choose_code_image)) }
    OutlinedButton(onClick = { importing = true }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.import_token_ellipsis)) }
    Text(stringResource(R.string.identify_existing_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    scanError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    HorizontalDivider()
    Text(stringResource(R.string.identify_new), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
    Button(onClick = { creating = true }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.setup_create_account)) }
    Text(stringResource(R.string.note_account), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    TextButton(onClick = onSkip, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.setup_skip)) }

    if (creating) CreateIdentityDialog(app.relay.orEmpty(), onDismiss = { creating = false }) { token ->
        creating = false
        app.useToken(token)
        // As in Settings: the new token to back up before going on.
        backup = token
    }
    if (importing) ImportTokenDialog(replaces = false, onDismiss = { importing = false }) { token ->
        importing = false
        app.useToken(token)
    }
    backup?.let { token -> TokenBackupDialog(token) { backup = null } }
}

/** New-episode alerts (the root's prompt does not ask again afterwards). */
@Composable
private fun NotificationsStep(onDone: () -> Unit) {
    val context = LocalContext.current
    fun finish(enabled: Boolean) {
        ReleaseReminders.setEnabled(context, enabled)
        ReleaseReminders.markPrompted(context)
        onDone()
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> finish(granted) }
    StepHeader(Icons.Filled.Notifications, stringResource(R.string.release_prompt_title), stringResource(R.string.release_prompt_text))
    Button(onClick = {
        if (Build.VERSION.SDK_INT >= 33 && !ReleaseReminders.hasPermission(context)) {
            permission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        } else {
            finish(true)
        }
    }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.setup_enable_alerts)) }
    TextButton(onClick = { finish(false) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.not_now)) }
}
