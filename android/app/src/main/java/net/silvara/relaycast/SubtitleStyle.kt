package net.silvara.relaycast

import android.graphics.Color
import android.graphics.Typeface
import android.view.accessibility.CaptioningManager
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView

/**
 * The subtitles' look on the phone and the TV. As Google recommends, the
 * system caption style wins when the user turned it on (Settings ›
 * Accessibility › Captions). Otherwise, instead of Media3's white text on
 * black boxes: white medium-weight text with a dark outline and no box, a
 * little larger, which keeps more of the picture visible.
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal fun styleSubtitles(view: SubtitleView) {
    val captioning = view.context.getSystemService(CaptioningManager::class.java)
    if (captioning?.isEnabled == true) {
        view.setUserDefaultStyle()
        view.setUserDefaultTextSize()
        return
    }
    view.setStyle(
        CaptionStyleCompat(
            Color.WHITE,
            Color.TRANSPARENT,
            Color.TRANSPARENT,
            CaptionStyleCompat.EDGE_TYPE_OUTLINE,
            Color.BLACK,
            Typeface.create("sans-serif-medium", Typeface.NORMAL),
        ),
    )
    view.setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * 1.1f)
}
