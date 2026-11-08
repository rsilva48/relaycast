package net.silvara.relaycast

import android.app.Application
import android.content.Context
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import java.util.Locale

/** Starts what every part of the app needs, before any activity, service or worker. */
class RelaycastApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Strings.init(this)
    }
}

/**
 * Text for code without a composable or a Context at hand (errors, workers,
 * notifications), in the app's language: Spanish on Spanish devices, English
 * otherwise (or the language chosen for the app in the system settings).
 */
internal object Strings {
    private lateinit var context: Context

    fun init(app: Context) {
        context = app.applicationContext
    }

    fun get(@StringRes id: Int, vararg args: Any): String = context.getString(id, *args)

    fun plural(@PluralsRes id: Int, count: Int, vararg args: Any): String = context.resources.getQuantityString(id, count, *args)

    /** The app's language, for dates and language names. */
    val locale: Locale get() = context.resources.configuration.locales[0]

    /** The app shows Spanish; catalog labels from the relay then use `label`, else `labelEn`. */
    val spanish: Boolean get() = locale.language == "es"
}
