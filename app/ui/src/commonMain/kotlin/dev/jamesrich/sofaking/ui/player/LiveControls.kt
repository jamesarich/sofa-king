package dev.jamesrich.sofaking.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import dev.jamesrich.sofaking.data.Channel
import dev.jamesrich.sofaking.data.PlaybackSessions
import dev.jamesrich.sofaking.data.Program
import dev.jamesrich.sofaking.data.RecordingMarks
import dev.jamesrich.sofaking.resources.Res
import dev.jamesrich.sofaking.resources.change_failed
import dev.jamesrich.sofaking.resources.player_recording
import dev.jamesrich.sofaking.resources.player_recording_canceled
import dev.jamesrich.sofaking.resources.player_zoom_notice
import dev.jamesrich.sofaking.ui.formatTemplate
import dev.jamesrich.sofaking.ui.requestFocusWhenAttached
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/** The texts of the corner notice: Record's answers and the zoom's name. */
@Immutable
internal data class NoticeTexts(
    val recording: String,
    val canceled: String,
    val failed: String,
    val zoom: String,
    val zoomLabels: Map<VideoZoom, String>,
)

/**
 * The short note at the top left for [NOTICE_TOAST_MS]: Record's answer once the server has taken the change or not,
 * or the new zoom.
 */
@Stable
internal class PlayerNotice(private val scope: CoroutineScope, private val texts: NoticeTexts) {
    var text by mutableStateOf<String?>(null)
    private var recordBusy by mutableStateOf(false)

    /**
     * Record on the bar for [program], which [onRecord] records or cancels; null where the server does not record
     * ([marks] is null) or nothing is on. A press while the last change is on its way does nothing. While the program
     * is being recorded, [onWatchFromStart] plays that recording from its start.
     */
    fun recordFor(
        program: Program?,
        marks: RecordingMarks?,
        onRecord: suspend (Program) -> Boolean,
        onWatchFromStart: ((recordingId: String) -> Unit)? = null,
    ): LiveRecord? {
        if (marks == null || program == null) return null
        val mark = marks.of(program)
        val recording = mark.recording
        val fromStart = mark.runningId?.let { id -> onWatchFromStart?.let { watch -> { watch(id) } } }
        return LiveRecord(recording, fromStart) {
            if (recordBusy) return@LiveRecord
            recordBusy = true
            scope.launch {
                try {
                    text = when {
                        !onRecord(program) -> texts.failed
                        recording -> formatTemplate(texts.canceled, program.title)
                        else -> formatTemplate(texts.recording, program.title)
                    }
                } finally {
                    recordBusy = false
                }
            }
        }
    }

    /** The picture now meets the screen as [zoom]. */
    fun zoomed(zoom: VideoZoom) {
        text = formatTemplate(texts.zoom, texts.zoomLabels.getValue(zoom))
    }
}

@Composable
internal fun rememberPlayerNotice(): PlayerNotice {
    val texts = NoticeTexts(
        recording = stringResource(Res.string.player_recording),
        canceled = stringResource(Res.string.player_recording_canceled),
        failed = stringResource(Res.string.change_failed),
        zoom = stringResource(Res.string.player_zoom_notice),
        zoomLabels = VideoZoom.entries.associateWith { stringResource(it.label) },
    )
    val scope = rememberCoroutineScope()
    val notice = remember(scope, texts) { PlayerNotice(scope, texts) }
    LaunchedEffect(notice.text) {
        if (notice.text == null) return@LaunchedEffect
        delay(NOTICE_TOAST_MS)
        notice.text = null
    }
    return notice
}

/** The player surface, which holds focus whenever no panel does, and the way into the info bar's buttons. */
@Stable
internal class PlayerFocus {
    val picture = FocusRequester()
    val buttons = FocusRequester()
}

@Composable
internal fun rememberPlayerFocus(state: PlayerState): PlayerFocus {
    val focus = remember { PlayerFocus() }
    // Focus returns to the player surface whenever a panel closes, including after its list is removed, and when the
    // bar leaves while one of its buttons has it.
    LaunchedEffect(state.miniGuideOpen, state.streamCardOpen, state.overlayVisible) {
        // Retried until the surface is placed: on entry it may not be yet, and a refused
        // request would leave nothing focused.
        val buttonsHoldFocus = state.overlayVisible && state.buttonsFocused
        if (!state.miniGuideOpen && !state.streamCardOpen && !buttonsHoldFocus) {
            requestFocusWhenAttached(focus.picture)
        }
    }
    return focus
}

/**
 * The live player's moving parts, which its effects, keys and panels share: its [state], the [player], the [tuner]
 * that loads it, the break screen's [cover], the surface's [focus] and the corner [notice].
 */
@Stable
internal class LiveParts(
    val state: PlayerState,
    val player: LivePlayer,
    val tuner: LiveTuner,
    val cover: BreakCover,
    val focus: PlayerFocus,
    val notice: PlayerNotice,
)

/** The parts for [state] on [player]; the break screen follows the tuned channel's breaks while [covers] is on. */
@Composable
internal fun rememberLiveParts(
    state: PlayerState,
    player: LivePlayer,
    sessions: PlaybackSessions,
    covers: Boolean,
): LiveParts {
    val tuner = rememberLiveTuner(player, sessions, state)
    val cover = rememberBreakCover(player, sessions, covers, state.tuned?.guideId)
    val focus = rememberPlayerFocus(state)
    val notice = rememberPlayerNotice()
    return remember(state, player, tuner, cover, focus, notice) {
        LiveParts(state, player, tuner, cover, focus, notice)
    }
}

/** Where the player's keys leave it, and what it saves: the guide, search, Back out, and the caption settings. */
internal class LiveExits(
    val onGuide: () -> Unit,
    val onSearch: () -> Unit,
    val onExit: () -> Unit,
    val onCaptionsChange: (Boolean) -> Unit,
    val onCaptionAdvanceChange: (Channel, Int) -> Unit,
)

/**
 * What the player's keys and buttons do beyond its own state. [channelAdvance] is the tuned channel's caption timing;
 * [load] tunes a channel again, [onRecord] presses Record for the program on now, and [onZoom] moves to the next
 * zoom.
 */
internal fun liveControls(
    parts: LiveParts,
    channelAdvance: Int,
    exits: LiveExits,
    load: (Channel) -> Unit,
    onRecord: () -> Unit,
    onZoom: () -> Unit,
): PlayerControls = object : PlayerControls {
    private val state = parts.state
    private val player = parts.player

    override fun guide() = exits.onGuide()

    override fun search() = exits.onSearch()

    override fun toggleCaptions() {
        val enabled = !state.captionsOn
        player.setCaptionsEnabled(enabled)
        state.captionsToggled(
            enabled,
            channelHasText = player.hasTextTrack,
            leadSupported = player.supportsCaptionLead,
            timingMs = channelAdvance,
        )
        exits.onCaptionsChange(enabled)
    }

    override fun adjustCaptionTiming(direction: Int) =
        state.adjustCaptionTiming(direction, channelAdvance, exits.onCaptionAdvanceChange)

    override fun playPause() = if (player.playWhenReady) player.pause() else player.play()

    override fun play() = player.play()

    override fun pause() = player.pause()

    override fun retry() {
        val channel = state.currentChannel ?: return
        state.clearFailure()
        load(channel)
    }

    override fun focusButtons() {
        parts.focus.buttons.requestFocus()
    }

    override fun focusPicture() {
        parts.focus.picture.requestFocus()
    }

    override fun record() = onRecord()

    override fun zoom() = onZoom()

    override fun stop() = exits.onExit()
}
