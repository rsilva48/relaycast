package net.silvara.relaycast

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs

/**
 * Remote control from the phone apps of the same identity, through the
 * relay. The player publishes its state here and receives the commands
 * that are not "play" (pause, seek, next…); "play" goes to the activity.
 */
object Remote {
    val playerCommands = MutableSharedFlow<RemoteCommand>(extraBufferCapacity = 16)
    val state = MutableStateFlow(DeviceState())
    /** "play" commands for the activity; kept until a profile is open. */
    val playRequests = Channel<RemoteCommand>(Channel.CONFLATED)
    /** Whether the app is on screen (it can then always open an episode). */
    val foreground = MutableStateFlow(false)
    /** Bumped after each command so the phone sees its effect right away. */
    internal val handled = MutableStateFlow(0L)
    /**
     * Whether the screen is on. In standby the service keeps running, but the
     * relay stops listing this TV to the phones: nobody would see what they send.
     */
    val awake = MutableStateFlow(true)
}

/**
 * Keeps this TV visible to the phones (a report on every change and every
 * few seconds) and long-polls for commands.
 */
suspend fun runRemoteControl(
    client: RelayClient,
    deviceId: String,
    deviceName: String,
    canOpen: () -> Boolean,
    onVolume: (Int) -> Unit,
    onPlay: (RemoteCommand) -> Unit,
) = coroutineScope {
    launch {
        while (isActive) {
            val current = Remote.state.value
            val foreground = Remote.foreground.value
            val handled = Remote.handled.value
            val awake = Remote.awake.value
            runCatching { client.reportDevice(deviceId, deviceName, current, foreground || canOpen(), awake) }
            // Status, episode or skip changes are sent right away; the
            // position alone every 5 s while playing, otherwise every 15 s.
            withTimeoutOrNull(if (current.status == "playing") 5_000L else 15_000L) {
                merge(
                    Remote.state.filter {
                        it.status != current.status || it.watchUrl != current.watchUrl || it.episode != current.episode || it.skip != current.skip ||
                            it.subtitle != current.subtitle || it.subtitles != current.subtitles
                    },
                    Remote.foreground.filter { it != foreground },
                    Remote.handled.filter { it != handled },
                    Remote.awake.filter { it != awake },
                ).first()
            }
            delay(500)
        }
    }
    launch {
        // The relay keeps each command until a later poll acknowledges it.
        var handled = 0L
        while (isActive) {
            val commands = try {
                client.waitForCommands(deviceId, handled).filter { it.seq == 0L || it.seq > handled }
            } catch (error: RelayException) {
                // Offline or a relay without remote control: try again later.
                delay(if (error.status == 404) 60_000L else 5_000L)
                continue
            }
            for (command in commands) {
                when (command.type) {
                    "play" -> onPlay(command)
                    "volume" -> command.steps?.let(onVolume)
                    else -> Remote.playerCommands.emit(command)
                }
            }
            commands.maxOfOrNull { it.seq }?.let { handled = maxOf(handled, it) }
            if (commands.isNotEmpty()) Remote.handled.value = System.currentTimeMillis()
        }
    }
}

/**
 * Keeps this TV reachable from the phones while the app is closed, like a
 * cast target: it starts at boot, after updates and whenever the app opens,
 * runs the remote control for the active profile, and opens the app for
 * "play". Opening an activity from the background needs the "display over
 * other apps" permission; without it the TV is listed only while the app is
 * on screen.
 */
class RemoteService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private var running: Pair<String, String>? = null

    override fun onBind(intent: Intent?): IBinder? = null

    // Screen on/off: standby is reported to the relay right away.
    private val screen = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_ON -> Remote.awake.value = true
                Intent.ACTION_SCREEN_OFF -> Remote.awake.value = false
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Remote.awake.value = getSystemService(PowerManager::class.java)?.isInteractive ?: true
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        ContextCompat.registerReceiver(this, screen, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), type)
        val state = AppState(applicationContext)
        val profile = state.activeProfile ?: state.profiles.singleOrNull()
        val relay = state.relayUrl
        if (profile == null || relay == null) {
            // Nobody to be reachable as (no relay yet, or choosing a profile).
            running = null
            job?.cancel()
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        // Started again on every app launch and profile change; restarts
        // the loop only when the identity changed.
        if (running != relay to profile.token || job?.isActive != true) {
            running = relay to profile.token
            job?.cancel()
            val name = Settings.Global.getString(contentResolver, "device_name") ?: Build.MODEL
            job = scope.launch {
                runRemoteControl(
                    state.client(profile), state.deviceId, name,
                    canOpen = { Settings.canDrawOverlays(this@RemoteService) },
                    onVolume = ::changeVolume,
                ) { command ->
                    val open = Intent(this@RemoteService, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        .putExtra(EXTRA_PLAY, RelayClient.json.encodeToString(RemoteCommand.serializer(), command))
                    runCatching { startActivity(open) }
                }
            }
        }
        return START_STICKY
    }

    // The TV's own volume; on a box connected by HDMI, Android forwards it
    // to the TV (or receiver) over CEC, like the remote's volume keys.
    private fun changeVolume(steps: Int) {
        val audio = getSystemService(AudioManager::class.java) ?: return
        val direction = if (steps > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
        repeat(minOf(abs(steps), 20)) {
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
        }
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(screen) }
        scope.cancel()
        super.onDestroy()
    }

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.remote_channel), NotificationManager.IMPORTANCE_MIN))
        }
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else @Suppress("DEPRECATION") Notification.Builder(this)
        return builder.setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.remote_notification))
            .setOngoing(true)
            .build()
    }

    companion object {
        const val EXTRA_PLAY = "net.silvara.relaycast.PLAY"
        private const val CHANNEL = "remote"
        private const val NOTIFICATION_ID = 1

        fun start(context: Context) {
            // The phone build of this app is a remote itself, not a target.
            if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)) return
            runCatching { ContextCompat.startForegroundService(context, Intent(context, RemoteService::class.java)) }
        }
    }
}

/** Starts [RemoteService] when the TV boots and after the app is updated. */
class RemoteStartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) RemoteService.start(context)
    }
}
