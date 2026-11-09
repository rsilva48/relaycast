package net.silvara.relaycast

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.media.AudioManager
import android.provider.Settings
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.media3.ui.PlayerView
import kotlin.math.abs
import kotlin.math.roundToInt

/** The two levels the player adjusts with a vertical drag, as on the iPhone. */
internal enum class PlayerLevel { Brightness, Volume }

/**
 * Brightness of this window (not the system setting, restored on [restore])
 * and the media volume, for the player's gestures and side bars.
 */
internal class PlayerLevels(private val activity: Activity?, context: Context) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val maxVolume = (audio?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 1).coerceAtLeast(1)

    var brightness by mutableFloatStateOf(startingBrightness())
        private set
    var volume by mutableFloatStateOf(systemVolume())
        private set
    /** The bar shown while a gesture or a bar changes it; null when idle. */
    var adjusting by mutableStateOf<PlayerLevel?>(null)
    /** Bumped on every change, to hide the bar a moment after the last one. */
    var changes by mutableIntStateOf(0)
        private set

    private fun startingBrightness(): Float {
        val window = activity?.window?.attributes?.screenBrightness ?: -1f
        if (window >= 0f) return window
        val system = runCatching { Settings.System.getInt(activity?.contentResolver, Settings.System.SCREEN_BRIGHTNESS) }.getOrNull() ?: 128
        return (system / 255f).coerceIn(0f, 1f)
    }

    private fun systemVolume(): Float = (audio?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0) / maxVolume.toFloat()

    /** The volume can change with the phone's buttons; read again before showing it. */
    fun refresh() {
        volume = systemVolume()
    }

    fun level(of: PlayerLevel): Float = if (of == PlayerLevel.Brightness) brightness else volume

    fun set(of: PlayerLevel, value: Float) {
        val clamped = value.coerceIn(0f, 1f)
        if (of == PlayerLevel.Brightness) {
            brightness = clamped
            // Fully 0 turns some screens off; keep a glimmer.
            activity?.window?.let { window -> window.attributes = window.attributes.apply { screenBrightness = clamped.coerceAtLeast(0.01f) } }
        } else {
            volume = clamped
            // Flag 0: no system volume panel over the player's own bar.
            runCatching { audio?.setStreamVolume(AudioManager.STREAM_MUSIC, (clamped * maxVolume).roundToInt(), 0) }
        }
        adjusting = of
        changes++
    }

    fun restore() {
        activity?.window?.let { window -> window.attributes = window.attributes.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE } }
    }
}

/** What the player does with the touches on the video, set on every composition. */
internal class PlayerTouch {
    var enabled = true
    var onTap: () -> Unit = {}
    var onDoubleTap: (forward: Boolean) -> Unit = {}
    var onDragStart: (PlayerLevel) -> Unit = {}
    /** The drag's total vertical change since it started, as a share of the level (up is positive). */
    var onDrag: (PlayerLevel, Float) -> Unit = { _, _ -> }
    var onDragEnd: () -> Unit = {}
    var onPinch: (zoomIn: Boolean) -> Unit = {}
}

/**
 * The iPhone player's gestures on Media3's view: a tap shows or hides the
 * controls, a double tap on either half jumps 10 s, a vertical drag on the
 * left half changes the brightness and on the right half the volume, and a
 * pinch fills the screen or fits the whole picture. Touches on the
 * controller's own buttons and seek bar never reach here (they are child
 * views), so those keep working.
 */
@SuppressLint("ClickableViewAccessibility")
internal fun PlayerView.attachGestures(touch: PlayerTouch) {
    var drag: PlayerLevel? = null
    var ignoredDrag = false
    var pinching = false
    var pinchFactor = 1f
    val scale = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            pinching = true
            pinchFactor = 1f
            return true
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            pinchFactor *= detector.scaleFactor
            return true
        }

        override fun onScaleEnd(detector: ScaleGestureDetector) {
            if (abs(pinchFactor - 1f) > 0.08f) touch.onPinch(pinchFactor > 1f)
        }
    })
    val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            touch.onTap()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            touch.onDoubleTap(e.x > width / 2f)
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            if (e1 == null || pinching || e2.pointerCount > 1 || ignoredDrag) return false
            val dx = e2.x - e1.x
            val dy = e2.y - e1.y
            val level = drag ?: run {
                if (abs(dy) <= abs(dx)) {
                    ignoredDrag = true
                    return false
                }
                (if (e1.x < width / 2f) PlayerLevel.Brightness else PlayerLevel.Volume).also {
                    drag = it
                    touch.onDragStart(it)
                }
            }
            touch.onDrag(level, -dy / (height * 0.7f).coerceAtLeast(1f))
            return true
        }
    })
    setOnTouchListener { _, event ->
        if (!touch.enabled) return@setOnTouchListener false
        scale.onTouchEvent(event)
        if (!pinching) gestures.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            if (drag != null) touch.onDragEnd()
            drag = null
            ignoredDrag = false
            pinching = false
        }
        true
    }
}

/**
 * A level as a vertical glass bar (brightness on the left, volume on the
 * right), like the iPhone's. Tapping or dragging it sets the level.
 */
@Composable
internal fun LevelBar(icon: ImageVector, description: String, value: Float, onChange: (Float) -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier
            .width(44.dp)
            .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(22.dp))
            .padding(vertical = 10.dp)
            .semantics { contentDescription = description },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier
                .width(8.dp)
                .height(120.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Color.White.copy(alpha = 0.25f))
                .pointerInput(Unit) {
                    detectTapGestures { offset -> onChange(1f - offset.y / size.height) }
                }
                .pointerInput(Unit) {
                    detectVerticalDragGestures { change, _ -> onChange(1f - change.position.y / size.height) }
                },
            contentAlignment = Alignment.BottomCenter,
        ) {
            Box(Modifier.fillMaxWidth().fillMaxHeight(value.coerceIn(0f, 1f)).background(Color.White))
        }
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
    }
}

/** "« 20 s" on the left or "20 s »" on the right after double taps, accumulated. */
@Composable
internal fun SeekBubble(forward: Boolean, seconds: Int, modifier: Modifier = Modifier) {
    Box(modifier.size(96.dp).background(Color.Black.copy(alpha = 0.45f), CircleShape), contentAlignment = Alignment.Center) {
        Text(if (forward) "$seconds s »" else "« $seconds s", color = Color.White, style = MaterialTheme.typography.titleMedium)
    }
}
