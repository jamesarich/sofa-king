package dev.jamesrich.sofaking.ui.player

import androidx.compose.ui.input.key.Key

/** Each digit's key on the remote or the keyboard's top row and on the number pad, in digit order. */
private val DIGIT_KEYS = listOf(
    Key.Zero to Key.NumPad0,
    Key.One to Key.NumPad1,
    Key.Two to Key.NumPad2,
    Key.Three to Key.NumPad3,
    Key.Four to Key.NumPad4,
    Key.Five to Key.NumPad5,
    Key.Six to Key.NumPad6,
    Key.Seven to Key.NumPad7,
    Key.Eight to Key.NumPad8,
    Key.Nine to Key.NumPad9,
)

internal fun Key.digitValue(): Int? =
    DIGIT_KEYS.indexOfFirst { (row, pad) -> this == row || this == pad }.takeIf { it >= 0 }

/** What a short OK press does in the player. */
internal enum class PlayerOk { COMMIT_NUMBER, RETRY, OPEN_CHANNEL_LIST, SHOW_INFO }

/**
 * Typed digits commit first, then a failed stream retries; otherwise the info bar shows, and
 * OK on it opens the channel list.
 */
internal fun playerOk(hasDigits: Boolean, failed: Boolean, infoVisible: Boolean): PlayerOk = when {
    hasDigits -> PlayerOk.COMMIT_NUMBER
    failed -> PlayerOk.RETRY
    infoVisible -> PlayerOk.OPEN_CHANNEL_LIST
    else -> PlayerOk.SHOW_INFO
}

/** A key that leaves the player for the guide, and where in the guide it lands. */
internal enum class PlayerExit { GUIDE_AT_NOW, SEARCH }

/**
 * The Guide key, or Menu on remotes without one, opens the guide at the current time; Search opens its search by voice.
 */
internal fun Key.playerExit(): PlayerExit? = when (this) {
    Key.Guide, Key.Menu -> PlayerExit.GUIDE_AT_NOW
    Key.Search -> PlayerExit.SEARCH
    else -> null
}

/**
 * What a key does in the player once exits and digits are taken. [LEFT] and [RIGHT] go by [playerSideKey];
 * [TOGGLE_INFO] is a short Info press, since Info held opens the stream card.
 */
internal enum class PlayerAction {
    TOGGLE_CAPTIONS,
    ZAP_UP,
    ZAP_DOWN,
    CHANNEL_UP,
    CHANNEL_DOWN,
    LAST_CHANNEL,
    PLAY_PAUSE,
    PLAY,
    PAUSE,
    TOGGLE_INFO,
    LEFT,
    RIGHT,
    STREAM_INFO,
    RECORD,
    ZOOM,
    STOP,
}

/**
 * Up and Down zap the way they move the guide, Channel Up and Channel Down step the channel number, and so do Media
 * Next and Media Previous, as the system's next and previous do through the media session, and a gamepad's
 * shoulder buttons; Last Channel recalls the last channel. Audio Track opens the stream card, Record records the
 * program on now, Zoom steps the picture's zoom and Stop leaves the player.
 */
internal fun Key.playerAction(): PlayerAction? = when (this) {
    Key.Captions, Key.C -> PlayerAction.TOGGLE_CAPTIONS
    Key.DirectionUp -> PlayerAction.ZAP_UP
    Key.DirectionDown -> PlayerAction.ZAP_DOWN
    Key.ChannelUp, Key.MediaNext, Key.ButtonR1 -> PlayerAction.CHANNEL_UP
    Key.ChannelDown, Key.MediaPrevious, Key.ButtonL1 -> PlayerAction.CHANNEL_DOWN
    Key.LastChannel -> PlayerAction.LAST_CHANNEL
    Key.MediaPlayPause, Key.Spacebar, Key.K -> PlayerAction.PLAY_PAUSE
    Key.MediaPlay -> PlayerAction.PLAY
    Key.MediaPause -> PlayerAction.PAUSE
    Key.Info -> PlayerAction.TOGGLE_INFO
    Key.DirectionLeft -> PlayerAction.LEFT
    Key.DirectionRight -> PlayerAction.RIGHT
    Key.MediaAudioTrack -> PlayerAction.STREAM_INFO
    Key.MediaRecord -> PlayerAction.RECORD
    Key.TvZoomMode -> PlayerAction.ZOOM
    Key.MediaStop -> PlayerAction.STOP
    else -> null
}

/**
 * Channels a zap action moves through the lineup, which the guide lists by channel number. The D-pad follows the
 * guide: Up is the channel above the current one (negative), Down the channel below it (positive). Channel Up and
 * Channel Down say what they do: the next higher (positive) or lower (negative) channel number.
 */
internal fun PlayerAction.zapStep(): Int? = when (this) {
    PlayerAction.ZAP_UP, PlayerAction.CHANNEL_DOWN -> -1
    PlayerAction.ZAP_DOWN, PlayerAction.CHANNEL_UP -> 1
    else -> null
}

/** What Left or Right does in the player. */
internal enum class PlayerSideKey { ADJUST_CAPTION_TIMING, FOCUS_BUTTONS, SHOW_INFO }

/**
 * For a few seconds after the CC key, Left and Right move the channel's caption timing; on the info bar they move
 * into its buttons; otherwise they show the bar.
 */
internal fun playerSideKey(timingAdjustable: Boolean, infoVisible: Boolean): PlayerSideKey = when {
    timingAdjustable -> PlayerSideKey.ADJUST_CAPTION_TIMING
    infoVisible -> PlayerSideKey.FOCUS_BUTTONS
    else -> PlayerSideKey.SHOW_INFO
}

/** Where a key goes while the channel list is open. */
internal enum class MiniGuideKey { LIST, SWALLOWED, PLAYER }

/**
 * Up and Down move focus inside the panel, Left and Right move along its top row of filter chips and do nothing on a
 * channel row, and every other key acts as it does in the player.
 */
internal fun Key.inMiniGuide(chipsFocused: Boolean = false): MiniGuideKey = when (this) {
    Key.DirectionUp, Key.DirectionDown -> MiniGuideKey.LIST
    Key.DirectionLeft, Key.DirectionRight -> if (chipsFocused) MiniGuideKey.LIST else MiniGuideKey.SWALLOWED
    else -> MiniGuideKey.PLAYER
}

/** Where a key goes while the stream card is open. */
internal enum class StreamCardKey { CARD, CLOSE, PLAYER }

/**
 * Up and Down move between the card's audio tracks (OK picks one through the focused row), Left, Right, Back and Info
 * close it, and every other key closes it and acts as it does in the player.
 */
internal fun Key.inStreamCard(): StreamCardKey = when (this) {
    Key.DirectionUp, Key.DirectionDown -> StreamCardKey.CARD
    Key.DirectionLeft, Key.DirectionRight, Key.Back, Key.Info, Key.Escape -> StreamCardKey.CLOSE
    else -> StreamCardKey.PLAYER
}

/**
 * OK on the stream card: a focused audio track row takes it; with no tracks to pick, it closes the card as Back does.
 */
internal fun streamCardOk(hasTracks: Boolean): StreamCardKey =
    if (hasTracks) StreamCardKey.CARD else StreamCardKey.CLOSE

/** What a tap or click on the video does. */
internal enum class PlayerTap { CLOSE_CHANNEL_LIST, HIDE_INFO, SHOW_INFO }

/** A tap closes the channel list first; otherwise it toggles the info bar, which carries the buttons. */
internal fun playerTap(channelListOpen: Boolean, infoVisible: Boolean): PlayerTap = when {
    channelListOpen -> PlayerTap.CLOSE_CHANNEL_LIST
    infoVisible -> PlayerTap.HIDE_INFO
    else -> PlayerTap.SHOW_INFO
}
