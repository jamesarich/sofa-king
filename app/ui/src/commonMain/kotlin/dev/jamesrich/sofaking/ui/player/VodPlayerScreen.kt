package dev.jamesrich.sofaking.ui.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jamesrich.sofaking.data.CaptionStyle
import dev.jamesrich.sofaking.data.PlaybackSessions
import dev.jamesrich.sofaking.data.PlaybackStream
import dev.jamesrich.sofaking.data.SubtitleLook
import dev.jamesrich.sofaking.data.Subtitles
import dev.jamesrich.sofaking.resources.Res
import dev.jamesrich.sofaking.resources.player_paused
import dev.jamesrich.sofaking.resources.player_zoom_notice
import dev.jamesrich.sofaking.resources.retry
import dev.jamesrich.sofaking.resources.vod_break_skipped
import dev.jamesrich.sofaking.resources.vod_intro_skipped
import dev.jamesrich.sofaking.resources.vod_starting
import dev.jamesrich.sofaking.resources.vod_subtitles_failed
import dev.jamesrich.sofaking.resources.vod_waiting_converter
import dev.jamesrich.sofaking.ui.BackHandler
import dev.jamesrich.sofaking.ui.InputKind
import dev.jamesrich.sofaking.ui.KeepScreenOn
import dev.jamesrich.sofaking.ui.LastInput
import dev.jamesrich.sofaking.ui.LocalInPictureInPicture
import dev.jamesrich.sofaking.ui.LocalLastInput
import dev.jamesrich.sofaking.ui.LocalUptimeMs
import dev.jamesrich.sofaking.ui.LocalWindowFullScreen
import dev.jamesrich.sofaking.ui.PopIn
import dev.jamesrich.sofaking.ui.PopOut
import dev.jamesrich.sofaking.ui.WindowFullScreen
import dev.jamesrich.sofaking.ui.rememberSettled
import dev.jamesrich.sofaking.ui.repeatCountOrNull
import dev.jamesrich.sofaking.ui.screenPadding
import dev.jamesrich.sofaking.ui.theme.SofaScrim
import org.jetbrains.compose.resources.stringResource

/**
 * The full screen for something the viewer moves through: a library item or a recording. [open] opens it on the
 * app's one session (through `LibraryViewModel.play` or the recordings' own call) and the screen plays whatever item
 * stream the session holds, including one it reopens after ON_START or a dropped lease or restarts for a seek past
 * what the server has written. [onPosition] gets the position in the item about once a second while it plays, and
 * [onStop] where it stopped when the viewer leaves or it ends. Leaving stops the player and releases the session.
 * When it ends, [upNext] names what plays after it, which the Up next card counts down to and [onPlayNext] opens;
 * after [autoplays] says the card has played enough by itself, it asks "Still watching?" instead. The [sleep] timer
 * leaves the player when it runs out, or as the item ends in place of Up next.
 */
@Composable
fun VodPlayerScreen(
    title: VodTitle,
    player: LivePlayer,
    sessions: PlaybackSessions,
    /** The [PlaybackStream.itemId] [open] plays: the library item's id, or the recording's. */
    itemId: String,
    open: suspend () -> PlaybackStream,
    onPosition: (positionMs: Long, durationMs: Long?) -> Unit,
    onStop: (positionMs: Long, durationMs: Long?) -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
    /** Playback reached the end; leaving is the default. */
    onEnd: () -> Unit = onExit,
    /** Jump over a [VodMarkerKind.BREAK] when playback runs into it. */
    autoSkipBreaks: Boolean = false,
    /** Skip an intro after a countdown on Skip intro, rather than waiting for a press. */
    autoSkipIntros: Boolean = false,
    /** How the item's text subtitles look. */
    subtitleLook: SubtitleLook = SubtitleLook(),
    /** What plays after this item, asked for when it ends; null ends as [onEnd] says. */
    upNext: suspend () -> VodUpNext? = { null },
    onPlayNext: () -> Unit = {},
    /** The audio and subtitle tracks to pick from; none hides the picker. */
    tracks: VodTracks = VodTracks(),
    /**
     * A recording's captions, which the player draws, on or off; null for a library item,
     * whose subtitles are in [tracks].
     */
    captionsOn: Boolean? = null,
    onToggleCaptions: () -> Unit = {},
    captionStyle: CaptionStyle = CaptionStyle.SYSTEM,
    /** How the picture meets the screen, and the next one picked from the bar or the Zoom key. */
    zoom: VideoZoom = VideoZoom.FIT,
    onZoom: (VideoZoom) -> Unit = {},
    sleep: SleepTimer = remember { SleepTimer() },
    autoplays: AutoplayRun = remember { AutoplayRun() },
    /** Leaves a recording still being written for its channel, live; null hides Back to live. */
    onBackToLive: (() -> Unit)? = null,
) {
    val held by sessions.current.collectAsStateWithLifecycle()
    val stream = held?.takeIf { it.itemId == itemId }
    val parts = rememberVodParts(player, sessions, itemId, tracks)
    val state = parts.state
    VodSessionEffects(parts.vod, title, open, stream)
    VodEndingEffects(parts, sleep, autoplays, upNext, onStop, onEnd, onExit)
    UpNextCountdown(parts, autoplays, onPlayNext, onExit)
    VodClock(parts, title, sleep, autoSkipBreaks, autoSkipIntros, onPosition, onExit)
    LaunchedEffect(parts.picks.subtitle) { parts.picks.loadSubtitles(tracks) }
    VodToastTimers(parts.toasts)
    VodLifecycleEffects(parts.vod, onStop)
    val status by player.status.collectAsStateWithLifecycle()
    VodBarTimer(state, paused = !status.playWhenReady)
    val spots = rememberVodSpots(title.markers, state)
    // Credits that run to the end, with an episode after them: their button plays it.
    val creditsToNext =
        spots.credits?.let { creditsRunToEnd(it, state.durationMs) } == true && parts.ending.upcoming != null
    val buttons = vodButtons(
        failed = state.failure != null,
        inBreak = spots.breakEnd != null,
        tracksPickable = tracks.pickable,
        captions = captionsOn != null,
        pastStart = spots.pastStart,
        hasNext = parts.ending.upcoming != null,
        inIntro = spots.intro != null,
        inCredits = spots.credits != null,
        creditsToNext = creditsToNext,
        speedAdjustable = player.speedAdjustable,
        toLive = onBackToLive != null,
    )
    val captions = VodCaptions(captionsOn, onToggleCaptions, captionStyle, subtitleLook)
    val controls = vodControls(parts, tracks, captions, zoom, onZoom, onPlayNext, onExit, onBackToLive)
    VodFocusEffects(state, parts.focus, buttons, LocalLastInput.current.kind == InputKind.POINTER)
    BackHandler { if (state.back(controls)) onExit() }
    VodSurface(parts, controls, title, zoom, captions, hasButtons = buttons.isNotEmpty(), tracks.pickable, modifier) {
        val barVisible = (state.overlayVisible || state.failure != null) && state.next == null
        VodPills(parts, waiting = stream == null, backShown = barVisible, onExit = onExit)
        VodCards(parts, title, barVisible, onPlayNext, onExit)
        val items = buttons.map { button ->
            vodButtonItem(
                button,
                captionsOn = captionsOn == true,
                zoom = zoom,
                autoSkipSeconds = state.autoSkipSeconds,
                creditsToNext = creditsToNext,
                sleep = sleep,
                speed = parts.vod.speed,
                onClick = { parts.press(button, spots, creditsToNext, controls, sleep) },
            )
        }
        VodInfoBar(parts, title, stream?.mode, barVisible, items, controls)
        VodTextAndTracks(parts, tracks, barVisible, subtitleLook)
    }
}

/** What the player's keys and buttons do beyond its own state. */
private fun vodControls(
    parts: VodParts,
    tracks: VodTracks,
    captions: VodCaptions,
    zoom: VideoZoom,
    onZoom: (VideoZoom) -> Unit,
    onPlayNext: () -> Unit,
    onExit: () -> Unit,
    onBackToLive: (() -> Unit)?,
): VodControls = object : VodControls {
    override fun seek(targetMs: Long) = parts.vod.seek(targetMs)

    override fun playPause() = parts.vod.togglePause()

    override fun retry() = parts.vod.retry()

    override fun toggleCaptions() = parts.picks.switchCaptions(captions.on, captions.onToggle, tracks)

    override fun zoom() {
        val zoomed = zoom.next()
        onZoom(zoomed)
        parts.toasts.toast = VodToast(Res.string.player_zoom_notice, zoomed.label)
    }

    override fun focusButtons() {
        parts.focus.buttons.requestFocus()
    }

    override fun focusPicture() {
        parts.focus.picture.requestFocus()
    }

    override fun playNext() = onPlayNext()

    override fun exit() = onExit()

    override fun backToLive() {
        onBackToLive?.invoke()
    }
}

/**
 * The player surface, with the picture under [content]: it holds focus, names the item to a screen reader, and takes
 * the keys, taps and double taps. A recording's captions are the stream's own, drawn by the player as on live TV.
 * Picture-in-picture shows only the picture: the subtitles are drawn at the full screen's size.
 */
@Composable
private fun VodSurface(
    parts: VodParts,
    controls: VodControls,
    title: VodTitle,
    zoom: VideoZoom,
    captions: VodCaptions,
    hasButtons: Boolean,
    tracksPickable: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val state = parts.state
    val player = parts.vod.player
    val status by player.status.collectAsStateWithLifecycle()
    val scrubClock = LocalUptimeMs.current
    Box(
        modifier
            .fillMaxSize()
            .background(SofaScrim)
            .focusRequester(parts.focus.picture)
            .focusable()
            .semantics { contentDescription = title.title }
            .onPreviewKeyEvent { event ->
                state.onKeyEvent(
                    event.key,
                    event.type,
                    controls,
                    !status.playWhenReady,
                    hasButtons = hasButtons,
                    tracksPickable = tracksPickable,
                    repeatCount = event.repeatCountOrNull,
                    nowMs = scrubClock(),
                )
            }
            .vodTouches(parts, LocalWindowFullScreen.current, LocalLastInput.current),
    ) {
        player.Video(Modifier.fillMaxSize(), zoom) { Box(Modifier.fillMaxSize().background(SofaScrim)) }
        if (captions.on == true) {
            val windowHeightDp =
                with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp().value.toInt() }
            Captions(
                player,
                captions.style,
                CaptionPolicy.bottomPaddingFraction(windowHeightDp),
                Modifier.fillMaxSize(),
            )
        }
        KeepScreenOn(status.isPlaying)
        if (!LocalInPictureInPicture.current) content()
    }
}

/**
 * A tap shows or hides the bar. A double click switches a window the app can make [fullScreen]; on a phone or tablet
 * a double tap on the picture's left or right third steps back or forward, as the buttons do.
 */
private fun Modifier.vodTouches(parts: VodParts, fullScreen: WindowFullScreen?, lastInput: LastInput): Modifier {
    val state = parts.state
    return pointerInput(fullScreen) {
        detectTapGestures(
            onDoubleTap = { offset ->
                if (fullScreen != null) {
                    fullScreen.toggle()
                } else {
                    when (doubleTapSeek(offset.x, size.width.toFloat())) {
                        -1 -> parts.vod.seek(state.positionMs - VodScrub.BACK_STEP_MS)
                        1 -> parts.vod.seek(state.positionMs + VodScrub.FORWARD_STEP_MS)
                    }
                    state.showInfo()
                }
            },
            onTap = { state.tap() },
        )
    }.showBarOnMouseMove(lastInput, state::showInfo)
}

/**
 * The pills top left: Back for a finger or a mouse while the bar shows ([backShown]), starting or waiting for a
 * converter while there is no picture yet ([waiting] for the stream), paused, and the toasts.
 */
@Composable
private fun BoxScope.VodPills(parts: VodParts, waiting: Boolean, backShown: Boolean, onExit: () -> Unit) {
    val state = parts.state
    val toasts = parts.toasts
    val status by parts.vod.player.status.collectAsStateWithLifecycle()
    val pointerDriven = LocalLastInput.current.kind == InputKind.POINTER
    Column(Modifier.align(Alignment.TopStart).screenPadding(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AnimatedVisibility(backShown && pointerDriven, enter = PopIn, exit = PopOut) {
            PlayerBackControl(onExit)
        }
        val buffering = status.state == PlaybackState.BUFFERING
        AnimatedVisibility(
            rememberSettled((buffering || waiting || state.waitingForConverter) && state.failure == null),
            enter = PopIn,
            exit = PopOut,
        ) {
            StatusPill(
                stringResource(
                    if (state.waitingForConverter) Res.string.vod_waiting_converter else Res.string.vod_starting,
                ),
                busy = true,
            )
        }
        AnimatedVisibility(!status.playWhenReady && state.failure == null && !waiting, enter = PopIn, exit = PopOut) {
            StatusPill(stringResource(Res.string.player_paused))
        }
        AnimatedVisibility(toasts.breakSkipped, enter = PopIn, exit = PopOut) {
            StatusPill(stringResource(Res.string.vod_break_skipped))
        }
        AnimatedVisibility(toasts.introSkipped, enter = PopIn, exit = PopOut) {
            StatusPill(stringResource(Res.string.vod_intro_skipped))
        }
        AnimatedVisibility(toasts.subtitlesFailed, enter = PopIn, exit = PopOut) {
            StatusPill(stringResource(Res.string.vod_subtitles_failed))
        }
        val popIn = PopIn
        val popOut = PopOut
        AnimatedContent(toasts.toast, transitionSpec = { popIn togetherWith popOut }, label = "toast") { shown ->
            if (shown != null) {
                StatusPill(
                    shown.arg?.let {
                        stringResource(shown.text, stringResource(it))
                    } ?: stringResource(shown.text),
                )
            }
        }
        VolumeToast()
    }
}

/**
 * The cards over the picture: a failure, Up next (which [onPlayNext] plays and [onExit] leaves), the transport's
 * disc, and where a scrub would land while the bar is hidden ([barVisible]).
 */
@Composable
private fun BoxScope.VodCards(
    parts: VodParts,
    title: VodTitle,
    barVisible: Boolean,
    onPlayNext: () -> Unit,
    onExit: () -> Unit,
) {
    val state = parts.state
    state.failure?.let { FailureCard(it, live = false) }
    state.next?.let {
        UpNextCard(
            it,
            parts.ending.secondsLeft,
            LocalLastInput.current.kind == InputKind.POINTER,
            onPlay = onPlayNext,
            onCancel = onExit,
            stillWatching = parts.ending.stillWatching,
        )
    }
    TransportDisc(state.transport, state.transportPulse)
    SeekBadge(
        state.scrubTarget.takeIf { !barVisible && state.failure == null },
        positionMs = { state.positionMs },
        scrub = title.scrub,
    )
}

/**
 * The text subtitles, drawn over the bar and clear of its top edge ([barVisible]), waiting while a card would cover
 * them; and the Audio and subtitles card while it is open.
 */
@Composable
private fun BoxScope.VodTextAndTracks(parts: VodParts, tracks: VodTracks, barVisible: Boolean, look: SubtitleLook) {
    val state = parts.state
    val picks = parts.picks
    if (!state.tracksOpen && state.failure == null && state.next == null) {
        picks.subtitles?.let { cues ->
            val gap = SUBTITLE_GAP + look.raise.height
            VodSubtitle(
                cues,
                { state.positionMs - picks.subtitleOffsetMs },
                bottom = if (barVisible) parts.barHeight + gap else gap,
                look = look,
            )
        }
    }
    if (state.tracksOpen) {
        TracksCard(
            tracks,
            picks.audio,
            picks.subtitle,
            onAudio = {
                picks.pickAudio(it, tracks)
                parts.focus.picture.requestFocus()
            },
            onSubtitle = {
                picks.pickSubtitle(it, tracks)
                parts.focus.picture.requestFocus()
            },
            offsetMs = picks.subtitleOffsetMs.takeIf { picks.subtitles != null },
            onOffset = { picks.subtitleOffsetMs = it },
        )
    }
}

// The position ticks four times a second, so it is passed as a lambda and read only by these small composables; the
// screen itself recomposes only when something else changes.

@Composable
private fun BoxScope.VodSubtitle(cues: Subtitles, positionMs: () -> Long, bottom: Dp, look: SubtitleLook) {
    cues.textAt(positionMs())?.let { SubtitleText(it, bottom = bottom, look = look) }
}

private val SUBTITLE_GAP = 24.dp
