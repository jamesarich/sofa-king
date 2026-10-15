package dev.jamesrich.sofaking.ui.guide

import androidx.compose.ui.input.key.Key

/** Remote keys the guide answers wherever focus is. */
internal enum class GuideShortcut { NOW, SEARCH }

/** The Guide key, or Menu on remotes without one, jumps to the current time; Search opens search by voice. */
internal fun Key.guideShortcut(): GuideShortcut? = when (this) {
    Key.Guide, Key.Menu -> GuideShortcut.NOW
    Key.Search -> GuideShortcut.SEARCH
    else -> null
}

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
