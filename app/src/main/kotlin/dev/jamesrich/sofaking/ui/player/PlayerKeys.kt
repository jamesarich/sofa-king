package dev.jamesrich.sofaking.ui.player

import androidx.compose.ui.input.key.Key

internal fun Key.digitValue(): Int? = when (this) {
    Key.Zero, Key.NumPad0 -> 0
    Key.One, Key.NumPad1 -> 1
    Key.Two, Key.NumPad2 -> 2
    Key.Three, Key.NumPad3 -> 3
    Key.Four, Key.NumPad4 -> 4
    Key.Five, Key.NumPad5 -> 5
    Key.Six, Key.NumPad6 -> 6
    Key.Seven, Key.NumPad7 -> 7
    Key.Eight, Key.NumPad8 -> 8
    Key.Nine, Key.NumPad9 -> 9
    else -> null
}

/** A key that leaves the player for the guide, and where in the guide it lands. */
internal enum class PlayerExit { GUIDE_AT_NOW }

/** The Guide key, or Menu on remotes without one, opens the guide at the current time. */
internal fun Key.playerExit(): PlayerExit? = when (this) {
    Key.Guide, Key.Menu -> PlayerExit.GUIDE_AT_NOW
    else -> null
}
