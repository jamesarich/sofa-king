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
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jamesrich.sofaking.data.Channel
import dev.jamesrich.sofaking.data.Lineup
import dev.jamesrich.sofaking.data.PlaybackSessions
import dev.jamesrich.sofaking.resources.*
import dev.jamesrich.sofaking.ui.PopIn
import dev.jamesrich.sofaking.ui.PopOut
import dev.jamesrich.sofaking.ui.RiseIn
import dev.jamesrich.sofaking.ui.rememberSettled
import dev.jamesrich.sofaking.ui.screenPadding
import dev.jamesrich.sofaking.ui.theme.SofaIcons
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Status pills stack top left so a caption toast never covers "Paused" or "Tuning"; a finger or a mouse gets the Back
 * button above them while the bar shows ([onBack]). [notice] is a short answer to a button, such as Record's.
 */
@Composable
internal fun BoxScope.StatusPills(state: PlayerState, buffering: Boolean, paused: Boolean, notice: String?, onBack: (() -> Unit)?) {
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
        AnimatedContent(targetState = notice, transitionSpec = { popIn togetherWith popOut }, label = "notice") { text ->
            if (text != null) StatusPill(text)
        }
        VolumeToast()
    }
}

/** The stream card, on Info held: its figures are read again every second while it is open. */
@Composable
internal fun StreamCardPanel(state: PlayerState, player: LivePlayer, sessions: PlaybackSessions, audioTracks: List<AudioTrack>) {
    val heldStream by sessions.current.collectAsStateWithLifecycle()
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
                onChipsFocused = { state.miniGuideChipsFocused = it },
            )
        }
    }
}

/** A live program to record from the bar: whether it is [recording] already, and the press that changes that. */
internal class LiveRecord(val recording: Boolean, val onClick: () -> Unit)

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
) {
    /** A button's action, after which the bar stays up for another while; [toPicture] hands focus back to the picture. */
    fun control(toPicture: Boolean = pointerDriven, action: () -> Unit): () -> Unit = {
        action()
        state.keepInfo()
        if (toPicture) focus.requestFocus()
    }

    val failed = state.failure != null
    val items = liveButtons(pointerDriven, canRecord = record != null, canFavorite = favorite != null, hasPrevious = state.previousIndex != null).map { button ->
        when (button) {
            LiveButton.CHANNELS -> PlayerButtonItem(Icons.AutoMirrored.Filled.List, stringResource(Res.string.player_control_channels), state::openMiniGuide)
            LiveButton.PREVIOUS -> PlayerButtonItem(SofaIcons.SwapHoriz, stringResource(Res.string.player_control_previous), control(toPicture = true) { state.recallLastChannel() })
            LiveButton.PLAY_PAUSE -> if (failed) {
                PlayerButtonItem(Icons.Filled.Refresh, stringResource(Res.string.player_control_retry), control { controls.retry() })
            } else {
                PlayerButtonItem(
                    if (paused) Icons.Filled.PlayArrow else SofaIcons.Pause,
                    stringResource(if (paused) Res.string.player_control_play else Res.string.player_control_pause),
                    control { controls.playPause() },
                )
            }
            // Captions hand the D-pad back to the picture, so Left and Right set the timing while the toast shows.
            LiveButton.CAPTIONS -> PlayerButtonItem(
                if (state.captionsOn) SofaIcons.ClosedCaption else SofaIcons.ClosedCaptionOff,
                stringResource(Res.string.player_control_captions),
                control(toPicture = true) { controls.toggleCaptions() },
                state = stringResource(if (state.captionsOn) Res.string.captions_on else Res.string.captions_off),
            )
            LiveButton.STREAM_INFO -> PlayerButtonItem(
                if (hasAudioChoice) SofaIcons.Audiotrack else Icons.Filled.Info,
                stringResource(Res.string.player_control_stream),
                state::openStreamCard,
            )
            LiveButton.ZOOM -> zoomButtonItem(zoom, control { controls.zoom() })
            LiveButton.SLEEP -> sleepButtonItem(sleep) {
                sleep.step(Clock.System.now().toEpochMilliseconds(), programEndMs = programEnd?.toEpochMilliseconds())
                state.keepInfo()
            }
            LiveButton.RECORD -> PlayerButtonItem(
                if (record?.recording == true) SofaIcons.Recordings else SofaIcons.Record,
                stringResource(if (record?.recording == true) Res.string.guide_details_cancel_recording else Res.string.guide_details_record),
                control { record?.onClick?.invoke() },
            )
            LiveButton.FAVORITE -> PlayerButtonItem(
                if (favorite == true) Icons.Filled.Star else Icons.Outlined.Star,
                stringResource(if (favorite == true) Res.string.guide_details_remove_favorite else Res.string.guide_details_add_favorite),
                control(action = onToggleFavorite),
            )
            LiveButton.GUIDE -> PlayerButtonItem(SofaIcons.GridView, stringResource(Res.string.player_control_guide), onGuide)
        }
    }

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
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                ControlIcon(Icons.Filled.KeyboardArrowDown, stringResource(Res.string.player_control_channel_down), control { state.zapStep(PlayerAction.CHANNEL_DOWN.zapStep()!!) })
                PlayPauseControl(paused, failed, control { controls.playPause() }, control { controls.retry() }, primary = true)
                ControlIcon(Icons.Filled.KeyboardArrowUp, stringResource(Res.string.player_control_channel_up), control { state.zapStep(PlayerAction.CHANNEL_UP.zapStep()!!) })
                PlayerButtonRow(items, entry, onFocused = { state.buttonsFocused = it }, showLabel = false)
                VolumeControl(onAdjust = state::keepInfo, onDone = control {})
                FullScreenControl()
            }
        } else {
            PlayerButtonRow(items, entry, onFocused = { state.buttonsFocused = it }, showLabel = true)
        }
    }
}
