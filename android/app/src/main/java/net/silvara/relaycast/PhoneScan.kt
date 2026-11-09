package net.silvara.relaycast

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.google.mlkit.vision.common.InputImage
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * A profile's QR, shown by a TV ("Take to phone") or by a phone (Settings ›
 * Show profile code). Values are percent-encoded (%20, not +), which both
 * Android and iOS decode the same way.
 */
internal fun identityLink(relay: String, token: String, label: String): String =
    "relaycast://identity?bridge=${Uri.encode(relay)}&token=${Uri.encode(token)}&label=${Uri.encode(label)}"

/** A QR read on the phone: a profile to use here (from a TV or another phone), or a TV to pair with this identity. */
internal sealed interface ScannedLink {
    /** "Take to phone": relaycast://identity?bridge=&token=&label= */
    data class Identity(val relay: String, val token: String, val label: String) : ScannedLink

    /** A TV pairing: relaycast://pair?bridge=&code= or the relay's https://…/setup?code= page. */
    data class Pairing(val relay: String, val code: String) : ScannedLink

    /**
     * A relay's setup code for a new device: relaycast://provision?bridge=&admin=.
     * With the administrator token, a new identity is created with it filled
     * in; it is never stored. Whoever has the code can create identities.
     */
    data class Provisioning(val relay: String, val adminToken: String?) : ScannedLink

    companion object {
        fun parse(text: String): ScannedLink? {
            val uri = runCatching { Uri.parse(text.trim()) }.getOrNull() ?: return null
            val scheme = uri.scheme?.lowercase()
            fun relayOf(value: String?): String? {
                val url = value?.let { runCatching { Uri.parse(it) }.getOrNull() } ?: return null
                val host = url.authority ?: return null
                return RelayClient.canonicalUrl(url.scheme ?: return null, host)
            }
            if (scheme == "relaycast" && uri.host == "identity") {
                val relay = relayOf(uri.getQueryParameter("bridge")) ?: return null
                val token = uri.getQueryParameter("token")?.lowercase()?.takeIf { Regex("^[a-f0-9]{64}$").matches(it) } ?: return null
                return Identity(relay, token, uri.getQueryParameter("label")?.trim()?.ifEmpty { null } ?: Strings.get(R.string.default_profile_name))
            }
            if (scheme == "relaycast" && uri.host == "provision") {
                val relay = relayOf(uri.getQueryParameter("bridge")) ?: return null
                val admin = uri.getQueryParameter("admin")?.trim()?.takeIf { it.isNotEmpty() && it.length <= 512 && it.none(Char::isWhitespace) }
                return Provisioning(relay, admin)
            }
            val code = uri.getQueryParameter("code")?.takeIf { it.isNotBlank() } ?: return null
            if (scheme == "relaycast" && uri.host == "pair") return relayOf(uri.getQueryParameter("bridge"))?.let { Pairing(it, code) }
            if ((scheme == "https" || scheme == "http") && uri.path == "/setup") return relayOf(text.trim())?.let { Pairing(it, code) }
            return null
        }
    }
}

/** Links opened from the camera (MainActivity) wait here for the phone screens. */
internal object PhoneLinks {
    val pending = MutableStateFlow<ScannedLink?>(null)
}

internal suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { continuation.resume(it) }
    addOnFailureListener { continuation.resumeWithException(it) }
    addOnCanceledListener { continuation.cancel() }
}

/**
 * Scans a TV's QR with Google's code scanner (no camera permission: the
 * scanner runs in Google Play services). Null when cancelled; throws when
 * the code is not one of this app's.
 */
internal suspend fun scanTvCode(context: Context): ScannedLink? {
    val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
    val barcode = runCatching { GmsBarcodeScanning.getClient(context, options).startScan().await() }.getOrElse {
        if (it is kotlinx.coroutines.CancellationException) return null
        throw RelayException(Strings.get(R.string.scanner_failed, it.message.orEmpty()))
    }
    val text = barcode.rawValue ?: return null
    return ScannedLink.parse(text) ?: throw RelayException(Strings.get(R.string.not_our_code))
}

/**
 * Reads this app's QR from an image (a screenshot, a photo of a printed
 * code): for a code that is only on this same phone. Throws when the image
 * has no QR, or none of this app's.
 */
internal suspend fun readCodeFromImage(context: Context, uri: Uri): ScannedLink {
    val image = runCatching { InputImage.fromFilePath(context, uri) }.getOrElse { throw RelayException(Strings.get(R.string.qr_image_unreadable)) }
    val scanner = BarcodeScanning.getClient(BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build())
    val codes = try {
        scanner.process(image).await()
    } catch (failure: Exception) {
        if (failure is kotlinx.coroutines.CancellationException) throw failure
        throw RelayException(Strings.get(R.string.qr_image_failed, failure.message.orEmpty()))
    } finally {
        scanner.close()
    }
    val texts = codes.mapNotNull { it.rawValue }
    if (texts.isEmpty()) throw RelayException(Strings.get(R.string.qr_not_found))
    return texts.firstNotNullOfOrNull { ScannedLink.parse(it) } ?: throw RelayException(Strings.get(R.string.not_our_code))
}

/**
 * Picks an image with the system Photo Picker (no storage permission) and
 * reads the QR in it, like [scanTvCode] does with the camera. Returns the
 * function that opens the picker.
 */
@Composable
internal fun rememberCodeImagePicker(onLink: (ScannedLink) -> Unit, onError: (String) -> Unit): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val currentOnLink by rememberUpdatedState(onLink)
    val currentOnError by rememberUpdatedState(onError)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            try {
                currentOnLink(readCodeFromImage(context, uri))
            } catch (failure: RelayException) {
                currentOnError(failure.message.orEmpty())
            }
        }
    }
    return { launcher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
}

/**
 * Shows this phone's profile as the same QR a TV shows, so another phone can
 * use the same Continue and preferences. Whoever scans it gets that history,
 * so it warns first, keeps the code out of screenshots and hides it after a
 * minute. The other phone shows the profile's name ("Use the profile …");
 * it is asked for only when the profile has none yet.
 */
@Composable
internal fun ProfileCodeDialog(relay: String, token: String, profileName: String?, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(profileName.orEmpty()) }
    var showing by remember { mutableStateOf(false) }
    if (showing) LaunchedEffect(Unit) {
        delay(60_000)
        onDismiss()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
        title = { Text(stringResource(R.string.profile_code_title)) },
        text = {
            if (!showing) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.profile_code_warning))
                    if (profileName.isNullOrBlank()) OutlinedTextField(
                        value = name,
                        onValueChange = { name = it.take(40) },
                        label = { Text(stringResource(R.string.profile_code_name)) },
                        singleLine = true,
                    )
                }
            } else {
                val link = remember(name) { identityLink(relay, token, name.trim().ifEmpty { Strings.get(R.string.default_profile_name) }) }
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(240.dp).background(Color.White, RoundedCornerShape(12.dp)).padding(12.dp)) {
                        Image(remember(link) { qrBitmap(link) }.asImageBitmap(), contentDescription = stringResource(R.string.profile_qr_description), modifier = Modifier.fillMaxSize())
                    }
                    Text(stringResource(R.string.profile_code_hint))
                    Text(stringResource(R.string.code_hides), style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            if (showing) TextButton(onClick = onDismiss) { Text(stringResource(R.string.done)) }
            else TextButton(onClick = { showing = true }) { Text(stringResource(R.string.show_code)) }
        },
        dismissButton = { if (!showing) TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/**
 * Sets up this phone from a relay's setup code: tests the relay, then asks
 * how to identify. Recommended: an existing account, with that account's
 * profile code (camera or image) or its token; or a new account, with the
 * code's administrator token filled in (never stored), and its new token to
 * back up. A code without a token sets the relay, and asks only if this
 * phone has no identity for it yet.
 */
@Composable
internal fun ProvisioningDialog(app: PhoneState, link: ScannedLink.Provisioning, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val sameRelay = app.relay == link.relay
    // A new identity replaces this one; another relay drops it from the phone.
    val replaces = app.token != null && !(sameRelay && link.adminToken == null)
    var confirmed by remember { mutableStateOf(!replaces) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    /** Relay tested: how to identify comes next. */
    var identifying by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var created by remember { mutableStateOf<String?>(null) }
    fun adoptRelay() { if (app.relay != link.relay) app.useRelay(link.relay) }

    created?.let { token ->
        TokenBackupDialog(token, onDismiss)
        return
    }
    if (creating) {
        CreateIdentityDialog(link.relay, initialAdminToken = link.adminToken.orEmpty(), onDismiss = { creating = false }) { token ->
            adoptRelay()
            app.useToken(token)
            scope.launch { app.session?.refresh() }
            created = token
        }
        return
    }
    if (importing) {
        // The replacement was confirmed on the first screen.
        ImportTokenDialog(replaces = false, onDismiss = { importing = false }) { token ->
            adoptRelay()
            app.useToken(token)
            scope.launch { app.session?.refresh() }
            onDismiss()
        }
        return
    }
    if (identifying) {
        IdentifyDialog(
            relay = link.relay,
            canCreateWithCode = link.adminToken != null,
            onIdentity = { identity -> PhoneLinks.pending.value = identity },
            onImport = { importing = true },
            onCreate = { creating = true },
            onLater = {
                adoptRelay()
                scope.launch { app.session?.refresh() }
                onDismiss()
            },
        )
        return
    }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.setup_relay_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(link.relay, style = MaterialTheme.typography.titleSmall)
                Text(stringResource(if (link.adminToken == null) R.string.setup_relay_only else R.string.setup_relay_identity))
                if (app.relay != null && !sameRelay) Text(stringResource(R.string.setup_relay_other, app.relay.orEmpty()))
                if (replaces) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { confirmed = !confirmed }) {
                    Checkbox(checked = confirmed, onCheckedChange = { confirmed = it })
                    Text(stringResource(R.string.saved_current_token_replace))
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(enabled = confirmed && !busy, onClick = {
                busy = true
                error = null
                scope.launch {
                    try {
                        if (!RelayClient(link.relay, "").health().ok) throw RelayException(Strings.get(R.string.relay_unexpected))
                        if (link.adminToken != null) {
                            identifying = true
                        } else {
                            adoptRelay()
                            app.session?.refresh()
                            // Already identified on this relay: nothing else to do.
                            if (app.token != null) onDismiss() else identifying = true
                        }
                    } catch (failure: RelayException) {
                        error = failure.message
                    } finally {
                        busy = false
                    }
                }
            }) { Text(stringResource(if (busy) R.string.testing_connection else R.string.continue_action)) }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/**
 * After a setup code: how this phone identifies. Existing account first (its
 * profile code from a phone's Settings or a TV's "Take to phone", by camera
 * or from an image, or its token), then a new account.
 */
@Composable
private fun IdentifyDialog(
    relay: String,
    canCreateWithCode: Boolean,
    onIdentity: (ScannedLink.Identity) -> Unit,
    onImport: () -> Unit,
    onCreate: () -> Unit,
    onLater: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    fun handle(found: ScannedLink?) {
        when (found) {
            null -> Unit
            is ScannedLink.Identity -> onIdentity(found)
            else -> error = Strings.get(R.string.identify_wrong_code)
        }
    }
    val pickImage = rememberCodeImagePicker(onLink = ::handle, onError = { error = it })
    AlertDialog(
        onDismissRequest = onLater,
        title = { Text(stringResource(R.string.identify_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.identify_relay_ready, relay), style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.identify_existing), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                FilledTonalButton(onClick = {
                    error = null
                    scope.launch {
                        try { handle(scanTvCode(context)) } catch (failure: RelayException) { error = failure.message }
                    }
                }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.identify_scan_profile)) }
                OutlinedButton(onClick = { error = null; pickImage() }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.choose_code_image)) }
                OutlinedButton(onClick = onImport, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.import_token_ellipsis)) }
                Text(stringResource(R.string.identify_existing_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                HorizontalDivider()
                Text(stringResource(R.string.identify_new), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                Button(onClick = onCreate, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.setup_create_account)) }
                Text(
                    stringResource(if (canCreateWithCode) R.string.identify_new_with_code else R.string.identify_new_needs_admin),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { TextButton(onClick = onLater) { Text(stringResource(R.string.identify_later)) } },
    )
}

/** Uses a profile from a TV or another phone (same Continue and preferences); a different relay is tested first. */
@Composable
internal fun IdentityImportDialog(app: PhoneState, link: ScannedLink.Identity, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val sameRelay = app.relay == link.relay
    val inUse = sameRelay && app.token == link.token
    val replaces = sameRelay && app.token != null && !inUse
    var confirmed by remember { mutableStateOf(!replaces) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.use_profile_title, link.label)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (inUse) {
                    Text(stringResource(R.string.profile_in_use))
                } else {
                    Text(stringResource(R.string.use_profile_text))
                    if (!sameRelay) Text(
                        app.relay?.let { stringResource(R.string.other_relay_switch, link.relay, it) }
                            ?: stringResource(R.string.connect_relay, link.relay),
                    )
                    if (replaces) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { confirmed = !confirmed }) {
                        Checkbox(checked = confirmed, onCheckedChange = { confirmed = it })
                        Text(stringResource(R.string.saved_current_token_replace))
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            if (!inUse) TextButton(enabled = confirmed && !busy, onClick = {
                busy = true
                error = null
                scope.launch {
                    try {
                        if (!sameRelay) {
                            if (!RelayClient(link.relay, "").health().ok) throw RelayException(Strings.get(R.string.relay_unexpected))
                            app.useRelay(link.relay)
                        }
                        app.useToken(link.token)
                        app.session?.refresh()
                        onDismiss()
                    } catch (failure: RelayException) {
                        error = failure.message
                    } finally {
                        busy = false
                    }
                }
            }) { Text(stringResource(if (busy) R.string.connecting else R.string.use_this_profile)) }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text(stringResource(if (inUse) R.string.close else R.string.cancel)) } },
    )
}

/** Approves a TV pairing with this phone's identity: the TV shares its Continue and preferences. */
@Composable
internal fun PairTvDialog(app: PhoneState, link: ScannedLink.Pairing, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    // The TV shows the profile's name; asked for only when it has none yet.
    val profileName = app.session?.account?.displayName?.takeIf { it.isNotBlank() }
    var label by remember { mutableStateOf(profileName.orEmpty()) }
    var busy by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val matches = app.relay != null && app.relay == link.relay
    val defaultName = stringResource(R.string.default_profile_name)
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.link_a_tv)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when {
                    done -> Text(stringResource(R.string.tv_linked, label.ifBlank { defaultName }))
                    !matches -> Text(stringResource(R.string.tv_other_relay, link.relay))
                    else -> {
                        Text(stringResource(R.string.link_tv_text))
                        if (profileName == null) OutlinedTextField(value = label, onValueChange = { label = it.take(40) }, label = { Text(stringResource(R.string.tv_profile_name)) }, placeholder = { Text(stringResource(R.string.tv_profile_placeholder)) }, singleLine = true)
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            if (matches && !done) TextButton(enabled = !busy, onClick = {
                busy = true
                error = null
                scope.launch {
                    try {
                        app.session?.client?.approvePairing(link.code, label.ifBlank { defaultName }) ?: throw RelayException(Strings.get(R.string.setup_relay_first))
                        done = true
                    } catch (failure: RelayException) {
                        error = failure.message
                    } finally {
                        busy = false
                    }
                }
            }) { Text(stringResource(if (busy) R.string.linking else R.string.link_tv)) }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text(stringResource(if (done) R.string.done else R.string.cancel)) } },
    )
}
