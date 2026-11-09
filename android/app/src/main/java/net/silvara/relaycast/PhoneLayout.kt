package net.silvara.relaycast

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.adaptive.allVerticalHingeBounds
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.material3.adaptive.separatingHorizontalHingeBounds
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.window.core.layout.WindowSizeClass

/**
 * How the phone app uses the current window (Jetpack WindowManager through
 * Material 3 adaptive): compact windows keep the bottom bar and one pane;
 * from the medium width class (600 dp: unfolded foldables, tablets, phones
 * in landscape, split screen) a navigation rail, and the list beside the
 * series when the height is medium too (not a phone in landscape). Hinge
 * bounds are in window pixels; devices without a fold report none, so older
 * phones keep the compact layout.
 */
internal data class PhoneLayout(
    val rail: Boolean,
    val twoPane: Boolean,
    /** A vertical fold to split the two panes at (flat or half-opened). */
    val verticalHinge: Rect?,
    /** Tabletop posture: the horizontal fold between the screen's halves. */
    val tabletopHinge: Rect?,
)

@Composable
internal fun rememberPhoneLayout(): PhoneLayout {
    val info = currentWindowAdaptiveInfoV2()
    // The size from the configuration, which changes together with the
    // rotation. The window size the adaptive info reports can lag one
    // rotation behind on some phones (Android 11): landscape kept the bottom
    // bar and portrait kept the rail. The posture still comes from it.
    val configuration = LocalConfiguration.current
    val wide = configuration.screenWidthDp >= WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND
    // A phone in landscape is wide but short: it keeps one pane (with the
    // rail, which saves height); two panes need a medium height too.
    val tall = configuration.screenHeightDp >= WindowSizeClass.HEIGHT_DP_MEDIUM_LOWER_BOUND
    val posture = info.windowPosture
    val verticalHinge = posture.allVerticalHingeBounds.firstOrNull()
    val tabletopHinge = posture.separatingHorizontalHingeBounds.firstOrNull()?.takeIf { posture.isTabletop }
    return PhoneLayout(rail = wide, twoPane = wide && tall, verticalHinge = verticalHinge, tabletopHinge = tabletopHinge)
}

/**
 * The list and the series side by side. With a vertical fold inside this
 * area the panes split exactly at it, so the hinge never covers content;
 * otherwise the list takes about 40 % of the width.
 */
@Composable
internal fun TwoPane(hinge: Rect?, first: @Composable () -> Unit, second: @Composable () -> Unit) {
    val density = LocalDensity.current
    // Where this area starts in the window (the rail is to its left).
    var origin by remember { mutableFloatStateOf(0f) }
    BoxWithConstraints(Modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInWindow().x }) {
        val total = constraints.maxWidth.toFloat()
        val minimum = with(density) { 280.dp.toPx() }
        val fold = hinge?.let { (it.left - origin) to (it.right - origin) }
            ?.takeIf { (left, right) -> left >= minimum && total - right >= minimum }
        val listWidth = fold?.first ?: (total * 0.4f).coerceIn(minOf(minimum, total / 2), maxOf(minimum, total - with(density) { 360.dp.toPx() }))
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.width(with(density) { listWidth.toDp() }).fillMaxHeight()) { first() }
            if (fold != null) {
                Spacer(Modifier.width(with(density) { (fold.second - fold.first).coerceAtLeast(0f).toDp() }))
            } else {
                VerticalDivider()
            }
            Box(Modifier.weight(1f).fillMaxHeight()) { second() }
        }
    }
}
