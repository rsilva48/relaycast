package net.silvara.relaycast

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.SubtitleView
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * "Traducir subtítulos" (a setting of this device, off by default): the
 * subtitles in the device's language when the episode's are in another one.
 * iOS does this by itself (the system translates subtitles on the device);
 * Android uses ML Kit, as for the synopsis, so no text leaves the device.
 */
internal object SubtitleTranslationSetting {
    private const val KEY = "subtitles.translate"
    private fun storage(context: Context) = context.applicationContext.getSharedPreferences("relaycast", Context.MODE_PRIVATE)
    fun isEnabled(context: Context): Boolean = storage(context).getBoolean(KEY, false)
    fun setEnabled(context: Context, enabled: Boolean) = storage(context).edit().putBoolean(KEY, enabled).apply()
}

/**
 * Translates the cues of [player] into [subtitleView] (PlayerView's own, with
 * its style and position over the video). Attached after PlayerView, so in
 * each onCues it replaces the originals PlayerView has just set, in the same
 * callback: already translated lines never flicker. A new line shows nothing
 * for up to 150 ms while it translates, then the original if the translation
 * is late, then the translation. Bitmap subtitles pass through as they are.
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal class SubtitleTranslator(
    private val player: Player,
    private val subtitleView: SubtitleView,
    private val scope: CoroutineScope,
) : Player.Listener {
    /** True while a language model downloads (the first time): shown over the video. */
    var downloading by mutableStateOf(false)
        private set

    private val translators = HashMap<String, Translator>()
    private val downloaded = HashSet<String>()
    private val cache = HashMap<String, String>()
    private var generation = 0
    private var job: Job? = null

    fun attach() = player.addListener(this)

    fun release() {
        player.removeListener(this)
        job?.cancel()
        translators.values.forEach { it.close() }
        translators.clear()
    }

    override fun onCues(cueGroup: CueGroup) {
        // Same language (or unsupported): PlayerView's originals stay.
        val (source, target) = languagePair() ?: return
        val pair = "$source>$target"
        val cues = cueGroup.cues
        val current = ++generation
        job?.cancel()
        val ready = cues.map { translated(it, pair) }
        if (ready.all { it != null }) {
            subtitleView.setCues(ready.filterNotNull())
            return
        }
        subtitleView.setCues(emptyList())
        job = scope.launch {
            val late = launch {
                delay(150)
                if (current == generation) subtitleView.setCues(cues)
            }
            val translator = translator(source, target, pair) ?: run { late.cancel(); subtitleView.setCues(cues); return@launch }
            for (cue in cues) {
                val text = cue.text?.let(::plain) ?: continue
                val key = "$pair\u0000$text"
                if (key in cache) continue
                cache[key] = runCatching { translator.translate(text).await() }.getOrNull() ?: text
            }
            late.cancel()
            if (current == generation) subtitleView.setCues(cues.map { translated(it, pair) ?: it })
        }
    }

    /** The cue with its text translated, if it is in the cache (bitmap cues as they are). */
    private fun translated(cue: Cue, pair: String): Cue? {
        val text = cue.text ?: return cue
        val translation = cache["$pair\u0000${plain(text)}"] ?: return null
        return cue.buildUpon().setText(translation).build()
    }

    /** One sentence per cue (the view wraps it again): translates better than line by line. */
    private fun plain(text: CharSequence) = text.toString().replace('\n', ' ').replace(Regex(" +"), " ").trim()

    /** (source, target) in ML Kit codes, or null when there is nothing to translate. */
    private fun languagePair(): Pair<String, String>? {
        val target = TranslateLanguage.fromLanguageTag(Strings.locale.language) ?: return null
        val selected = player.currentTracks.groups.firstOrNull { it.type == C.TRACK_TYPE_TEXT && it.isSelected }
        val language = selected?.let { group -> (0 until group.length).firstOrNull { group.isTrackSelected(it) }?.let { group.getTrackFormat(it).language } }
        val source = language?.let { TranslateLanguage.fromLanguageTag(it.substringBefore('-')) } ?: TranslateLanguage.ENGLISH
        return if (source == target) null else source to target
    }

    private suspend fun translator(source: String, target: String, pair: String): Translator? {
        val translator = translators.getOrPut(pair) {
            Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(source).setTargetLanguage(target).build())
        }
        if (pair in downloaded) return translator
        // The note only if the download takes a moment (it is instant once the model is there).
        val note = scope.launch { delay(800); downloading = true }
        return try {
            translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
            downloaded += pair
            translator
        } catch (error: Exception) {
            null
        } finally {
            note.cancel()
            downloading = false
        }
    }
}
