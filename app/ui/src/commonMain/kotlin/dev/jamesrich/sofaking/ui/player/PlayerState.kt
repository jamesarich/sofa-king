package dev.jamesrich.sofaking.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import dev.jamesrich.sofaking.data.AppSettings
import dev.jamesrich.sofaking.data.Channel
import dev.jamesrich.sofaking.ui.isOk

/**
 * Caption toggle feedback; compared by identity so a repeated press restarts its timer. While [timingMs] is set,
 * Left and Right adjust the channel's caption timing and the toast shows where it is.
 */
internal class CaptionToast(val outcome: CaptionToggleOutcome, val timingMs: Int?)

/**
 * A stream that failed or ended; [attempt] counts the automatic reconnects made for it so far. [immediate]
 * marks a 410 from the relay, whose first reconnect re-reads the session without waiting for the backoff.
 */
internal data class Failure(val code: String, val attempt: Int, val immediate: Boolean = false)

/** The failure code of a live stream that ended. */
private const val STREAM_ENDED = "STREAM_ENDED"

/** What the player's keys do beyond its own state: leaving for the guide, captions and the stream. */
internal interface PlayerControls {
    /** The Guide key: the guide at Now. */
    fun guide()

    /** The Search key: the guide's search. */
    fun search()

    fun toggleCaptions()

    /** One step earlier (1, Right) or later (-1, Left) for the channel's caption timing. */
    fun adjustCaptionTiming(direction: Int)

    fun playPause()

    fun play()

    fun pause()

    /** A failed channel tunes again now, however many automatic retries have run. */
    fun retry()

    /** Focus moves into the info bar's buttons. */
    fun focusButtons()

    /** Focus moves back to the picture from the info bar's buttons. */
    fun focusPicture()
}

/**
 * The full-screen player's UI state and its moves, kept out of the composable so they can be tested: the ring of
 * channels and the row on it, the channel before it for Last, the stream's failure and reconnect, captions and
 * their timing, and which of the info bar, the channel list and the stream card shows. Keys reach it through
 * [onKeyEvent]; what they do to the stream goes through [PlayerControls].
 */
@Stable
internal class PlayerState(
    lineupChannels: List<Channel>,
    startIndex: Int,
    previousGuideId: String? = null,
    zapScope: ZapScope? = null,
    captionsOn: Boolean = false,
    attended: Boolean = true,
    keyLegendShown: Int = AppSettings.KEY_LEGEND_BARS,
    val numbers: ChannelNumberInput = ChannelNumberInput(),
) {
    val startChannel: Channel? = lineupChannels.getOrNull(startIndex)

    // Up, Down and the channel list stay in the guide's filter until a number typed outside it, or a channel
    // being followed after a re-import, widens them to the whole lineup.
    var widened by mutableStateOf(zapScope != null && startChannel?.guideId !in zapScope.guideIds)

    /** The whole lineup, as last composed. */
    var lineupChannels by mutableStateOf(lineupChannels)

    /** The channels Up and Down cycle through ([zapRing]), as last composed. */
    var channels by mutableStateOf(zapRing(lineupChannels, zapScope, widened))

    /** The row of [channels] on screen. */
    var index by mutableIntStateOf(channels.indexOfFirst { it.guideId == startChannel?.guideId }.coerceAtLeast(0))

    // What load() last tuned and what was watched before it, so a refreshed lineup can be followed by identity.
    var tuned by mutableStateOf<Channel?>(null)
    var previousChannel by mutableStateOf(lineupChannels.firstOrNull { it.guideId == previousGuideId })
        private set
    var previousIndex by mutableStateOf(channels.indexOfFirst { it.guideId == previousGuideId }.takeIf { it >= 0 })
        private set

    // A channel number typed outside the scope, tuned once the ring has widened.
    var pendingNumber by mutableStateOf<String?>(null)
        private set

    var failure by mutableStateOf<Failure?>(null)

    // The channel whose id the server no longer knows, while the lineup is reloaded to find it again.
    var recovering by mutableStateOf<Channel?>(null)

    // Seconds to the next automatic reconnect, 0 while it runs, null with none scheduled.
    var retryIn by mutableStateOf<Int?>(null)

    var captionsOn by mutableStateOf(captionsOn)
    var captionToast by mutableStateOf<CaptionToast?>(null)

    // Each channel's captioner runs a different distance behind; adjustments show before the store round-trips.
    var advanceOverrides by mutableStateOf<Map<String, Int>>(emptyMap())
        private set

    var overlayVisible by mutableStateOf(true)

    /** Bumped to keep the info bar up for another while. */
    var overlayRequest by mutableIntStateOf(0)
        private set

    var miniGuideOpen by mutableStateOf(false)

    // Bumped on every key while the mini-guide is open, so its idle timer restarts.
    var miniGuideActivity by mutableIntStateOf(0)
        private set

    // True while a chip of the channel list has focus, so Left and Right move along the chips.
    var miniGuideChipsFocused by mutableStateOf(false)

    // True while a button of the info bar has focus, so the D-pad moves along them instead of zapping.
    var buttonsFocused by mutableStateOf(false)

    var streamCardOpen by mutableStateOf(false)
    var streamCardActivity by mutableIntStateOf(0)
        private set
    var streamInfo by mutableStateOf(StreamInfo())
    var streamBufferedMs by mutableStateOf(0L)

    var pauseState by mutableStateOf<PauseStatus?>(null)
    var liveToast by mutableStateOf(false)

    // The key legend rides the first few info bars after install, counted once per bar.
    var legendCount by mutableIntStateOf(keyLegendShown)
        private set
    var legendOnThisBar by mutableStateOf(false)
        private set

    // An auto-opened full screen lets the screen sleep (ambient mode) until someone presses a key or taps.
    var attended by mutableStateOf(attended)

    // The press that closed a panel ends here: with the panel's focused row gone, its key-up can miss the
    // surface's key handler and arrive as a Back of its own.
    var swallowBackUp by mutableStateOf(false)
        private set

    private val centerGesture = CenterKeyGesture()
    private val infoGesture = CenterKeyGesture()

    val currentChannel: Channel? get() = channels.getOrNull(index)

    init {
        numbers.onCommit = ::commitEntry
    }

    fun zapTo(target: Int) {
        numbers.clear()
        failure = null
        recovering = null
        if (target != index) {
            previousIndex = index
            previousChannel = channels.getOrNull(index)
            index = target
        }
        showInfo()
        miniGuideOpen = false
    }

    /** Moves [step] channels through the lineup, wrapping at its ends; positive is down the guide, as [PlayerAction.zapStep] gives it. */
    fun zapStep(step: Int) {
        val size = channels.size
        if (size > 0) zapTo((index + step).mod(size))
    }

    /** Last-channel recall; with nothing watched before, it just shows the info overlay. */
    fun recallLastChannel() {
        val previous = previousIndex
        if (previous != null) zapTo(previous) else showInfo()
    }

    fun showInfo() {
        overlayVisible = true
        overlayRequest++
    }

    /** A touch control acted: the info bar stays up for another while. */
    fun keepInfo() {
        overlayRequest++
    }

    /** Typed digits name a channel in the ring, one outside it (the ring widens first), or no channel at all. */
    fun commitEntry(pending: ChannelNumberEntry) {
        val target = pending.indexIn(channels)
        val outside = if (target == null) pending.indexIn(lineupChannels)?.let { lineupChannels[it] } else null
        when {
            target != null -> zapTo(target)
            outside != null -> {
                pendingNumber = outside.guideId
                widened = true
            }
            else -> numbers.missed(pending.digits)
        }
    }

    /** A row of the channel list: a channel in the ring tunes at once, one outside it widens the ring first. */
    fun tuneChannel(channel: Channel) {
        val target = channels.indexOfFirst { it.guideId == channel.guideId }
        if (target >= 0) {
            zapTo(target)
        } else {
            miniGuideOpen = false
            pendingNumber = channel.guideId
            widened = true
        }
    }

    /**
     * The lineup refreshes every few hours and may reorder or grow; the tuned channel keeps its stream and
     * its row follows it. A channel that vanished stays playing on the nearest row. The same holds when the
     * ring widens from the guide's filter to the whole lineup; a number typed outside the filter is tuned then.
     */
    fun followRing(ring: List<Channel>) {
        if (recovering != null) return
        val current = tuned ?: startChannel
        if (current != null) {
            index = indexAfterRefresh(ring, current) ?: index.coerceIn(0, (ring.size - 1).coerceAtLeast(0))
            previousIndex = previousChannel?.let { indexAfterRefresh(ring, it) }
        }
        val number = pendingNumber ?: return
        pendingNumber = null
        ring.indexOfFirst { it.guideId == number }.takeIf { it >= 0 }?.let(::zapTo)
    }

    /** The stream's state moved on: playing clears a failure, and a live stream that ended is one. */
    fun onStatus(status: PlayerStatus) {
        if (status.isPlaying || status.state == PlaybackState.READY) failure = null
        // A live stream never ends; the server closed the connection, so treat it as a failure and reconnect.
        if (status.state == PlaybackState.ENDED) failure = Failure(STREAM_ENDED, attempt = failure?.attempt ?: 0)
    }

    /** The stream failed, or its session could not be opened; the reconnect keeps counting its attempts. */
    fun fail(code: String, immediate: Boolean = false) {
        failure = Failure(code, attempt = failure?.attempt ?: 0, immediate = immediate)
    }

    /** Clears the failure, so the channel tunes again from the first attempt. */
    fun clearFailure() {
        failure = null
        recovering = null
    }

    /** Captions were switched; the toast offers the timing for a few seconds where the engine applies one. */
    fun captionsToggled(enabled: Boolean, channelHasText: Boolean, leadSupported: Boolean, timingMs: Int) {
        captionsOn = enabled
        captionToast = CaptionToast(CaptionPolicy.toggleOutcome(enabled, channelHasText), timingMs.takeIf { enabled && leadSupported })
    }

    /** The caption timing of the channel on screen, as adjusted here or else as [stored] keeps it. */
    fun captionAdvance(stored: (Channel) -> Int): Int =
        currentChannel?.let { advanceOverrides[it.guideId] ?: stored(it) } ?: 0

    /** One step earlier (Right) or later (Left) from [advance] for this channel; the toast restarts its window at the new timing. */
    fun adjustCaptionTiming(direction: Int, advance: Int, onChanged: (Channel, Int) -> Unit) {
        val channel = currentChannel ?: return
        val toast = captionToast ?: return
        val next = CaptionPolicy.stepAdvance(advance, direction)
        advanceOverrides = advanceOverrides + (channel.guideId to next)
        onChanged(channel, next)
        captionToast = CaptionToast(toast.outcome, next)
    }

    fun openMiniGuide() {
        overlayVisible = false
        streamCardOpen = false
        miniGuideOpen = true
        miniGuideChipsFocused = false
        miniGuideActivity++
    }

    fun openStreamCard() {
        overlayVisible = false
        miniGuideOpen = false
        streamCardOpen = true
        streamCardActivity++
    }

    /** A track was picked on the stream card, which stays open for another while. */
    fun streamCardUsed() {
        streamCardActivity++
    }

    /** A new info bar shows: it carries the key legend for the first few bars; returns the count to save when it does. */
    fun legendForNewBar(): Int? {
        legendOnThisBar = legendCount < AppSettings.KEY_LEGEND_BARS
        return if (legendOnThisBar) ++legendCount else null
    }

    /** The system Back: an open panel closes; true when nothing was open and the player is left. */
    fun back(): Boolean {
        when {
            // The press that closed a panel ends here: with the panel's focused row gone, its key-up can miss the
            // surface's key handler and arrive as a Back of its own.
            swallowBackUp -> swallowBackUp = false
            streamCardOpen -> streamCardOpen = false
            miniGuideOpen -> miniGuideOpen = false
            else -> return true
        }
        return false
    }

    /** A tap or click on the video: the stream card or the channel list closes, or the info bar and its touch controls toggle. */
    fun tap() {
        if (streamCardOpen) {
            streamCardOpen = false
            return
        }
        when (playerTap(miniGuideOpen, overlayVisible)) {
            PlayerTap.CLOSE_CHANNEL_LIST -> miniGuideOpen = false
            PlayerTap.HIDE_INFO -> overlayVisible = false
            PlayerTap.SHOW_INFO -> showInfo()
        }
    }

    /**
     * A key event on the player surface, before the focused panel sees it; true when the player took it.
     * [hasAudioTracks] says whether the stream card lists tracks to pick.
     */
    fun onKeyEvent(
        key: Key,
        type: KeyEventType,
        controls: PlayerControls,
        isLongPress: Boolean = false,
        repeatCount: Int? = null,
        hasAudioTracks: Boolean = false,
    ): Boolean {
        if (buttonsFocused) {
            // Each key keeps the bar up for another while.
            overlayRequest++
            when (key.inButtonRow()) {
                ButtonRowKey.ROW -> return false
                // Up, Back and Escape hand focus back to the picture on key-up, so the key-up cannot act there too.
                ButtonRowKey.LEAVE -> {
                    if (type == KeyEventType.KeyUp) controls.focusPicture()
                    return true
                }
                ButtonRowKey.PLAYER -> controls.focusPicture()
            }
        }
        // The mini-guide's focused list swallows Back before BackHandler sees it, so close it here and
        // eat the matching key-up so the same press can't also leave the player.
        if (key == Key.Back) {
            if (type == KeyEventType.KeyDown && (miniGuideOpen || streamCardOpen)) {
                miniGuideOpen = false
                streamCardOpen = false
                swallowBackUp = true
                return true
            }
            if (type == KeyEventType.KeyUp && swallowBackUp) {
                swallowBackUp = false
                return true
            }
        }
        if (key == Key.Info) return onInfo(type, isLongPress, repeatCount)
        if (key.isOk) {
            if (streamCardOpen) {
                // The focused track row takes OK; with nothing to pick, OK closes the card on its key-up.
                if (streamCardOk(hasAudioTracks) == StreamCardKey.CARD) return false
                if (type == KeyEventType.KeyUp) streamCardOpen = false
                return true
            }
            return onCenter(type, isLongPress, repeatCount, controls)
        }
        if (type != KeyEventType.KeyDown || channels.isEmpty()) return false
        if (streamCardOpen) {
            streamCardActivity++
            return when (key.inStreamCard()) {
                // The focused track row handles OK.
                StreamCardKey.CARD -> false
                StreamCardKey.CLOSE -> {
                    streamCardOpen = false
                    true
                }
                StreamCardKey.PLAYER -> {
                    streamCardOpen = false
                    onKey(key, controls)
                }
            }
        }
        if (!miniGuideOpen) return onKey(key, controls)
        miniGuideActivity++
        return when (key.inMiniGuide(miniGuideChipsFocused)) {
            // The focused row or chip handles OK.
            MiniGuideKey.LIST -> false
            MiniGuideKey.SWALLOWED -> true
            MiniGuideKey.PLAYER -> onKey(key, controls)
        }
    }

    /** OK is judged on key-up: a short press acts as [playerOk] decides, a long press recalls the last channel. */
    private fun onCenter(type: KeyEventType, isLongPress: Boolean, repeatCount: Int?, controls: PlayerControls): Boolean {
        // With the mini-guide open, its focused row, chip or button takes OK; the press still counts against its idle timer.
        if (miniGuideOpen) {
            miniGuideActivity++
            return false
        }
        if (channels.isEmpty()) return false
        return when (type) {
            KeyEventType.KeyDown -> {
                if (centerGesture.onKeyDown(isLongPress, repeatCount) == CenterPress.LONG) recallLastChannel()
                true
            }
            KeyEventType.KeyUp -> {
                if (centerGesture.onKeyUp() == CenterPress.SHORT) {
                    when (playerOk(numbers.hasDigits, failure != null && currentChannel != null, overlayVisible)) {
                        PlayerOk.COMMIT_NUMBER -> numbers.commit()
                        PlayerOk.RETRY -> controls.retry()
                        PlayerOk.OPEN_CHANNEL_LIST -> openMiniGuide()
                        PlayerOk.SHOW_INFO -> showInfo()
                    }
                }
                true
            }
            else -> false
        }
    }

    private fun onKey(key: Key, controls: PlayerControls): Boolean {
        if (channels.isEmpty()) return false
        when (key.playerExit()) {
            PlayerExit.GUIDE_AT_NOW -> {
                controls.guide()
                return true
            }
            PlayerExit.SEARCH -> {
                controls.search()
                return true
            }
            null -> Unit
        }
        key.digitValue()?.let { digit ->
            numbers.append(digit)
            return true
        }
        val action = key.playerAction() ?: return false
        when (action) {
            PlayerAction.TOGGLE_CAPTIONS -> controls.toggleCaptions()
            PlayerAction.ZAP_UP, PlayerAction.ZAP_DOWN, PlayerAction.CHANNEL_UP, PlayerAction.CHANNEL_DOWN -> zapStep(action.zapStep()!!)
            PlayerAction.LAST_CHANNEL -> recallLastChannel()
            PlayerAction.PLAY_PAUSE -> controls.playPause()
            PlayerAction.PLAY -> controls.play()
            PlayerAction.PAUSE -> controls.pause()
            PlayerAction.TOGGLE_INFO -> if (!numbers.hasDigits) overlayVisible = !overlayVisible
            PlayerAction.LEFT, PlayerAction.RIGHT -> {
                val adjustable = captionToast?.timingMs != null && captionsOn
                when (playerSideKey(adjustable, overlayVisible)) {
                    // Right shows captions earlier, Left later.
                    PlayerSideKey.ADJUST_CAPTION_TIMING -> controls.adjustCaptionTiming(if (action == PlayerAction.RIGHT) 1 else -1)
                    PlayerSideKey.FOCUS_BUTTONS -> {
                        controls.focusButtons()
                        keepInfo()
                    }
                    PlayerSideKey.SHOW_INFO -> showInfo()
                }
            }
        }
        return true
    }

    /** Info is judged on key-up: a short press toggles the info bar, a long press opens the stream card. */
    private fun onInfo(type: KeyEventType, isLongPress: Boolean, repeatCount: Int?): Boolean {
        when (type) {
            KeyEventType.KeyDown -> if (infoGesture.onKeyDown(isLongPress, repeatCount) == CenterPress.LONG) openStreamCard()
            KeyEventType.KeyUp -> if (infoGesture.onKeyUp() == CenterPress.SHORT) {
                when {
                    streamCardOpen -> streamCardOpen = false
                    miniGuideOpen -> {
                        miniGuideOpen = false
                        showInfo()
                    }
                    !numbers.hasDigits -> overlayVisible = !overlayVisible
                }
            }
            else -> return false
        }
        return true
    }
}

/** A [PlayerState] for the player opened on [startIndex] of [lineupChannels], kept while the player is on screen. */
@Composable
internal fun rememberPlayerState(
    lineupChannels: List<Channel>,
    startIndex: Int,
    previousGuideId: String?,
    zapScope: ZapScope?,
    captionsOn: Boolean,
    attended: Boolean,
    keyLegendShown: Int,
): PlayerState {
    val numbers = rememberChannelNumberInput()
    return remember {
        PlayerState(lineupChannels, startIndex, previousGuideId, zapScope, captionsOn, attended, keyLegendShown, numbers)
    }
}
