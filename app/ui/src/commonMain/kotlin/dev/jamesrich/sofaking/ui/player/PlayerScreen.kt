package dev.jamesrich.sofaking.ui.player

import dev.jamesrich.sofaking.ui.requestFocusWhenAttached
import dev.jamesrich.sofaking.ui.appLifecycleOwner
import dev.jamesrich.sofaking.ui.BackHandler
import dev.jamesrich.sofaking.contract.Wire
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.withStateAtLeast
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import co.touchlab.kermit.Logger
import dev.jamesrich.sofaking.ui.KeepScreenOn
import dev.jamesrich.sofaking.ui.formatTemplate
import dev.jamesrich.sofaking.ui.isOk
import dev.jamesrich.sofaking.ui.isLongPress
import dev.jamesrich.sofaking.ui.repeatCountOrNull
import dev.jamesrich.sofaking.resources.*
import dev.jamesrich.sofaking.data.AdBreakNow
import dev.jamesrich.sofaking.data.AppSettings
import dev.jamesrich.sofaking.data.BreakWatch
import dev.jamesrich.sofaking.data.CaptionStyle
import dev.jamesrich.sofaking.data.Channel
import dev.jamesrich.sofaking.data.Lineup
import dev.jamesrich.sofaking.data.LiveBreaks
import dev.jamesrich.sofaking.data.PlaybackIntent
import dev.jamesrich.sofaking.data.PlaybackSessions
import dev.jamesrich.sofaking.data.Programme
import dev.jamesrich.sofaking.data.RecordingMarks
import dev.jamesrich.sofaking.ui.label
import dev.jamesrich.sofaking.ui.rememberMinuteNow
import dev.jamesrich.sofaking.ui.rememberTimeFormatter
import dev.jamesrich.sofaking.ui.theme.SofaScrim
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Clock
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import dev.jamesrich.sofaking.ui.uptimeMs
import androidx.compose.ui.input.pointer.pointerInput
import dev.jamesrich.sofaking.ui.InputKind
import dev.jamesrich.sofaking.ui.LocalInPictureInPicture
import dev.jamesrich.sofaking.ui.LocalLastInput
import dev.jamesrich.sofaking.ui.LocalWindowFullScreen

private const val CAPTION_TOAST_MS = 2_000L
/** How long after the CC key Left and Right keep adjusting the channel's caption timing; each press restarts it. */
internal const val CAPTION_TIMING_WINDOW_MS = 3_000L
private const val MINI_GUIDE_IDLE_MS = 8_000L
private const val STREAM_CARD_IDLE_MS = 15_000L
private const val LIVE_TOAST_MS = 1_500L
private const val NOTICE_MS = 3_000L
private const val SLEEP_CHECK_MS = 1_000L
/** A swipe up this far on a touch screen opens the channel list. */
private const val SWIPE_UP_DP = 80
/** The server's answer when a session is asked for a channel id its lineup no longer has. */
internal const val CHANNEL_NOT_FOUND = Wire.ErrorCode.CHANNEL_NOT_FOUND
/** How long the player waits for the lineup it asked for to catch up with the one it holds. */
private const val LINEUP_SYNC_MS = 2_000L
/** How often the player looks for an ad in its own playlist; a break is several segments of a few seconds each. */
private const val AD_BREAK_POLL_MS = 500L
/** How often an engine that cannot read the playlist asks the server instead, and how long it waits after a failed ask. */
private const val AD_BREAK_ASK_MS = 1_000L
private const val AD_BREAK_RETRY_MS = 15_000L
private val log = Logger.withTag("SofaKing")

/** How long a zap rests before the new channel is tuned, so pressing through channels does not open a session per press. */
internal const val ZAP_SETTLE_MS = 400L

/** A zap from a tuned channel waits [ZAP_SETTLE_MS]; the first tune, which may take over the preview's stream, does not. */
internal fun zapSettleMs(tuned: Channel?, next: Channel): Long =
    if (tuned == null || tuned.guideId == next.guideId) 0L else ZAP_SETTLE_MS

/** The tune in flight, so a zap cancels the previous channel's session request before it lands on the player. */
private class TuneJob {
    var job: Job? = null
}

/** The full-screen player's test tag; Android exposes it as a resource id for the Baseline Profile generator. */
const val PLAYER_TEST_TAG = "player"

@Composable
fun PlayerScreen(
    lineup: Lineup,
    startIndex: Int,
    /** The channel watched before this player opened, for Last; it may have been left through the guide. */
    previousGuideId: String? = null,
    /** The guide filter the channel was picked from; null cycles the whole lineup. */
    zapScope: ZapScope? = null,
    /** Opened by the guide with no key pressed: the screen may sleep until the first key or tap. */
    unattended: Boolean = false,
    player: LivePlayer,
    sessions: PlaybackSessions,
    onExit: () -> Unit,
    onChannelChanged: (Channel) -> Unit,
    onGuide: () -> Unit = onExit,
    onSearch: () -> Unit = onExit,
    reloadLineup: suspend () -> Lineup? = { null },
    onChannelGone: (Channel) -> Unit = { onExit() },
    captionsEnabled: Boolean = false,
    onCaptionsChanged: (Boolean) -> Unit = {},
    captionAdvanceFor: (Channel) -> Int = { AppSettings.DEFAULT_CAPTION_ADVANCE_MS },
    onCaptionAdvanceChanged: (Channel, Int) -> Unit = { _, _ -> },
    captionStyle: CaptionStyle = CaptionStyle.SYSTEM,
    smoothCaptions: Boolean = true,
    /** Starred channels, for the channel list's Favorites chip, in the order Settings gave them. */
    favorites: Set<String> = emptySet(),
    favoritesOrder: List<String> = emptyList(),
    /** Channels watched lately, latest first, for the channel list's Recent chip; empty leaves the chip out. */
    recentGuideIds: List<String> = emptyList(),
    /** Stars or unstars a channel from the channel list; null leaves the star out. */
    onToggleFavorite: ((Channel) -> Unit)? = null,
    /** How many info bars have carried the key legend so far; it rides the first [AppSettings.KEY_LEGEND_BARS]. */
    keyLegendShown: Int = AppSettings.KEY_LEGEND_BARS,
    onKeyLegendShown: (Int) -> Unit = {},
    /** The server's scheduled recordings, for the bar's Record; null where it does not record. */
    recordingMarks: RecordingMarks? = null,
    /** Records the program, or cancels its recording; false when the server did not take it. */
    onRecord: suspend (Programme) -> Boolean = { false },
    /** How the picture meets the screen, and the next one picked from the bar or the Zoom key. */
    zoom: VideoZoom = VideoZoom.FIT,
    onZoom: (VideoZoom) -> Unit = {},
    /** Whether the break screen covers the ads a streaming channel marks, and whether their sound plays under it. */
    liveBreaks: LiveBreaks = LiveBreaks.PLAY,
    /** The ads the break screen covered today before now, and where each break screen's time goes when it closes. */
    adsDodgedTodayMs: Long = 0,
    onAdsDodged: (Long) -> Unit = {},
    /** The sleep timer, which leaves the player as Back does once it runs out. */
    sleep: SleepTimer = remember { SleepTimer() },
) {
    val state = rememberPlayerState(lineup.channels, startIndex, previousGuideId, zapScope, captionsEnabled, attended = !unattended, keyLegendShown)
    val captionSync = player.captionSync
    val nowPlayingTemplate = stringResource(Res.string.now_playing)
    val nowPlayingChannelTemplate = stringResource(Res.string.now_playing_channel)
    val channels = remember(lineup.channels, zapScope, state.widened) { zapRing(lineup.channels, zapScope, state.widened) }
    val scoped = channels.size < lineup.channels.size
    // As rememberUpdatedState does: the key handlers and effects read the ring and the lineup last composed.
    state.channels = channels
    state.lineupChannels = lineup.channels
    val currentOnChannelChanged by rememberUpdatedState(onChannelChanged)
    val currentOnCaptionsChanged by rememberUpdatedState(onCaptionsChanged)
    val currentOnCaptionAdvanceChanged by rememberUpdatedState(onCaptionAdvanceChanged)
    val currentOnKeyLegendShown by rememberUpdatedState(onKeyLegendShown)
    val currentOnToggleFavorite by rememberUpdatedState(onToggleFavorite)
    val currentOnExit by rememberUpdatedState(onExit)
    val windowHeightDp = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp().value.toInt() }
    val captionBottomFraction = CaptionPolicy.bottomPaddingFraction(windowHeightDp)
    // The shared player may arrive already playing the channel from the guide preview.
    val status by player.status.collectAsStateWithLifecycle()
    val playing = status.isPlaying
    val buffering = status.state == PlaybackState.BUFFERING
    val paused = !status.playWhenReady
    val audioTracks by player.audioTracks.collectAsStateWithLifecycle()
    val pointerDriven = LocalLastInput.current.kind == InputKind.POINTER
    val fullScreen = LocalWindowFullScreen.current
    val inPictureInPicture = LocalInPictureInPicture.current
    val volume = LocalPlayerVolume.current
    val now by rememberMinuteNow()
    val timeFormat = rememberTimeFormatter()
    val scope = rememberCoroutineScope()
    val tuneJob = remember { TuneJob() }

    LaunchedEffect(sleep) {
        while (true) {
            delay(SLEEP_CHECK_MS)
            if (sleep.due(Clock.System.now().toEpochMilliseconds())) {
                sleep.cancel()
                currentOnExit()
                break
            }
        }
    }

    // The server marks the ad segments of a streaming channel's playlist; while Settings asks, the break screen covers
    // them. Media3 reads the marks itself; the other engines ask the server, which knows the segments they fetched.
    // OK or Back shows the picture again until that break is over (BreakWatch).
    var inAdBreak by remember { mutableStateOf(false) }
    var adBreakLeftMs by remember { mutableStateOf<Long?>(null) }
    var adBreakDismissed by remember { mutableStateOf(false) }
    LaunchedEffect(player, liveBreaks.covers, state.tuned?.guideId) {
        inAdBreak = false
        adBreakDismissed = false
        if (!liveBreaks.covers) return@LaunchedEffect
        val watch = BreakWatch()
        var lastFound: AdBreakNow? = null
        while (true) {
            var wait = if (player.readsAdMarks) AD_BREAK_POLL_MS else AD_BREAK_ASK_MS
            // With nothing loaded past the picture the picture is stalled, and the server can't place it.
            val ahead = if (player.readsAdMarks) null else player.loadedAheadMs
            val stalled = !player.readsAdMarks && ahead == null
            val found = if (player.readsAdMarks) {
                player.adBreak()
            } else {
                ahead?.let { loaded ->
                    try {
                        sessions.adBreak(loaded)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // A server from before the break screen, or one between sessions: ask again in a while.
                        log.d { "ad break: ask failed: ${e.message}" }
                        wait = AD_BREAK_RETRY_MS
                        null
                    }
                }
            }
            val shown = if (stalled) watch.stalled() else watch.shown(found)
            if (found == null && shown != null && lastFound != null) {
                log.d { "ad break: ${if (stalled) "picture stalled" else "reading lost it"}, holding ${shown.remainingMs ?: "?"} ms" }
            }
            lastFound = found
            inAdBreak = shown != null
            adBreakLeftMs = shown?.remainingMs
            if (watch.over) adBreakDismissed = false
            delay(wait)
        }
    }
    // A failure inside a break keeps the screen up under its card: a browser can fail at the seam between two ads, and
    // the card should not uncover the ad while the stream reconnects.
    val breakScreen = inAdBreak && !adBreakDismissed && !inPictureInPicture
    val breakScreenUp by rememberUpdatedState(breakScreen)
    val breakMuted by rememberUpdatedState(breakScreen && liveBreaks.muted)
    fun gain() = if (breakMuted) 0f else volume.gain

    val currentChannel = state.currentChannel
    val channelAdvance = state.captionAdvance(captionAdvanceFor)
    val captionLead = if (state.captionsOn && player.supportsCaptionLead) channelAdvance * 1_000L else 0L
    SideEffect {
        captionSync.advanceUs = captionLead
        captionSync.smoothingEnabled = smoothCaptions
    }

    // Opened and closed by the effect, never during composition; the publishers below all run after it. It is
    // open only while the app is in front, as the app stops the player behind another app. The system's
    // next/previous/play/pause (MediaSession, media keys) reach the player through this session.
    var mediaSession by remember { mutableStateOf<MediaSessionHandle?>(null) }
    LifecycleStartEffect(player, lifecycleOwner = appLifecycleOwner) {
        val session = player.openMediaSession { step -> state.zapStep(step) }
        mediaSession = session
        player.volume = gain()
        onStopOrDispose {
            session.close()
            mediaSession = null
        }
    }
    LaunchedEffect(player, volume) { snapshotFlow { gain() }.collect { player.volume = it } }
    LaunchedEffect(player) { player.status.collect { state.onStatus(it) } }
    LaunchedEffect(player) {
        player.errors.collect { error -> state.fail(error.code, immediate = error.streamGone) }
    }

    fun nowPlayingFor(channel: Channel) = NowPlaying(
        title = lineup.nowAndNext(channel, now).first?.title ?: channel.name,
        subtitle = channel.label(),
        artworkUrl = channel.logo,
    )

    val lifecycle = appLifecycleOwner.lifecycle

    fun load(channel: Channel) {
        val metadata = nowPlayingFor(channel)
        mediaSession?.publish(metadata)
        tuneJob.job?.cancel()
        // Handed over from the guide preview mid-stream: keep it, and let the caption lead grow back from zero.
        // The same session is promoted to watch, so the stream continues and its lease grows to 90 s.
        val playerState = player.status.value.state
        if (player.currentChannelId == channel.guideId && (playerState == PlaybackState.READY || playerState == PlaybackState.BUFFERING)) {
            captionSync.effectiveUs = 0L
            tuneJob.job = scope.launch {
                try {
                    sessions.setIntent(channel.guideId, PlaybackIntent.WATCH)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.w(e) { "session: promote failed" }
                }
            }
            return
        }
        // Reset the renderers so the next stream's decoder rescales instead of reusing the old surface size,
        // and before the session changes hands, so the released stream's end is not read as a failure.
        player.stop()
        // Playback can't start until the full advance is buffered, so a fresh tune starts at the full lead.
        captionSync.effectiveUs = captionSync.advanceUs
        tuneJob.job = scope.launch {
            while (true) {
                val stream = try {
                    sessions.tune(channel.guideId, PlaybackIntent.WATCH)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // The reconnect loop below tunes again with backoff, as it does for a stream that failed.
                    state.fail(failureCode(e))
                    return@launch
                }
                if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                    player.setStream(stream, metadata)
                    player.prepare()
                    return@launch
                }
                // The app went behind another one while the session opened, and its stop released that session.
                lifecycle.withStateAtLeast(Lifecycle.State.STARTED) {}
            }
        }
    }

    // The lineup refreshes every few hours and may reorder or grow, and the ring widens from the guide's filter
    // to the whole lineup; the tuned channel keeps its stream and its row follows it.
    LaunchedEffect(channels) { state.followRing(channels) }

    LaunchedEffect(state.index, currentChannel?.guideId, lifecycle, state.recovering) {
        // While a dead id is being followed, nothing tunes until the row for its replacement is known.
        if (state.recovering != null) return@LaunchedEffect
        val channel = currentChannel ?: return@LaunchedEffect
        // Channels passed under quick presses are never tuned; the info bar follows each press at once.
        delay(zapSettleMs(state.tuned, channel))
        lifecycle.withStateAtLeast(Lifecycle.State.STARTED) {
            val before = state.tuned
            state.tuned = channel
            // A refresh that keeps the channel preserves its stream and session.
            if (before?.guideId != channel.guideId) {
                load(channel)
                currentOnChannelChanged(channel)
            }
        }
    }

    // Moves the session's programme title on with the minute clock when the show changes; the stream is not touched.
    LaunchedEffect(state.index, now, mediaSession) {
        val channel = state.currentChannel ?: return@LaunchedEffect
        mediaSession?.publish(nowPlayingFor(channel))
    }

    PlayerTimers(state, player, paused)

    // Keeps the caption look-ahead inside what's downloaded; see CaptionSync.nextEffectiveUs. Nothing runs
    // while captions are off, the channel has no advance or the engine cannot apply one.
    val captionLeadActive = captionLead != 0L
    LaunchedEffect(player, captionLeadActive) {
        if (!captionLeadActive) {
            captionSync.effectiveUs = 0L
            return@LaunchedEffect
        }
        var tick = 0
        while (true) {
            delay(250)
            // While (re)buffering the start gate guards the lead; clamping then would collapse it to zero.
            if (!player.status.value.isPlaying) continue
            captionSync.effectiveUs = CaptionSync.nextEffectiveUs(
                currentUs = captionSync.effectiveUs,
                advanceUs = captionSync.advanceUs,
                bufferedUs = player.bufferedMs * 1_000L,
            )
            if (++tick % 8 == 0) {
                log.d { "captions: buffered=${player.bufferedMs}ms lead=${captionSync.effectiveUs / 1000}ms of ${captionSync.advanceUs / 1000}ms" }
            }
        }
    }

    // Reconnects with backoff for as long as the channel is on screen, and only while the app is in front:
    // a retry in the background would take the TV's decoder and a session on the server. A 410 skips the
    // first wait: the server asked for the session to be re-read, which is what tuning again does.
    LaunchedEffect(state.failure) {
        val current = state.failure ?: run {
            state.recovering = null
            return@LaunchedEffect
        }
        if (current.code == CHANNEL_NOT_FOUND) {
            // The server's lineup has new ids (a re-import): retrying this one would fail forever. Reload the
            // lineup and follow the channel by number, then name; with nothing to follow, the guide takes over.
            val channel = state.currentChannel ?: return@LaunchedEffect
            state.recovering = channel
            // The replacement is looked for in the whole lineup; the ring's ids are the ones that went stale.
            if (!state.widened) {
                state.widened = true
                state.index = state.lineupChannels.indexOfFirst { it.guideId == channel.guideId }.coerceAtLeast(0)
            }
            var attempt = current.attempt
            var reloaded = reloadLineup()
            while (reloaded == null) {
                // Unreachable or timed out: the reload, not the dead id, is what backs off and tries again.
                delay(reconnectDelayMs(attempt++))
                lifecycle.withStateAtLeast(Lifecycle.State.STARTED) {}
                reloaded = reloadLineup()
            }
            withTimeoutOrNull(LINEUP_SYNC_MS) { snapshotFlow { state.channels }.first { it == reloaded.channels } }
            val target = resolveReplacement(state.channels, channel)
            if (target == null) {
                onChannelGone(channel)
            } else {
                state.index = target
                state.clearFailure()
            }
            return@LaunchedEffect
        }
        if (!(current.immediate && current.attempt == 0)) {
            // The card counts the wait down a second at a time.
            val until = uptimeMs() + reconnectDelayMs(current.attempt)
            while (true) {
                val remaining = until - uptimeMs()
                if (remaining <= 0L) break
                state.retryIn = ((remaining + 999L) / 1_000L).toInt()
                delay(minOf(remaining, 1_000L))
            }
        }
        state.retryIn = 0
        lifecycle.withStateAtLeast(Lifecycle.State.STARTED) {}
        val channel = state.currentChannel ?: return@LaunchedEffect
        state.failure = current.copy(attempt = current.attempt + 1)
        load(channel)
    }
    LaunchedEffect(state.failure == null) { if (state.failure == null) state.retryIn = null }

    LaunchedEffect(state.overlayVisible) {
        if (!state.overlayVisible || pointerDriven) return@LaunchedEffect
        state.legendForNewBar()?.let(currentOnKeyLegendShown)
    }

    val focus = remember { FocusRequester() }
    val buttonsEntry = remember { FocusRequester() }
    // Focus returns to the player surface whenever a panel closes, including after its list is removed, and when the
    // bar leaves while one of its buttons has it.
    LaunchedEffect(state.miniGuideOpen, state.streamCardOpen, state.overlayVisible) {
        // Retried until the surface is placed: on entry it may not be yet, and a refused request would leave nothing focused.
        if (!state.miniGuideOpen && !state.streamCardOpen && !(state.overlayVisible && state.buttonsFocused)) requestFocusWhenAttached(focus)
    }
    // A short note at the top left: Record's answer once the server has taken the change or not, or the new zoom.
    var notice by remember { mutableStateOf<String?>(null) }
    var recordBusy by remember { mutableStateOf(false) }
    val recordingTemplate = stringResource(Res.string.player_recording)
    val recordCanceledTemplate = stringResource(Res.string.player_recording_canceled)
    val recordFailedText = stringResource(Res.string.player_recording_failed)
    val zoomTemplate = stringResource(Res.string.player_zoom_notice)
    val zoomLabels = VideoZoom.entries.associateWith { stringResource(it.label) }
    LaunchedEffect(notice) {
        if (notice == null) return@LaunchedEffect
        delay(NOTICE_MS)
        notice = null
    }
    fun recordFor(programme: Programme?): LiveRecord? {
        val marks = recordingMarks ?: return null
        val on = programme ?: return null
        val recording = marks.of(on).recording
        return LiveRecord(recording) {
            if (recordBusy) return@LiveRecord
            recordBusy = true
            scope.launch {
                try {
                    notice = when {
                        !onRecord(on) -> recordFailedText
                        recording -> formatTemplate(recordCanceledTemplate, on.title)
                        else -> formatTemplate(recordingTemplate, on.title)
                    }
                } finally {
                    recordBusy = false
                }
            }
        }
    }

    val controls = object : PlayerControls {
        override fun guide() = onGuide()

        override fun search() = onSearch()

        override fun toggleCaptions() {
            val enabled = !state.captionsOn
            player.setCaptionsEnabled(enabled)
            state.captionsToggled(enabled, channelHasText = player.hasTextTrack, leadSupported = player.supportsCaptionLead, timingMs = channelAdvance)
            currentOnCaptionsChanged(enabled)
        }

        override fun adjustCaptionTiming(direction: Int) = state.adjustCaptionTiming(direction, channelAdvance, currentOnCaptionAdvanceChanged)

        override fun playPause() = if (player.playWhenReady) player.pause() else player.play()

        override fun play() = player.play()

        override fun pause() = player.pause()

        override fun retry() {
            val channel = state.currentChannel ?: return
            state.clearFailure()
            load(channel)
        }

        override fun focusButtons() {
            buttonsEntry.requestFocus()
        }

        override fun focusPicture() {
            focus.requestFocus()
        }

        override fun record() {
            val channel = state.currentChannel ?: return
            recordFor(lineup.nowAndNext(channel, now).first)?.onClick?.invoke()
        }

        override fun zoom() {
            val next = zoom.next()
            onZoom(next)
            notice = formatTemplate(zoomTemplate, zoomLabels.getValue(next))
        }

        override fun stop() = onExit()
    }
    /**
     * OK with nothing else on screen, or Back with no panel open, goes to the break screen, which leaves on the
     * key-up; the info bar and zapping work over it as ever. Over a failure the keys work the failure card instead.
     */
    fun breakScreenTakes(key: Key): Boolean {
        if (!breakScreenUp || state.failure != null || state.miniGuideOpen || state.streamCardOpen || state.buttonsFocused) return false
        return key == Key.Back || (key.isOk && !state.overlayVisible && !state.numbers.hasDigits)
    }
    BackHandler {
        when {
            breakScreenTakes(Key.Back) -> adBreakDismissed = true
            state.back(controls) -> onExit()
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .testTag(PLAYER_TEST_TAG)
            .background(SofaScrim)
            .focusRequester(focus)
            .focusable()
            .semantics {
                state.currentChannel?.let { channel ->
                    val title = lineup.nowAndNext(channel, now).first?.title
                    contentDescription = if (title == null) {
                        formatTemplate(nowPlayingChannelTemplate, channel.label())
                    } else {
                        formatTemplate(nowPlayingTemplate, channel.label(), title)
                    }
                }
            }
            .onPreviewKeyEvent { event ->
                state.attended = true
                if (breakScreenTakes(event.key)) {
                    if (event.type == KeyEventType.KeyUp) adBreakDismissed = true
                    return@onPreviewKeyEvent true
                }
                state.onKeyEvent(event.key, event.type, controls, event.isLongPress, event.repeatCountOrNull, hasAudioTracks = audioTracks.isNotEmpty())
            }
            // A tap or click on the video: the channel list closes, or the info bar and its touch controls toggle. Focus
            // stays on the player surface, so the remote and keyboard keep working after a pointer has been used. A
            // double click switches a window the app can make full screen.
            .pointerInput(fullScreen) {
                detectTapGestures(
                    onDoubleTap = fullScreen?.let { { _ -> it.toggle() } },
                    onTap = {
                        state.attended = true
                        if (breakScreenTakes(Key.Back)) adBreakDismissed = true else state.tap()
                        focus.requestFocus()
                    },
                )
            }
            .showBarOnMouseMove(LocalLastInput.current, state::showInfo)
            // A swipe up on a touch screen opens the channel list, the way a second OK does on the remote.
            .pointerInput(Unit) {
                var dragged = 0f
                detectVerticalDragGestures(
                    onDragStart = { dragged = 0f },
                    onDragEnd = { if (dragged < -SWIPE_UP_DP.dp.toPx()) state.openMiniGuide() },
                    onVerticalDrag = { _, dy -> dragged += dy },
                )
            },
    ) {
        player.Video(Modifier.fillMaxSize(), zoom) { Box(Modifier.fillMaxSize().background(SofaScrim)) }
        Captions(player, captionStyle, captionBottomFraction, Modifier.fillMaxSize())
        KeepScreenOn(playing && state.attended)
        // Picture-in-picture shows the picture and its captions, which scale with the window, and nothing else.
        if (inPictureInPicture) return@Box
        if (breakScreen) {
            val next = state.currentChannel?.let { lineup.nowAndNext(it, now).second }
            BreakScreen(
                Modifier.fillMaxSize(),
                remainingMs = { adBreakLeftMs },
                upNext = next?.let { stringResource(Res.string.break_up_next, it.title, timeFormat.format(it.start)) },
                dodgedBeforeMs = adsDodgedTodayMs,
                onCovered = onAdsDodged,
            )
        }

        val channel = state.currentChannel
        if (channel == null) {
            Text(stringResource(Res.string.no_channels), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.align(Alignment.Center))
        } else {
            StatusPills(
                state,
                buffering = buffering,
                paused = paused,
                notice = notice,
                onBack = onExit.takeIf { pointerDriven && state.overlayVisible && !state.miniGuideOpen && !state.streamCardOpen },
            )
            state.failure?.let { FailureCard(it.code) { state.retryIn } }
            ChannelNumberBadge(state.numbers)
            StreamCardPanel(state, player, sessions, audioTracks)
            ChannelListPanel(
                state = state,
                lineup = lineup,
                channel = channel,
                scope = zapScope?.takeIf { scoped },
                favorites = favorites,
                favoritesOrder = favoritesOrder,
                recent = recentGuideIds,
                now = now,
            )
            InfoBarPanel(
                state = state,
                lineup = lineup,
                channel = channel,
                now = now,
                scope = zapScope?.takeIf { scoped },
                ringSize = channels.size,
                captionAdvanceMs = channelAdvance.takeIf { player.supportsCaptionLead },
                pointerDriven = pointerDriven,
                paused = paused,
                hasAudioChoice = audioTracks.isNotEmpty(),
                record = recordFor(lineup.nowAndNext(channel, now).first),
                sleep = sleep,
                programmeEnd = lineup.nowAndNext(channel, now).first?.stop,
                zoom = zoom,
                favorite = if (onToggleFavorite != null) channel.guideId in favorites else null,
                onToggleFavorite = { currentOnToggleFavorite?.invoke(channel) },
                controls = controls,
                onGuide = controls::guide,
                focus = focus,
                entry = buttonsEntry,
            )
        }
    }
}

/**
 * The panels' rest timers and the figures that move while they show: the info bar, the channel list, the stream
 * card and the caption toast each close after a rest, which any use restarts; the stream card's figures and the
 * pause pill's count are read again every second.
 */
@Composable
private fun PlayerTimers(state: PlayerState, player: LivePlayer, paused: Boolean) {
    LaunchedEffect(state.miniGuideOpen, state.miniGuideActivity) {
        if (!state.miniGuideOpen) return@LaunchedEffect
        delay(MINI_GUIDE_IDLE_MS)
        state.miniGuideOpen = false
    }
    LaunchedEffect(state.streamCardOpen, state.streamCardActivity) {
        if (!state.streamCardOpen) return@LaunchedEffect
        delay(STREAM_CARD_IDLE_MS)
        state.streamCardOpen = false
    }
    // The card's figures move while it is open: the buffer every second, the formats when the engine changes them.
    LaunchedEffect(state.streamCardOpen, player) {
        while (state.streamCardOpen) {
            state.streamInfo = player.streamInfo()
            state.streamBufferedMs = player.bufferedMs
            delay(1_000)
        }
    }
    // The pause pill counts how far behind live the picture is; a resume past the live-resume rule says "Live" for a moment.
    LaunchedEffect(paused) {
        if (!paused) {
            val wasPaused = state.pauseState
            state.pauseState = null
            if (wasPaused?.resumesLive == true) {
                state.liveToast = true
                delay(LIVE_TOAST_MS)
                state.liveToast = false
            }
            return@LaunchedEffect
        }
        val pausedAt = uptimeMs()
        while (true) {
            state.pauseState = pauseStatus(pausedAt, uptimeMs())
            delay(1_000)
        }
    }
    // The bar stays up while paused, as the library player's does.
    LaunchedEffect(state.overlayVisible, state.overlayRequest, paused) {
        if (!state.overlayVisible || paused) return@LaunchedEffect
        delay(BAR_IDLE_MS)
        state.overlayVisible = false
    }
    LaunchedEffect(state.captionToast) {
        val toast = state.captionToast ?: return@LaunchedEffect
        delay(if (toast.timingMs != null) CAPTION_TIMING_WINDOW_MS else CAPTION_TOAST_MS)
        state.captionToast = null
    }
}
