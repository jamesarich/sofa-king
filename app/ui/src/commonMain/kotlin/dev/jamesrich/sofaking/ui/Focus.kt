package dev.jamesrich.sofaking.ui

import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key

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
