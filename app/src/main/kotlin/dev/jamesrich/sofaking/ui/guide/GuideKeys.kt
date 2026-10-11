package dev.jamesrich.sofaking.ui.guide

import androidx.compose.ui.input.key.Key

/** Remote keys the guide answers wherever focus is. */
internal enum class GuideShortcut { NOW }

/** The Guide key, or Menu on remotes without one, jumps to the current time. */
internal fun Key.guideShortcut(): GuideShortcut? = when (this) {
    Key.Guide, Key.Menu -> GuideShortcut.NOW
    else -> null
}
