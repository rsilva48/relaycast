@file:OptIn(ExperimentalTvMaterial3Api::class)

package net.silvara.relaycast

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.Button
import androidx.tv.material3.Card
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import java.net.URLEncoder
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Relay address: scheme picker plus host (with port if needed). */
@Composable
fun SetupScreen(state: AppState, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    val current = state.relayUrl
    var scheme by remember { mutableStateOf(if (current?.startsWith("http://") == true) "http" else "https") }
    var host by remember { mutableStateOf(current?.substringAfter("://").orEmpty()) }
    var testing by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    fun test() {
        val url = RelayClient.canonicalUrl(scheme, host) ?: run {
            message = Strings.get(R.string.relay_address_invalid)
            return
        }
        testing = true
        message = null
        scope.launch {
            try {
                val health = state.anonymousClient(url).health()
                if (!health.ok) throw RelayException(Strings.get(R.string.relay_bad_health))
                state.setRelay(url)
                onDone()
            } catch (error: RelayException) {
                message = error.message
            } finally {
                testing = false
            }
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 96.dp, vertical = 64.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Text(stringResource(R.string.setup_title), style = MaterialTheme.typography.displaySmall)
        Text(
            stringResource(R.string.setup_text_tv),
            style = MaterialTheme.typography.bodyLarge,
            color = Color.White.copy(alpha = 0.7f),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf("https", "http").forEach { option ->
                if (scheme == option) {
                    Button(onClick = { scheme = option }) { Text("$option://") }
                } else {
                    OutlinedButton(onClick = { scheme = option }) { Text("$option://") }
                }
            }
        }
        TvTextField(
            value = host,
            onValueChange = { value ->
                // A pasted scheme moves to the picker, like on iOS.
                when {
                    value.startsWith("https://") -> { scheme = "https"; host = value.removePrefix("https://") }
                    value.startsWith("http://") -> { scheme = "http"; host = value.removePrefix("http://") }
                    else -> host = value
                }
            },
            placeholder = stringResource(R.string.relay_placeholder),
            keyboardType = KeyboardType.Uri,
            imeAction = ImeAction.Go,
            onSubmit = ::test,
        )
        Button(onClick = ::test, enabled = !testing) { Text(stringResource(if (testing) R.string.testing else R.string.test_and_save)) }
        message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

/**
 * For a dialog opened by holding OK: the key's repeats and release would
 * press its focused button, so keys count from a new press.
 */
@Composable
fun Modifier.ignoringHeldKey(): Modifier {
    var armed by remember { mutableStateOf(false) }
    return onPreviewKeyEvent { event ->
        val key = event.nativeKeyEvent
        if (!armed && key.action == android.view.KeyEvent.ACTION_DOWN && key.repeatCount == 0) armed = true
        !armed
    }
}

/**
 * "Who is watching": one tile per paired profile, pairing one from a phone,
 * and creating a new one with an existing profile (no administrator token).
 */
@Composable
fun ProfilesScreen(state: AppState, onSelect: (Profile) -> Unit, onAdd: () -> Unit, onChangeRelay: () -> Unit) {
    var menuFor by remember { mutableStateOf<Profile?>(null) }
    var removing by remember { mutableStateOf<Profile?>(null) }
    var transferring by remember { mutableStateOf<Profile?>(null) }
    var creating by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<Profile?>(null) }
    // Each profile's name and picture from the relay (set on a phone), shown
    // from the cache first; the name is kept on the TV too.
    val context = androidx.compose.ui.platform.LocalContext.current
    val accounts = remember {
        androidx.compose.runtime.mutableStateMapOf<String, AccountProfile>().apply {
            state.profiles.forEach { profile -> AccountCache.load(context, profile.id)?.let { put(profile.id, it) } }
        }
    }
    LaunchedEffect(state.profiles.map { it.id }, state.relayUrl) {
        val relay = state.relayUrl ?: return@LaunchedEffect
        for (profile in state.profiles) {
            runCatching { RelayClient(relay, profile.token).account() }.onSuccess { account ->
                accounts[profile.id] = account
                AccountCache.store(context, profile.id, account)
                account.displayName?.let { state.renameProfile(profile, it) }
            }
        }
    }

    Box(
        Modifier.fillMaxSize().background(
            androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color(0xFF1B1D3A), Color(0xFF0B0B12))),
        ),
    ) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 64.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(stringResource(R.string.who_is_watching), style = MaterialTheme.typography.displaySmall, color = Color.White)
        Spacer(Modifier.height(28.dp))
        // The profile used last has the focus: OK continues with it.
        val lastUsed = state.activeProfile ?: state.profiles.firstOrNull()
        val lastUsedFocus = remember { FocusRequester() }
        LaunchedEffect(lastUsed?.id) {
            delay(100)
            runCatching { lastUsedFocus.requestFocus() }
        }
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(40.dp, Alignment.CenterHorizontally),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 16.dp, horizontal = 24.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            items(state.profiles, key = { it.id }) { profile ->
                ProfileTile(
                    name = profile.name,
                    account = accounts[profile.id],
                    onClick = { onSelect(profile) },
                    onLongClick = { menuFor = profile },
                    modifier = if (profile.id == lastUsed?.id) Modifier.focusRequester(lastUsedFocus) else Modifier,
                )
            }
            if (state.profiles.isNotEmpty()) item {
                ProfileTile(name = stringResource(R.string.create_profile), symbol = "+", onClick = { creating = true })
            }
            item {
                ProfileTile(name = stringResource(R.string.link_profile), symbol = "⌁", onClick = onAdd)
            }
        }
        Spacer(Modifier.height(20.dp))
        Text(
            stringResource(R.string.profiles_hint, state.relayUrl.orEmpty()),
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.5f),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        OutlinedButton(onClick = onChangeRelay) { Text(stringResource(R.string.change_relay)) }
    }
    }

    menuFor?.let { profile ->
        Dialog(onDismissRequest = { menuFor = null }) {
            Surface(shape = RoundedCornerShape(24.dp)) {
                Column(Modifier.padding(32.dp).ignoringHeldKey(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(profile.name, style = MaterialTheme.typography.titleLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = { menuFor = null; transferring = profile }) { Text(stringResource(R.string.take_to_phone)) }
                        OutlinedButton(onClick = { menuFor = null; renaming = profile }) { Text(stringResource(R.string.rename_profile)) }
                        OutlinedButton(onClick = { menuFor = null; removing = profile }) { Text(stringResource(R.string.remove)) }
                        OutlinedButton(onClick = { menuFor = null }) { Text(stringResource(R.string.cancel)) }
                    }
                }
            }
        }
    }

    renaming?.let { profile ->
        RenameProfileDialog(state, profile, onDismiss = { renaming = null }) { account ->
            accounts[profile.id] = account
        }
    }

    if (creating) CreateProfileDialog(state, onDismiss = { creating = false }) { created ->
        creating = false
        transferring = created
    }

    transferring?.let { profile ->
        TransferProfileDialog(state, profile, onDismiss = { transferring = null })
    }

    removing?.let { profile ->
        Dialog(onDismissRequest = { removing = null }) {
            Surface(shape = RoundedCornerShape(24.dp)) {
                Column(Modifier.padding(32.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(stringResource(R.string.remove_profile_title, profile.name), style = MaterialTheme.typography.titleLarge)
                    Text(stringResource(R.string.remove_profile_text), color = Color.White.copy(alpha = 0.7f))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = { state.removeProfile(profile); removing = null }) { Text(stringResource(R.string.remove)) }
                        OutlinedButton(onClick = { removing = null }) { Text(stringResource(R.string.cancel)) }
                    }
                }
            }
        }
    }
}

/** Names a new profile and creates its identity with an existing profile's. */
@Composable
private fun CreateProfileDialog(state: AppState, onDismiss: () -> Unit, onCreated: (Profile) -> Unit) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    fun create() {
        val label = name.trim()
        val creator = state.activeProfile ?: state.profiles.firstOrNull() ?: return
        if (label.isEmpty() || busy) return
        busy = true
        message = null
        scope.launch {
            try {
                val identity = state.client(creator).createIdentity(label)
                onCreated(state.addProfile(identity.label ?: label, identity.token))
            } catch (error: RelayException) {
                message = if (error.status == 404) Strings.get(R.string.create_profile_unsupported) else error.message
            } finally {
                busy = false
            }
        }
    }

    Dialog(onDismissRequest = { if (!busy) onDismiss() }) {
        Surface(shape = RoundedCornerShape(24.dp)) {
            Column(Modifier.padding(32.dp).width(560.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringResource(R.string.create_profile), style = MaterialTheme.typography.titleLarge)
                Text(
                    stringResource(R.string.create_profile_text),
                    color = Color.White.copy(alpha = 0.7f),
                )
                TvTextField(value = name, onValueChange = { name = it.take(40) }, placeholder = stringResource(R.string.profile_name), imeAction = ImeAction.Done, onSubmit = ::create)
                message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = ::create, enabled = !busy && name.isNotBlank()) { Text(stringResource(if (busy) R.string.creating else R.string.create)) }
                    OutlinedButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.cancel)) }
                }
            }
        }
    }
}

/**
 * Shows a profile's identity as a QR for the phone app (relaycast://identity).
 * Whoever scans it gets that history, so it warns first and hides itself.
 */
@Composable
private fun TransferProfileDialog(state: AppState, profile: Profile, onDismiss: () -> Unit) {
    var showing by remember { mutableStateOf(false) }
    val link = remember(profile) { identityLink(state.relayUrl.orEmpty(), profile.token, profile.name) }
    if (showing) LaunchedEffect(Unit) {
        delay(60_000)
        onDismiss()
    }
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(24.dp)) {
            if (!showing) {
                Column(Modifier.padding(32.dp).width(560.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(stringResource(R.string.transfer_title, profile.name), style = MaterialTheme.typography.titleLarge)
                    Text(
                        stringResource(R.string.transfer_warning),
                        color = Color.White.copy(alpha = 0.8f),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = { showing = true }) { Text(stringResource(R.string.show_code)) }
                        OutlinedButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
                    }
                }
            } else {
                Row(Modifier.padding(32.dp), horizontalArrangement = Arrangement.spacedBy(32.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(300.dp).background(Color.White, RoundedCornerShape(16.dp)).padding(16.dp)) {
                        Image(remember(link) { qrBitmap(link) }.asImageBitmap(), contentDescription = stringResource(R.string.profile_qr_description), modifier = Modifier.fillMaxSize())
                    }
                    Column(Modifier.width(360.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Text(stringResource(R.string.scan_with_app), style = MaterialTheme.typography.titleLarge)
                        Text(
                            stringResource(R.string.transfer_scan_hint, profile.name),
                            color = Color.White.copy(alpha = 0.75f),
                        )
                        Text(stringResource(R.string.code_hides), color = Color.White.copy(alpha = 0.55f))
                        Button(onClick = onDismiss) { Text(stringResource(R.string.done)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProfileTile(name: String, onClick: () -> Unit, symbol: String? = null, account: AccountProfile? = null, onLongClick: (() -> Unit)? = null, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    Column(Modifier.width(176.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Card(
            onClick = onClick,
            onLongClick = onLongClick,
            modifier = modifier.size(156.dp).onFocusChanged { focused = it.isFocused },
            shape = androidx.tv.material3.CardDefaults.shape(CircleShape),
            scale = androidx.tv.material3.CardDefaults.scale(focusedScale = 1.1f),
            border = androidx.tv.material3.CardDefaults.border(
                focusedBorder = androidx.tv.material3.Border(androidx.compose.foundation.BorderStroke(4.dp, Color.White), shape = CircleShape),
            ),
            glow = androidx.tv.material3.CardDefaults.glow(
                focusedGlow = androidx.tv.material3.Glow(Color.White.copy(alpha = 0.35f), 18.dp),
            ),
        ) {
            if (symbol == null) {
                // The picture chosen on a phone (or the initial on its color).
                Avatar(account, name, 156.dp)
            } else {
                // Create and link: neutral, so the profiles stand out.
                Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                    Text(symbol, fontSize = 56.sp, color = Color.White.copy(alpha = 0.85f))
                }
            }
        }
        Text(
            name,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            color = if (focused) Color.White else Color.White.copy(alpha = 0.6f),
        )
    }
}

/** Renames a profile on the relay (every phone and TV shows it) and on this TV. */
@Composable
private fun RenameProfileDialog(state: AppState, profile: Profile, onDismiss: () -> Unit, onRenamed: (AccountProfile) -> Unit) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(profile.name) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    fun save() {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || busy) return
        busy = true
        error = null
        scope.launch {
            try {
                val account = RelayClient(state.relayUrl.orEmpty(), profile.token).renameAccount(trimmed)
                state.renameProfile(profile, trimmed)
                onRenamed(account)
                onDismiss()
            } catch (failure: RelayException) {
                error = failure.message
            } finally {
                busy = false
            }
        }
    }
    Dialog(onDismissRequest = { if (!busy) onDismiss() }) {
        Surface(shape = RoundedCornerShape(24.dp)) {
            Column(Modifier.padding(32.dp).width(520.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringResource(R.string.rename_profile), style = MaterialTheme.typography.titleLarge)
                TvTextField(value = name, onValueChange = { name = it.take(40) }, placeholder = stringResource(R.string.profile_name), onSubmit = ::save)
                error?.let { Text(it, color = Color(0xFFFF8A80)) }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = ::save, enabled = !busy && name.isNotBlank()) { Text(stringResource(if (busy) R.string.saving_tv else R.string.save_tv)) }
                    OutlinedButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.cancel)) }
                }
            }
        }
    }
}

/**
 * Pairing: shows a QR to the relay's setup page with a one-time code. The
 * phone approves it (with the app, a pasted token, or the first time the
 * administrator token), and this screen receives the identity's token.
 */
@Composable
fun PairingScreen(state: AppState, onPaired: () -> Unit, onChangeRelay: () -> Unit) {
    var qr by remember { mutableStateOf<Bitmap?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val relay = state.relayUrl.orEmpty()

    LaunchedEffect(relay) {
        val client = state.anonymousClient()
        // A new code whenever the previous one expires.
        while (true) {
            try {
                val pairing = client.startPairing()
                // The relay's setup page: any phone camera opens it; from there
                // the app approves it, or a token is pasted (or created once
                // with the administrator token).
                qr = qrBitmap("$relay/setup?code=${URLEncoder.encode(pairing.code, "UTF-8")}")
                message = null
                while (true) {
                    delay(2000)
                    val status = runCatching { client.pairingStatus(pairing.pairingId) }.getOrNull() ?: continue
                    if (status.status == "approved") {
                        state.addProfile(status.label ?: Strings.get(R.string.default_profile_name), status.token.orEmpty())
                        onPaired()
                        return@LaunchedEffect
                    }
                    if (status.status == "expired") break
                }
            } catch (error: RelayException) {
                qr = null
                message = error.message
                delay(5000)
            }
        }
    }

    Row(Modifier.fillMaxSize().padding(horizontal = 96.dp, vertical = 64.dp), horizontalArrangement = Arrangement.spacedBy(64.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(320.dp).background(Color.White, RoundedCornerShape(16.dp)).padding(16.dp), contentAlignment = Alignment.Center) {
            qr?.let { Image(it.asImageBitmap(), contentDescription = stringResource(R.string.pairing_qr_description), modifier = Modifier.fillMaxSize()) }
                ?: Text(stringResource(R.string.generating_code), color = Color.Black)
        }
        Column(verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.width(560.dp)) {
            Text(stringResource(R.string.pairing_title), style = MaterialTheme.typography.displaySmall)
            Text(stringResource(R.string.pairing_step1), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.pairing_step2), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.pairing_step3), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.pairing_note),
                color = Color.White.copy(alpha = 0.65f),
            )
            Text("Relay: $relay", color = Color.White.copy(alpha = 0.5f))
            message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            OutlinedButton(onClick = onChangeRelay) { Text(stringResource(R.string.change_relay)) }
        }
    }
}

internal fun qrBitmap(text: String, size: Int = 512): Bitmap {
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1))
    val pixels = IntArray(size * size) { index -> if (matrix[index % size, index / size]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
    return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
}

/** A text field that works with the TV keyboard (and voice input). */
@Composable
fun TvTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Done,
    onSubmit: () -> Unit = {},
) {
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var focused by remember { mutableStateOf(false) }
    // Read-only until OK: focusing the field must not open the keyboard,
    // which would take over the D-pad.
    var editing by remember { mutableStateOf(false) }
    BasicTextField(
        readOnly = !editing,
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = TextStyle(color = Color.White, fontSize = 22.sp),
        cursorBrush = SolidColor(Color.White),
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction, autoCorrectEnabled = false),
        keyboardActions = KeyboardActions(onAny = {
            editing = false
            keyboard?.hide()
            onSubmit()
        }),
        // Single line: up and down leave the field (the D-pad would stay in
        // it otherwise), and right does too while it is empty.
        modifier = modifier.fillMaxWidth()
            .onFocusChanged {
                focused = it.isFocused
                if (!it.isFocused) editing = false
            }
            .onPreviewKeyEvent { event ->
                val key = event.nativeKeyEvent
                val confirm = key.keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER || key.keyCode == android.view.KeyEvent.KEYCODE_ENTER
                if (confirm && !editing) {
                    if (key.action == android.view.KeyEvent.ACTION_UP) {
                        editing = true
                        keyboard?.show()
                    }
                    return@onPreviewKeyEvent true
                }
                if (key.action != android.view.KeyEvent.ACTION_DOWN) return@onPreviewKeyEvent false
                when (key.keyCode) {
                    android.view.KeyEvent.KEYCODE_DPAD_UP -> focusManager.moveFocus(FocusDirection.Up)
                    android.view.KeyEvent.KEYCODE_DPAD_DOWN -> focusManager.moveFocus(FocusDirection.Down)
                    // Not typing (or nothing typed), left and right leave too.
                    android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> (!editing || value.isEmpty()) && focusManager.moveFocus(FocusDirection.Right)
                    android.view.KeyEvent.KEYCODE_DPAD_LEFT -> (!editing || value.isEmpty()) && focusManager.moveFocus(FocusDirection.Left)
                    else -> false
                }
            },
        decorationBox = { inner ->
            Box(
                Modifier.background(Color.White.copy(alpha = if (focused) 0.16f else 0.1f), RoundedCornerShape(12.dp))
                    .border(2.dp, if (focused) Color.White.copy(alpha = 0.8f) else Color.Transparent, RoundedCornerShape(12.dp))
                    .padding(horizontal = 20.dp, vertical = 16.dp),
            ) {
                if (value.isEmpty()) Text(placeholder, color = Color.White.copy(alpha = 0.4f), fontSize = 22.sp)
                inner()
            }
        },
    )
}
