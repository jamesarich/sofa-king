package dev.jamesrich.sofaking.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import dev.jamesrich.sofaking.data.AppSettings
import dev.jamesrich.sofaking.data.Channel
import dev.jamesrich.sofaking.data.ZapScope

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

/** Playing and stopping the stream, as the player's keys ask for it. */
internal interface PlayerPlayback {
    fun playPause()

    fun play()

    fun pause()

    /** A failed channel tunes again now, however many automatic retries have run. */
    fun retry()

    /** The Stop key: leaves the player, as Back does. */
    fun stop()
}

/** What the player's keys do beyond its own state: leaving for the guide, captions, the picture and the stream. */
internal interface PlayerControls : PlayerPlayback {
    /** The Guide key: the guide at Now. */
    fun guide()

    /** The Search key: the guide's search. */
    fun search()

    fun toggleCaptions()

    /** One step earlier (1, Right) or later (-1, Left) for the channel's caption timing. */
    fun adjustCaptionTiming(direction: Int)

    /** Focus moves into the info bar's buttons. */
    fun focusButtons()

    /** Focus moves back to the picture from the info bar's buttons. */
    fun focusPicture()

    /** The Record key: records the program on now, or cancels its recording, where the server records. */
    fun record()

    /** The Zoom key: the picture's next [VideoZoom]. */
    fun zoom()
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
    panels: PanelState = PanelState(keyLegendShown),
    ring: ChannelRing = ChannelRing(lineupChannels, startIndex, previousGuideId, zapScope, numbers, panels),
    captions: LiveCaptionState = LiveCaptionState(captionsOn) { ring.currentChannel },
) : ChannelZapping by ring,
    PlayerPanels by panels,
    LiveCaptions by captions {
    var pauseState by mutableStateOf<PauseStatus?>(null)
    var liveToast by mutableStateOf(false)

    // An auto-opened full screen lets the screen sleep (ambient mode) until someone presses a key or taps.
    var attended by mutableStateOf(attended)

    private val keys = PlayerKeyHandler(ring, panels, captions, numbers)

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
    ): Boolean = keys.onKeyEvent(key, type, controls, isLongPress, repeatCount, hasAudioTracks)
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
        PlayerState(
            lineupChannels,
            startIndex,
            previousGuideId,
            zapScope,
            captionsOn,
            attended,
            keyLegendShown,
            numbers,
        )
    }
}
