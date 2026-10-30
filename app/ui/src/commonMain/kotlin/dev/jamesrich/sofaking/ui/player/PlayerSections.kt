package dev.jamesrich.sofaking.ui.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jamesrich.sofaking.data.Channel
import dev.jamesrich.sofaking.data.Lineup
import dev.jamesrich.sofaking.data.PlaybackSessions
import dev.jamesrich.sofaking.data.StreamHealth
import dev.jamesrich.sofaking.data.ZapScope
import dev.jamesrich.sofaking.data.runCatchingCancellable
import dev.jamesrich.sofaking.resources.Res
import dev.jamesrich.sofaking.resources.guide_details_add_favorite
import dev.jamesrich.sofaking.resources.guide_details_cancel_recording
import dev.jamesrich.sofaking.resources.guide_details_record
import dev.jamesrich.sofaking.resources.guide_details_remove_favorite
import dev.jamesrich.sofaking.resources.player_control_channel_down
import dev.jamesrich.sofaking.resources.player_control_channel_up
import dev.jamesrich.sofaking.resources.player_control_channels
import dev.jamesrich.sofaking.resources.player_control_from_start
import dev.jamesrich.sofaking.resources.player_control_guide
import dev.jamesrich.sofaking.resources.player_control_multiview
import dev.jamesrich.sofaking.resources.player_control_previous
import dev.jamesrich.sofaking.resources.player_live
import dev.jamesrich.sofaking.resources.retry
import dev.jamesrich.sofaking.resources.tuning
import dev.jamesrich.sofaking.resources.zap_scope
import dev.jamesrich.sofaking.ui.PopIn
import dev.jamesrich.sofaking.ui.PopOut
import dev.jamesrich.sofaking.ui.RiseIn
import dev.jamesrich.sofaking.ui.rememberSettled
import dev.jamesrich.sofaking.ui.screenPadding
import dev.jamesrich.sofaking.ui.theme.SofaIcons
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

private val HEALTH_READ_INTERVAL = 5.seconds

/**
 * Status pills stack top left so a caption toast never covers "Paused" or "Tuning"; a finger or a mouse gets the Back
 * button above them while the bar shows ([onBack]). [notice] is a short answer to a button, such as Record's.
 */
@Composable
internal fun BoxScope.StatusPills(
    state: PlayerState,
    buffering: Boolean,
    paused: Boolean,
    notice: String?,
    onBack: (() -> Unit)?,
) {
    val popIn = PopIn
    val popOut = PopOut
    Column(
        Modifier.align(Alignment.TopStart).screenPadding(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AnimatedVisibility(onBack != null, enter = popIn, exit = popOut) {
            if (onBack != null) PlayerBackControl(onBack)
        }
        AnimatedVisibility(rememberSettled(buffering && state.failure == null), enter = popIn, exit = popOut) {
            StatusPill(stringResource(Res.string.tuning), busy = true)
        }
        AnimatedVisibility(paused && state.failure == null, enter = popIn, exit = popOut) {
            PausePill(state.pauseState)
        }
        AnimatedVisibility(state.liveToast && !paused, enter = popIn, exit = popOut) {
            StatusPill(stringResource(Res.string.player_live), MaterialTheme.typography.titleLarge)
        }
        AnimatedContent(
            targetState = state.captionToast,
            transitionSpec = { popIn togetherWith popOut },
            label = "captionToast",
        ) { toast ->
            if (toast != null) CaptionToastPill(toast.outcome, toast.timingMs)
        }
        AnimatedContent(targetState = notice, transitionSpec = {
            popIn togetherWith popOut
        }, label = "notice") { text ->
            if (text != null) StatusPill(text)
        }
        VolumeToast()
    }
}

/** The stream card, on Info held: its figures are read again every second while it is open. */
@Composable
internal fun StreamCardPanel(
    state: PlayerState,
    player: LivePlayer,
    sessions: PlaybackSessions,
    audioTracks: List<AudioTrack>,
) {
    val heldStream by sessions.current.collectAsStateWithLifecycle()
    var health by remember { mutableStateOf<StreamHealth?>(null) }
    // The server's view of the session moves slower than the engine's, so it is read every few seconds.
    LaunchedEffect(state.streamCardOpen, heldStream?.sessionId) {
        health = null
        while (state.streamCardOpen) {
            health = runCatchingCancellable { sessions.health() }.getOrNull()
            delay(HEALTH_READ_INTERVAL)
        }
    }
    AnimatedVisibility(state.streamCardOpen, enter = PopIn, exit = ExitTransition.None) {
        Box(Modifier.fillMaxSize()) {
            StreamCard(
                tracks = audioTracks,
                info = state.streamInfo,
                stream = heldStream,
                bufferedMs = state.streamBufferedMs,
                onSelectTrack = { id ->
                    player.selectAudioTrack(id)
                    state.streamCardUsed()
                },
                health = health,
            )
        }
    }
}

/**
 * The channel list arrives on a spring but leaves at once: while it animated out it would still hold focus, and a
 * quick second Back or Down would land in the list instead of the player.
 */
@Composable
internal fun ChannelListPanel(
    state: PlayerState,
    lineup: Lineup,
    channel: Channel,
    scope: ZapScope?,
    favorites: Set<String>,
    favoritesOrder: List<String>,
    recent: List<String>,
    now: Instant,
) {
    AnimatedVisibility(state.miniGuideOpen, enter = RiseIn, exit = ExitTransition.None) {
        Box(Modifier.fillMaxSize()) {
            MiniGuide(
                lineup = lineup,
                scope = scope,
                favorites = favorites,
                favoritesOrder = favoritesOrder,
                recent = recent,
                currentGuideId = channel.guideId,
                previousGuideId = state.previousChannel?.guideId,
                now = now,
                onTune = state::tuneChannel,
                onChipsFocus = { state.miniGuideChipsFocused = it },
            )
        }
    }
}

/**
 * A live program to record from the bar: whether it is [recording] already, [fromStart], which plays the recording
 * being written from the program's start (null when none is), and the press that records or cancels.
 */
internal class LiveRecord(val recording: Boolean, val fromStart: (() -> Unit)? = null, val onClick: () -> Unit)

/**
 * The info bar over the bottom of the picture with its buttons ([liveButtons]): the D-pad reaches them with Left or
 * Right ([entry] takes the first), and a finger or a mouse gets channel down, play or pause and channel up before
 * them, then the volume and full screen. [record] is null where the server does not record or nothing is on;
 * [favorite] is null where the star cannot change. The [sleep] timer offers [programEnd], the end of the program on
 * now, after its minutes.
 */
@Composable
internal fun BoxScope.InfoBarPanel(
    state: PlayerState,
    lineup: Lineup,
    channel: Channel,
    now: Instant,
    scope: ZapScope?,
    ringSize: Int,
    captionAdvanceMs: Int?,
    pointerDriven: Boolean,
    paused: Boolean,
    hasAudioChoice: Boolean,
    record: LiveRecord?,
    sleep: SleepTimer,
    programEnd: Instant?,
    zoom: VideoZoom,
    favorite: Boolean?,
    onToggleFavorite: () -> Unit,
    controls: PlayerControls,
    onGuide: () -> Unit,
    focus: FocusRequester,
    entry: FocusRequester,
    onMultiview: (() -> Unit)? = null,
) {
    val bar = BarActions(state, focus, pointerDriven)
    val buttons = LiveBarButtons(
        state,
        controls,
        bar,
        paused,
        hasAudioChoice,
        zoom,
        sleep,
        programEnd,
        record,
        favorite,
        onToggleFavorite,
        onGuide,
        onMultiview,
    )
    val items = liveButtons(
        pointerDriven,
        canRecord = record != null,
        canFavorite = favorite != null,
        hasPrevious = state.previousIndex != null,
        canWatchFromStart = record?.fromStart != null,
        canMultiview = onMultiview != null,
    ).map { buttons.item(it) }

    InfoBar(state.overlayVisible && !state.miniGuideOpen && !state.streamCardOpen) {
        val scopeLabel = scope?.let { stringResource(Res.string.zap_scope, it.label, state.index + 1, ringSize) }
        InfoOverlay(
            lineup = lineup,
            channel = channel,
            now = now,
            captionsOn = state.captionsOn,
            captionAdvanceMs = captionAdvanceMs,
            scope = scopeLabel,
            showKeyLegend = state.legendOnThisBar && !pointerDriven,
        )
        if (pointerDriven) {
            PointerControls(state, controls, bar, paused) {
                PlayerButtonRow(items, entry, onFocus = { state.buttonsFocused = it }, showLabel = false)
            }
        } else {
            PlayerButtonRow(items, entry, onFocus = { state.buttonsFocused = it }, showLabel = true)
        }
    }
}

/** Wraps the bar's button actions, so the bar stays up for another while after each. */
private class BarActions(
    private val state: PlayerState,
    private val focus: FocusRequester,
    private val pointerDriven: Boolean,
) {
    /** [action], then the bar stays up; [toPicture] hands focus back to the picture. */
    fun control(toPicture: Boolean = pointerDriven, action: () -> Unit): () -> Unit = {
        action()
        state.keepInfo()
        if (toPicture) focus.requestFocus()
    }
}

/** The info bar's buttons, each drawn from what the player shows now. */
private class LiveBarButtons(
    private val state: PlayerState,
    private val controls: PlayerControls,
    private val bar: BarActions,
    private val paused: Boolean,
    private val hasAudioChoice: Boolean,
    private val zoom: VideoZoom,
    private val sleep: SleepTimer,
    private val programEnd: Instant?,
    private val record: LiveRecord?,
    private val favorite: Boolean?,
    private val onToggleFavorite: () -> Unit,
    private val onGuide: () -> Unit,
    private val onMultiview: (() -> Unit)?,
) {
    @Composable
    fun item(button: LiveButton): PlayerButtonItem = when (button) {
        LiveButton.CHANNELS -> PlayerButtonItem(
            Icons.AutoMirrored.Filled.List,
            stringResource(Res.string.player_control_channels),
            state::openMiniGuide,
        )

        LiveButton.PREVIOUS -> PlayerButtonItem(
            SofaIcons.SwapHoriz,
            stringResource(Res.string.player_control_previous),
            bar.control(toPicture = true) { state.recallLastChannel() },
        )

        LiveButton.PLAY_PAUSE -> playPauseButtonItem(
            paused,
            state.failure != null,
            bar.control { controls.playPause() },
            bar.control { controls.retry() },
        )

        // Captions hand the D-pad back to the picture, so Left and Right set the timing while the toast shows.
        LiveButton.CAPTIONS -> captionsButtonItem(
            state.captionsOn,
            bar.control(toPicture = true) { controls.toggleCaptions() },
        )

        LiveButton.STREAM_INFO -> streamInfoButtonItem(hasAudioChoice, state::openStreamCard)

        LiveButton.ZOOM -> zoomButtonItem(zoom, bar.control { controls.zoom() })

        LiveButton.SLEEP -> sleepButtonItem(sleep) {
            sleep.step(Clock.System.now().toEpochMilliseconds(), programEndMs = programEnd?.toEpochMilliseconds())
            state.keepInfo()
        }

        LiveButton.FROM_START -> PlayerButtonItem(
            SofaIcons.Replay,
            stringResource(Res.string.player_control_from_start),
            bar.control(toPicture = true) { record?.fromStart?.invoke() },
        )

        LiveButton.RECORD -> record()

        LiveButton.FAVORITE -> favorite()

        LiveButton.MULTIVIEW -> PlayerButtonItem(
            SofaIcons.Dashboard,
            stringResource(Res.string.player_control_multiview),
            { onMultiview?.invoke() },
        )

        LiveButton.GUIDE -> PlayerButtonItem(
            SofaIcons.GridView,
            stringResource(Res.string.player_control_guide),
            onGuide,
        )
    }

    @Composable
    private fun record(): PlayerButtonItem {
        val recording = record?.recording == true
        return PlayerButtonItem(
            if (recording) SofaIcons.Cancel else SofaIcons.Record,
            stringResource(
                if (recording) Res.string.guide_details_cancel_recording else Res.string.guide_details_record,
            ),
            bar.control { record?.onClick?.invoke() },
        )
    }

    @Composable
    private fun favorite(): PlayerButtonItem {
        val on = favorite == true
        return PlayerButtonItem(
            if (on) Icons.Filled.Star else Icons.Outlined.Star,
            stringResource(if (on) Res.string.guide_details_remove_favorite else Res.string.guide_details_add_favorite),
            bar.control(action = onToggleFavorite),
        )
    }
}

/**
 * The bar's controls for a finger or a mouse: channel down, play or pause, channel up, then the [buttons], the
 * volume and full screen.
 */
@Composable
private fun PointerControls(
    state: PlayerState,
    controls: PlayerControls,
    bar: BarActions,
    paused: Boolean,
    buttons: @Composable () -> Unit,
) {
    PointerControlRow(
        start = PlayerButtonItem(
            Icons.Filled.KeyboardArrowDown,
            stringResource(Res.string.player_control_channel_down),
            bar.control { PlayerAction.CHANNEL_DOWN.zapStep()?.let(state::zapStep) },
        ),
        primary = playPauseButtonItem(
            paused,
            state.failure != null,
            bar.control { controls.playPause() },
            bar.control { controls.retry() },
        ),
        end = PlayerButtonItem(
            Icons.Filled.KeyboardArrowUp,
            stringResource(Res.string.player_control_channel_up),
            bar.control { PlayerAction.CHANNEL_UP.zapStep()?.let(state::zapStep) },
        ),
        onVolumeAdjust = state::keepInfo,
        onVolumeFinish = bar.control {},
        buttons = buttons,
    )
}
