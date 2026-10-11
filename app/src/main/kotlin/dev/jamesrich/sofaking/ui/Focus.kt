package dev.jamesrich.sofaking.ui

import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester

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
