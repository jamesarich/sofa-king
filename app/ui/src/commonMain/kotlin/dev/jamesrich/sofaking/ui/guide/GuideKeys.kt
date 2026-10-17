package dev.jamesrich.sofaking.ui.guide

import androidx.compose.ui.input.key.Key

/** Remote keys the guide answers wherever focus is. */
internal enum class GuideShortcut { NOW, SEARCH, DETAILS, DAY_FORWARD, DAY_BACK }

/**
 * The Guide key, or Menu on remotes without one, jumps to the current time; Search opens search by voice; Info
 * opens the focused program's details; Fast Forward and Rewind move the grid a day ahead and back.
 */
internal fun Key.guideShortcut(): GuideShortcut? = when (this) {
    Key.Guide, Key.Menu -> GuideShortcut.NOW
    Key.Search -> GuideShortcut.SEARCH
    Key.Info -> GuideShortcut.DETAILS
    Key.MediaFastForward, Key.MediaSkipForward -> GuideShortcut.DAY_FORWARD
    Key.MediaRewind, Key.MediaSkipBackward -> GuideShortcut.DAY_BACK
    else -> null
}

/** Back, or Escape on a keyboard, closes the program details on a TV; the panel takes these keys itself. */
internal fun Key.closesDetails(): Boolean = this == Key.Back || this == Key.Escape

/** Rows one Channel or Page key moves in the grid; about one screenful on a 1080p TV. */
internal const val GUIDE_PAGE_ROWS = 4

/** A key that moves the grid's focused row by [rows] (negative is up); a [paged] move stops at the grid's ends. */
internal data class GridStep(val rows: Int, val paged: Boolean)

/** Up and Down step one row; Channel Up/Down and Page Up/Down move a page. */
internal fun Key.gridStep(): GridStep? = when (this) {
    Key.DirectionDown -> GridStep(1, paged = false)
    Key.DirectionUp -> GridStep(-1, paged = false)
    Key.ChannelDown, Key.PageDown -> GridStep(GUIDE_PAGE_ROWS, paged = true)
    Key.ChannelUp, Key.PageUp -> GridStep(-GUIDE_PAGE_ROWS, paged = true)
    else -> null
}

/** How many days ahead of the live window the grid can show; the server's guide data rarely reaches further. */
internal const val MAX_DAY_OFFSET = 6

/** The day offset a Fast Forward or Rewind lands on from [current]: one day either way, held within the window the app loads. */
internal fun dayOffsetAfter(current: Int, shortcut: GuideShortcut): Int = when (shortcut) {
    GuideShortcut.DAY_FORWARD -> (current + 1).coerceAtMost(MAX_DAY_OFFSET)
    GuideShortcut.DAY_BACK -> (current - 1).coerceAtLeast(0)
    else -> current
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
