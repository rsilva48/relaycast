package net.silvara.relaycast

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions

/**
 * The catalog's English synopsis in the phone's language, translated on the
 * phone with ML Kit (no text leaves it). The language model, about 30 MB,
 * downloads the first time. Null when the language is not supported or the
 * model cannot be downloaded.
 */
internal suspend fun translateSynopsis(text: String): String? {
    val target = TranslateLanguage.fromLanguageTag(Strings.locale.language) ?: return null
    if (target == TranslateLanguage.ENGLISH) return null
    val translator = Translation.getClient(
        TranslatorOptions.Builder().setSourceLanguage(TranslateLanguage.ENGLISH).setTargetLanguage(target).build(),
    )
    return try {
        translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
        translator.translate(text).await()
    } catch (error: Exception) {
        null
    } finally {
        translator.close()
    }
}
