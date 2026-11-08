package net.silvara.relaycast

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.Serializable
import org.json.JSONObject

/** An episode the catalog expects to release (GET /api/schedule). */
@Serializable
data class ScheduleEntry(
    val watchUrl: String,
    val title: String,
    val titleJp: String? = null,
    val imageUrl: String? = null,
    val episode: Int,
    val kind: String,
    /** Unix seconds. */
    val airingAt: Double,
)

@Serializable
data class ScheduleReply(val entries: List<ScheduleEntry> = emptyList())

/**
 * Notifications of new episodes, as on iOS: for the series in Continue, in
 * the Sub/Dub kind being followed, only once the catalog has really
 * published them. The app asks the relay for each series' latest episode
 * and notifies those past the last one it knew; an estimated airing time is
 * never announced (episodes are often delayed for weeks). Checked when the
 * app opens and every hour in the background (WorkManager, with network).
 */
internal object ReleaseReminders {
    /** Reminders at the estimated time (before 0.2.41), cancelled. */
    private const val TAG = "release"
    private const val CHECK_WORK = "release-check"
    /** The latest episode known per series and kind ("<path>#sub": 12). */
    private const val KEY_KNOWN = "releaseKnown"
    private const val CHANNEL = "releases"
    private const val KEY_ENABLED = "releaseNotifications"
    private const val KEY_PROMPTED = "releaseNotificationsPrompted"

    /** A series to open from a tapped reminder. */
    val opened = MutableStateFlow<Anime?>(null)

    private fun prefs(context: Context) = context.getSharedPreferences("relaycast", Context.MODE_PRIVATE)

    fun isEnabled(context: Context) = prefs(context).getBoolean(KEY_ENABLED, false)
    fun wasPrompted(context: Context) = prefs(context).getBoolean(KEY_PROMPTED, false)
    fun markPrompted(context: Context) = prefs(context).edit().putBoolean(KEY_PROMPTED, true).apply()

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).putBoolean(KEY_PROMPTED, true).apply()
    }

    fun hasPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** Checks now with the session's history and keeps the hourly background check. */
    suspend fun refresh(context: Context, session: Session?) {
        val work = WorkManager.getInstance(context)
        work.cancelAllWorkByTag(TAG)
        if (session == null || !isEnabled(context) || !hasPermission(context)) {
            work.cancelUniqueWork(CHECK_WORK)
            return
        }
        val periodic = PeriodicWorkRequestBuilder<ReleaseCheckWorker>(1, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        work.enqueueUniquePeriodicWork(CHECK_WORK, ExistingPeriodicWorkPolicy.KEEP, periodic)
        runCatching { check(context, session.client, session.history, session.preferences) }
    }

    /**
     * Notifies the series whose latest published episode, in the kind each
     * one is followed in, went past the last one known (and past the one
     * watched). A series seen for the first time is only remembered, so
     * what was already out is not announced.
     */
    suspend fun check(context: Context, client: RelayClient, entries: List<HistoryEntry>, preferences: Preferences) {
        if (!isEnabled(context) || !hasPermission(context)) return
        val followed = entries.take(50)
        if (followed.isEmpty()) return
        val statuses = client.seriesStatuses(followed.map { it.watchUrl })
        val known = JSONObject(prefs(context).getString(KEY_KNOWN, null) ?: "{}")
        for (entry in followed) {
            val path = catalogPath(entry.watchUrl)
            val status = statuses.firstOrNull { catalogPath(it.watchUrl) == path } ?: continue
            val kind = entry.serverKind ?: preferences.defaultTrackType
            val latest = status.latest(kind) ?: continue
            val key = "$path#$kind"
            val previous = if (known.has(key)) known.getInt(key) else null
            known.put(key, maxOf(previous ?: 0, latest))
            if (previous == null) continue
            val first = maxOf(previous, entry.episode) + 1
            if (latest < first) continue
            val title = if (preferences.titleLanguage == "jp") entry.titleJp ?: entry.seriesTitle else entry.seriesTitle
            val kindName = if (kind == "dub") "Dub" else "Sub"
            val body = if (first == latest) context.getString(R.string.release_available_one, latest, kindName)
                else context.getString(R.string.release_available_many, first, latest, kindName)
            notify(context, title, body, Anime(title = entry.seriesTitle, watchUrl = entry.watchUrl, imageUrl = entry.imageUrl, titleJp = entry.titleJp))
        }
        prefs(context).edit().putString(KEY_KNOWN, known.toString()).apply()
    }

    fun channel(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        context.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.releases_channel), NotificationManager.IMPORTANCE_DEFAULT))
    }

    /** Posts a reminder; tapping it opens the series. */
    fun notify(context: Context, title: String, body: String, anime: Anime) {
        if (!hasPermission(context)) return
        channel(context)
        val open = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(EXTRA_SERIES, RelayClient.json.encodeToString(Anime.serializer(), anime))
        val pending = PendingIntent.getActivity(context, anime.watchUrl.hashCode(), open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(title)
            .setContentText(body)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setGroup(catalogPath(anime.watchUrl))
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(anime.watchUrl.hashCode(), notification) }
    }

    const val EXTRA_SERIES = "net.silvara.relaycast.SERIES"
}

/** The hourly check; history and preferences come straight from the relay. */
class ReleaseCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val identity = PhoneIdentity(applicationContext)
        val relay = identity.relay() ?: return Result.success()
        val token = identity.token() ?: return Result.success()
        val client = RelayClient(relay, token)
        return runCatching {
            ReleaseReminders.check(applicationContext, client, client.history().entries, client.preferences())
        }.fold(onSuccess = { Result.success() }, onFailure = { Result.retry() })
    }
}

/**
 * A reminder at the estimated time, scheduled by a version before 0.2.41
 * and not cancelled yet: no longer shown, since it could announce a delayed
 * episode ([ReleaseCheckWorker] notifies released ones).
 */
class ReleaseWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = Result.success()
}
