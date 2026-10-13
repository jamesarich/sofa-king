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

/** What a short OK press does in the player. */
internal enum class PlayerOk { COMMIT_NUMBER, RETRY, OPEN_CHANNEL_LIST, SHOW_INFO }

/** Typed digits commit first, then a failed stream retries; otherwise the info bar shows, and OK on it opens the channel list. */
internal fun playerOk(hasDigits: Boolean, failed: Boolean, infoVisible: Boolean): PlayerOk = when {
    hasDigits -> PlayerOk.COMMIT_NUMBER
    failed -> PlayerOk.RETRY
    infoVisible -> PlayerOk.OPEN_CHANNEL_LIST
    else -> PlayerOk.SHOW_INFO
}

/** A key that leaves the player for the guide, and where in the guide it lands. */
internal enum class PlayerExit { GUIDE_AT_NOW, SEARCH }

/** The Guide key, or Menu on remotes without one, opens the guide at the current time; Search opens its search by voice. */
internal fun Key.playerExit(): PlayerExit? = when (this) {
    Key.Guide, Key.Menu -> PlayerExit.GUIDE_AT_NOW
    Key.Search -> PlayerExit.SEARCH
    else -> null
}

/** What a tap or click on the video does. */
internal enum class PlayerTap { CLOSE_CHANNEL_LIST, HIDE_INFO, SHOW_INFO }

/** A tap closes the channel list first; otherwise it toggles the info bar, which carries the touch controls. */
internal fun playerTap(channelListOpen: Boolean, infoVisible: Boolean): PlayerTap = when {
    channelListOpen -> PlayerTap.CLOSE_CHANNEL_LIST
    infoVisible -> PlayerTap.HIDE_INFO
    else -> PlayerTap.SHOW_INFO
}
