package dev.jamesrich.sofaking.ui.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
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
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Instant

/** Status pills stack top left so a caption toast never covers "Paused" or "Tuning". */
@Composable
internal fun BoxScope.StatusPills(state: PlayerState, buffering: Boolean, paused: Boolean) {
    val popIn = PopIn
    val popOut = PopOut
    Column(
        Modifier.align(Alignment.TopStart).screenPadding(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
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
    actions: MiniGuideActions,
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
                actions = actions,
            )
        }
    }
}

/** The info bar over the bottom of the picture, with the touch controls once a pointer has been used. */
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
    controls: PlayerControls,
    onGuide: () -> Unit,
    focus: FocusRequester,
) {
    /** A touch control's action, after which the info bar stays up for another while and the surface keeps focus. */
    fun control(action: () -> Unit): () -> Unit = {
        action()
        state.keepInfo()
        focus.requestFocus()
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
            TouchControls(
                captionsOn = state.captionsOn,
                paused = paused,
                failed = state.failure != null,
                hasAudioChoice = hasAudioChoice,
                onGuide = onGuide,
                onChannelUp = control { state.zapStep(PlayerAction.CHANNEL_UP.zapStep()!!) },
                onChannelDown = control { state.zapStep(PlayerAction.CHANNEL_DOWN.zapStep()!!) },
                onChannelList = state::openMiniGuide,
                onCaptions = control { controls.toggleCaptions() },
                onPlayPause = control { controls.playPause() },
                onStreamInfo = state::openStreamCard,
                onRetry = control { controls.retry() },
            )
        }
    }
}
