package dev.jamesrich.sofaking.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp

/** Window widths below this are compact (a phone in portrait), as Material's window size classes define them. */
internal const val COMPACT_WIDTH_BELOW_DP = 600

/** Window heights below this are compact (a phone in landscape), as Material's window size classes define them. */
internal const val COMPACT_HEIGHT_BELOW_DP = 480

/**
 * How the window is laid out. A TV keeps the layout tuned for a 1080p set seen from a sofa, whatever its size
 * class: a TV and a landscape tablet share one. Everywhere else the window size decides: a compact width narrows
 * the guide's channel column, and a compact width or height drops the preview band so the grid keeps its rows.
 */
@Immutable
internal data class LayoutProfile(val tv: Boolean, val compactWidth: Boolean, val compactHeight: Boolean) {
    /** The guide shows the preview video beside the chips only where it leaves the grid room for its rows. */
    val showsPreviewBand: Boolean get() = !compactWidth && !compactHeight

    companion object {
        val Tv = LayoutProfile(tv = true, compactWidth = false, compactHeight = false)

        fun of(tv: Boolean, widthDp: Int, heightDp: Int): LayoutProfile =
            if (tv) Tv else LayoutProfile(tv = false, compactWidth = widthDp < COMPACT_WIDTH_BELOW_DP, compactHeight = heightDp < COMPACT_HEIGHT_BELOW_DP)
    }
}

internal val LocalLayoutProfile = staticCompositionLocalOf { LayoutProfile.Tv }

/** The profile for the current window, recomputed as the window is resized, rotated or folded. */
@Composable
internal fun rememberLayoutProfile(): LayoutProfile {
    val tv = rememberIsTelevision()
    val size = LocalWindowInfo.current.containerSize
    val density = LocalDensity.current
    return remember(tv, size, density) {
        with(density) { LayoutProfile.of(tv, size.width.toDp().value.toInt(), size.height.toDp().value.toInt()) }
    }
}

/** TV overscan, which a set may crop: 48dp at the sides, 27dp at the top and bottom. */
private val TvOverscan = WindowInsets(left = 48.dp, top = 27.dp, right = 48.dp, bottom = 27.dp)

/** Breathing room beyond the system bars and cutouts on a phone, tablet, desktop or browser. */
private val HandheldMargin = WindowInsets(left = 16.dp, top = 12.dp, right = 16.dp, bottom = 12.dp)

/**
 * The space a screen keeps clear at the window's edges. A TV keeps the overscan, or the system bars and cutouts
 * where they are larger, and lets its on-screen keyboard overlay the screen as it always has; anything else keeps
 * clear of the system bars, cutouts and keyboard, plus a small margin.
 */
@Composable
internal fun screenInsets(): WindowInsets =
    if (LocalLayoutProfile.current.tv) {
        WindowInsets.systemBars.union(WindowInsets.displayCutout).union(TvOverscan)
    } else {
        WindowInsets.safeDrawing.add(HandheldMargin)
    }

/** Pads [sides] of the content by [screenInsets]. */
@Composable
internal fun Modifier.screenPadding(sides: WindowInsetsSides = WindowInsetsSides.Horizontal + WindowInsetsSides.Vertical): Modifier =
    windowInsetsPadding(screenInsets().only(sides))

/** What drove the app last: the remote or a keyboard, or a finger or mouse. */
internal enum class InputKind { KEYS, POINTER }

/** The [InputKind] of the latest input anywhere in the app, observed by [trackLastInput]. */
@Stable
internal class LastInput(initial: InputKind) {
    var kind: InputKind by mutableStateOf(initial)
        internal set
}

internal val LocalLastInput = staticCompositionLocalOf { LastInput(InputKind.KEYS) }

/** Starts as the platform reports (a TV, or a window that starts out of touch mode, is key-driven). */
@Composable
internal fun rememberLastInput(): LastInput {
    val tv = rememberIsTelevision()
    val mode = LocalInputModeManager.current.inputMode
    return remember { LastInput(if (!tv && mode == InputMode.Touch) InputKind.POINTER else InputKind.KEYS) }
}

/**
 * Records every key event and every press or scroll of a finger or mouse inside this node into [last], before
 * the content handles it and without consuming it. Mouse moves alone do not count.
 */
internal fun Modifier.trackLastInput(last: LastInput): Modifier = this
    .onPreviewKeyEvent {
        last.kind = InputKind.KEYS
        false
    }
    .pointerInput(last) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.type == PointerEventType.Press || event.type == PointerEventType.Scroll) last.kind = InputKind.POINTER
            }
        }
    }
