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

/**
 * What a key does in the player once exits and digits are taken. [LEFT] and [RIGHT] adjust the caption lead while
 * captions and the info bar are on, and toggle the info bar otherwise; the screen decides which.
 */
internal enum class PlayerAction { TOGGLE_CAPTIONS, ZAP_UP, ZAP_DOWN, LAST_CHANNEL, PLAY_PAUSE, PLAY, PAUSE, TOGGLE_INFO, LEFT, RIGHT }

/** Up and Channel Up zap up, Down and Channel Down zap down; Media Previous and Last Channel recall the last channel. */
internal fun Key.playerAction(): PlayerAction? = when (this) {
    Key.Captions, Key.C -> PlayerAction.TOGGLE_CAPTIONS
    Key.DirectionUp, Key.ChannelUp -> PlayerAction.ZAP_UP
    Key.DirectionDown, Key.ChannelDown -> PlayerAction.ZAP_DOWN
    Key.MediaPrevious, Key.LastChannel -> PlayerAction.LAST_CHANNEL
    Key.MediaPlayPause -> PlayerAction.PLAY_PAUSE
    Key.MediaPlay -> PlayerAction.PLAY
    Key.MediaPause -> PlayerAction.PAUSE
    Key.Info -> PlayerAction.TOGGLE_INFO
    Key.DirectionLeft -> PlayerAction.LEFT
    Key.DirectionRight -> PlayerAction.RIGHT
    else -> null
}

/** Where a key goes while the channel list is open. */
internal enum class MiniGuideKey { LIST, SWALLOWED, PLAYER }

/** Up and Down move focus inside the list, Left and Right do nothing, and every other key acts as it does in the player. */
internal fun Key.inMiniGuide(): MiniGuideKey = when (this) {
    Key.DirectionUp, Key.DirectionDown -> MiniGuideKey.LIST
    Key.DirectionLeft, Key.DirectionRight -> MiniGuideKey.SWALLOWED
    else -> MiniGuideKey.PLAYER
}

/** What a tap or click on the video does. */
internal enum class PlayerTap { CLOSE_CHANNEL_LIST, HIDE_INFO, SHOW_INFO }

/** A tap closes the channel list first; otherwise it toggles the info bar, which carries the touch controls. */
internal fun playerTap(channelListOpen: Boolean, infoVisible: Boolean): PlayerTap = when {
    channelListOpen -> PlayerTap.CLOSE_CHANNEL_LIST
    infoVisible -> PlayerTap.HIDE_INFO
    else -> PlayerTap.SHOW_INFO
}
