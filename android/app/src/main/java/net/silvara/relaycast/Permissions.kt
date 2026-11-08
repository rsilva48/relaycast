@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package net.silvara.relaycast

import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.net.Uri
import android.os.Process
import android.os.UserManager
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Surface
import androidx.tv.material3.Text

/**
 * Special app access ("display over other apps") is
 * granted per Android user. TV Settings opens the whole list instead of this
 * app's page, and when the app is also installed in a work profile it can
 * list only that copy (the one with a briefcase): turning that on does
 * nothing for this one (seen on a TCL with Android 11; `adb install`
 * installs for every profile). Returns the serial numbers of those copies.
 */
private fun otherProfileCopies(context: Context): List<Long> {
    val users = context.getSystemService(UserManager::class.java) ?: return emptyList()
    val launcher = context.getSystemService(LauncherApps::class.java) ?: return emptyList()
    return users.userProfiles.filter { it != Process.myUserHandle() }
        .filter { profile -> runCatching { launcher.getApplicationInfo(context.packageName, 0, profile) }.isSuccess }
        .map { users.getSerialNumberForUser(it) }
}

/** How to grant a special access in TV Settings, with the work-profile case spelled out. */
fun specialAccessHint(context: Context, setting: String): String {
    if (otherProfileCopies(context).isEmpty()) return context.getString(R.string.special_access_hint, setting)
    return context.getString(R.string.special_access_hint_other_profile, setting)
}

/** Opens a special-access screen for this app; false when the TV does not have it. */
fun openSpecialAccess(context: Context, action: String): Boolean =
    runCatching { context.startActivity(Intent(action, Uri.parse("package:${context.packageName}"))) }.isSuccess

private val overlaySetting: String get() = Strings.get(R.string.overlay_setting)

/**
 * Asks for "display over other apps", which lets [RemoteService] open the
 * app when a phone sends an episode while it is closed. Hidden once granted.
 */
@Composable
fun RemoteAccessControl() {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var show by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    LifecycleResumeEffect(Unit) {
        granted = Settings.canDrawOverlays(context)
        if (granted) show = false
        onPauseOrDispose { }
    }
    if (granted) return
    OutlinedButton(onClick = { message = null; show = true }) { Text(stringResource(R.string.open_from_phone)) }
    Spacer(Modifier.width(12.dp))
    if (show) Dialog(onDismissRequest = { show = false }) {
        Surface(shape = RoundedCornerShape(24.dp)) {
            Column(Modifier.padding(32.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringResource(R.string.open_from_phone), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.overlay_explanation, overlaySetting))
                Text(message ?: specialAccessHint(context, overlaySetting))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = {
                        if (!openSpecialAccess(context, Settings.ACTION_MANAGE_OVERLAY_PERMISSION)) {
                            message = context.getString(R.string.overlay_missing)
                        }
                    }) { Text(stringResource(R.string.open_settings)) }
                    OutlinedButton(onClick = { show = false }) { Text(stringResource(R.string.not_now)) }
                }
            }
        }
    }
}
