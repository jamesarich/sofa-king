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
