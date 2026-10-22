package dev.jamesrich.sofaking.ui

import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import kotlin.math.abs

/** How many frames a focus request waits for its node by default; a first guide frame on a slow TV needs more. */
internal const val FOCUS_ATTACH_FRAMES = 10
internal const val FIRST_FOCUS_FRAMES = 60

/**
 * Requests focus once the node is attached. A request before the modifier is placed is refused, not thrown,
 * so the Boolean overload is what tells a lazy item's row to try again next frame. True once focus landed.
 */
internal suspend fun requestFocusWhenAttached(request: FocusRequester, frames: Int = FOCUS_ATTACH_FRAMES): Boolean {
    repeat(frames) {
        withFrameNanos { }
        if (request.requestFocus(FocusDirection.Enter)) return true
    }
    return false
}

/**
 * Moves focus on an arrow key that nothing under this node took, as Android does with a D-pad's. Compose on the
 * desktop, the web and iOS moves focus only on Tab, so without this a keyboard's arrows could not leave the guide's
 * chips, reach the tabs or walk Settings. Off where the platform already does it ([platformMovesFocusOnArrows]).
 */
internal fun Modifier.arrowKeysMoveFocus(focusManager: FocusManager): Modifier =
    if (platformMovesFocusOnArrows) this else onKeyEvent { event ->
        val direction = event.key.arrowFocusDirection() ?: return@onKeyEvent false
        event.type == KeyEventType.KeyDown && focusManager.moveFocus(direction)
    }

internal fun Key.arrowFocusDirection(): FocusDirection? = when (this) {
    Key.DirectionUp -> FocusDirection.Up
    Key.DirectionDown -> FocusDirection.Down
    Key.DirectionLeft -> FocusDirection.Left
    Key.DirectionRight -> FocusDirection.Right
    else -> null
}

/**
 * Keeps a held OK from reaching what opens under it. A held OK opens the program details, which focus Watch while the
 * key is still down; its repeats and key-up would then press Watch and play the channel. [arm] when something opens on
 * a held OK, and the rest of that press, repeats and key-up, is swallowed.
 */
internal class HeldOkGuard {
    private var held = false
    private var swallowing = false

    /** OK went down; true when this key-down is a repeat of a press already swallowed. */
    fun onKeyDown(): Boolean {
        held = true
        return swallowing
    }

    /** OK came up; true when it ends a swallowed press. */
    fun onKeyUp(): Boolean {
        val swallowed = swallowing
        held = false
        swallowing = false
        return swallowed
    }

    fun arm() {
        if (held) swallowing = true
    }
}

/** The keys that press OK: the remote's centre, Enter and a keypad's Enter, as Compose's own clickable reads them. */
internal val Key.isOk: Boolean get() = this == Key.DirectionCenter || this == Key.Enter || this == Key.NumPadEnter

/**
 * [content] whose lists scroll on focus only as far as it takes to show what took focus [edge] clear of their edges. A
 * TV's default pivots the focused item a third of the way down, which on arrival pushes a page's title off the top.
 */
@Composable
internal fun ScrollOnlyWhenHidden(edge: Dp, content: @Composable () -> Unit) {
    val edgePx = with(LocalDensity.current) { edge.toPx() }
    val spec = remember(edgePx) { ScrollOnlyWhenHiddenSpec(edgePx) }
    CompositionLocalProvider(LocalBringIntoViewSpec provides spec, content = content)
}

private class ScrollOnlyWhenHiddenSpec(private val edge: Float) : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val leading = offset - edge
        val trailing = offset + size + edge
        return when {
            leading >= 0f && trailing <= containerSize -> 0f
            trailing - leading > containerSize -> leading
            abs(leading) < abs(trailing - containerSize) -> leading
            else -> trailing - containerSize
        }
    }
}
