package dev.jamesrich.sofaking.ui.player

import dev.jamesrich.sofaking.ui.appLifecycleOwner
import dev.jamesrich.sofaking.ui.BackHandler
import dev.jamesrich.sofaking.contract.Wire
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.input.key.KeyEvent
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
import dev.jamesrich.sofaking.ui.isOk
import dev.jamesrich.sofaking.ui.KeepScreenOn
import dev.jamesrich.sofaking.ui.formatTemplate
import dev.jamesrich.sofaking.ui.isLongPress
import dev.jamesrich.sofaking.ui.repeatCountOrNull
import dev.jamesrich.sofaking.resources.*
import dev.jamesrich.sofaking.data.AppSettings
import dev.jamesrich.sofaking.data.CaptionStyle
import dev.jamesrich.sofaking.data.Channel
import dev.jamesrich.sofaking.data.Lineup
import dev.jamesrich.sofaking.data.PlaybackIntent
import dev.jamesrich.sofaking.data.PlaybackSessions
import dev.jamesrich.sofaking.data.SofaKingException
import dev.jamesrich.sofaking.ui.PopIn
import dev.jamesrich.sofaking.ui.PopOut
import dev.jamesrich.sofaking.ui.RiseIn
import dev.jamesrich.sofaking.ui.label
import dev.jamesrich.sofaking.ui.rememberMinuteNow
import dev.jamesrich.sofaking.ui.rememberSettled
import dev.jamesrich.sofaking.ui.theme.SofaScrim
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.fillMaxWidth
import dev.jamesrich.sofaking.ui.uptimeMs
import androidx.compose.ui.input.pointer.pointerInput
import dev.jamesrich.sofaking.ui.InputKind
import dev.jamesrich.sofaking.ui.LocalLastInput
import dev.jamesrich.sofaking.ui.screenPadding

private const val ZAP_OVERLAY_MS = 5_000L
private const val CAPTION_TOAST_MS = 2_000L
/** How long after the CC key Left and Right keep adjusting the channel's caption timing; each press restarts it. */
internal const val CAPTION_TIMING_WINDOW_MS = 3_000L
private const val MINI_GUIDE_IDLE_MS = 8_000L
private const val STREAM_CARD_IDLE_MS = 15_000L
private const val LIVE_TOAST_MS = 1_500L
/** A swipe up this far on a touch screen opens the channel list. */
private const val SWIPE_UP_DP = 80
private const val STREAM_ENDED = "STREAM_ENDED"
/** The server's answer when a session is asked for a channel id its lineup no longer has. */
internal const val CHANNEL_NOT_FOUND = Wire.ErrorCode.CHANNEL_NOT_FOUND
/** How long the player waits for the lineup it asked for to catch up with the one it holds. */
private const val LINEUP_SYNC_MS = 2_000L
private const val SESSION_UNAVAILABLE = "SESSION_UNAVAILABLE"
private val log = Logger.withTag("SofaKing")

/**
 * Caption toggle feedback; compared by identity so a repeated press restarts its timer. While [timingMs] is set,
 * Left and Right adjust the channel's caption timing and the toast shows where it is.
 */
private class CaptionToast(val outcome: CaptionToggleOutcome, val timingMs: Int?)

/**
 * A stream that failed or ended; [attempt] counts the automatic reconnects made for it so far. [immediate]
 * marks a 410 from the relay, whose first reconnect re-reads the session without waiting for the backoff.
 */
private data class Failure(val code: String, val attempt: Int, val immediate: Boolean = false)

/** How long a zap rests before the new channel is tuned, so pressing through channels does not open a session per press. */
internal const val ZAP_SETTLE_MS = 400L

/** A zap from a tuned channel waits [ZAP_SETTLE_MS]; the first tune, which may take over the preview's stream, does not. */
internal fun zapSettleMs(tuned: Channel?, next: Channel): Long =
    if (tuned == null || tuned.guideId == next.guideId) 0L else ZAP_SETTLE_MS

/** The tune in flight, so a zap cancels the previous channel's session request before it lands on the player. */
private class TuneJob {
    var job: Job? = null
}


/** The zap the system media session asks for, pointed at the current composition's handler. */
private class ZapRelay {
    var onStep: (Int) -> Unit = {}
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
) {
    val captionSync = player.captionSync
    val nowPlayingTemplate = stringResource(Res.string.now_playing)
    val nowPlayingChannelTemplate = stringResource(Res.string.now_playing_channel)
    val startChannel = lineup.channels.getOrNull(startIndex)
    // Up, Down and the channel list stay in the guide's filter until a number typed outside it, or a channel
    // being followed after a re-import, widens them to the whole lineup.
    var widened by remember { mutableStateOf(zapScope != null && startChannel?.guideId !in zapScope.guideIds) }
    val channels = remember(lineup.channels, zapScope, widened) { zapRing(lineup.channels, zapScope, widened) }
    val scoped = channels.size < lineup.channels.size
    val currentChannels by rememberUpdatedState(channels)
    val currentLineupChannels by rememberUpdatedState(lineup.channels)
    // A channel number typed outside the scope, tuned once the ring has widened.
    var pendingNumber by remember { mutableStateOf<String?>(null) }
    val currentOnChannelChanged by rememberUpdatedState(onChannelChanged)
    val currentOnCaptionsChanged by rememberUpdatedState(onCaptionsChanged)
    val currentOnCaptionAdvanceChanged by rememberUpdatedState(onCaptionAdvanceChanged)
    val currentOnKeyLegendShown by rememberUpdatedState(onKeyLegendShown)
    val currentOnToggleFavorite by rememberUpdatedState(onToggleFavorite)
    val windowHeightDp = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp().value.toInt() }
    val captionBottomFraction = CaptionPolicy.bottomPaddingFraction(windowHeightDp)
    var index by remember { mutableIntStateOf(channels.indexOfFirst { it.guideId == startChannel?.guideId }.coerceAtLeast(0)) }
    // What load() last tuned and what was watched before it, so a refreshed lineup can be followed by identity.
    var tuned by remember { mutableStateOf<Channel?>(null) }
    var previousChannel by remember { mutableStateOf(lineup.channels.firstOrNull { it.guideId == previousGuideId }) }
    var previousIndex by remember { mutableStateOf(channels.indexOfFirst { it.guideId == previousGuideId }.takeIf { it >= 0 }) }
    val numbers = rememberChannelNumberInput()
    var overlayVisible by remember { mutableStateOf(true) }
    var overlayRequest by remember { mutableIntStateOf(0) }
    // The shared player may arrive already playing the channel from the guide preview.
    val status by player.status.collectAsStateWithLifecycle()
    val playing = status.isPlaying
    val buffering = status.state == PlaybackState.BUFFERING
    val paused = !status.playWhenReady
    var failure by remember { mutableStateOf<Failure?>(null) }
    // The channel whose id the server no longer knows, while the lineup is reloaded to find it again.
    var recovering by remember { mutableStateOf<Channel?>(null) }
    var captionsOn by remember { mutableStateOf(captionsEnabled) }
    var captionToast by remember { mutableStateOf<CaptionToast?>(null) }
    var miniGuideOpen by remember { mutableStateOf(false) }
    // Bumped on every key while the mini-guide is open, so its idle timer restarts.
    var miniGuideActivity by remember { mutableIntStateOf(0) }
    // True while a chip of the channel list has focus, so Left and Right move along the chips.
    var miniGuideChipsFocused by remember { mutableStateOf(false) }
    var streamCardOpen by remember { mutableStateOf(false) }
    var streamCardActivity by remember { mutableIntStateOf(0) }
    var streamInfo by remember { mutableStateOf(StreamInfo()) }
    var streamBufferedMs by remember { mutableStateOf(0L) }
    val audioTracks by player.audioTracks.collectAsStateWithLifecycle()
    val heldStream by sessions.current.collectAsStateWithLifecycle()
    // Seconds to the next automatic reconnect, 0 while it runs, null with none scheduled.
    var retryIn by remember { mutableStateOf<Int?>(null) }
    var pauseState by remember { mutableStateOf<PauseStatus?>(null) }
    var liveToast by remember { mutableStateOf(false) }
    // The key legend rides the first few info bars after install, counted once per bar.
    var legendCount by remember { mutableIntStateOf(keyLegendShown) }
    var legendOnThisBar by remember { mutableStateOf(false) }
    val centerGesture = remember { CenterKeyGesture() }
    val infoGesture = remember { CenterKeyGesture() }
    val pointerDriven = LocalLastInput.current.kind == InputKind.POINTER
    val now by rememberMinuteNow()
    val scope = rememberCoroutineScope()
    val tuneJob = remember { TuneJob() }

    // Each channel's captioner runs a different distance behind; adjustments show before the store round-trips.
    var advanceOverrides by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    val currentChannel = currentChannels.getOrNull(index)
    val channelAdvance = currentChannel?.let { advanceOverrides[it.guideId] ?: captionAdvanceFor(it) } ?: 0
    val captionLead = if (captionsOn && player.supportsCaptionLead) channelAdvance * 1_000L else 0L
    SideEffect {
        captionSync.advanceUs = captionLead
        captionSync.smoothingEnabled = smoothCaptions
    }

    // The system's next/previous/play/pause (MediaSession, media keys) reach the player through this session.
    val zapRelay = remember { ZapRelay() }
    // An auto-opened full screen lets the screen sleep (ambient mode) until someone presses a key or taps.
    var attended by remember { mutableStateOf(!unattended) }
    // Opened and closed by the effect, never during composition; the publishers below all run after it. It is
    // open only while the app is in front, as the app stops the player behind another app.
    var mediaSession by remember { mutableStateOf<MediaSessionHandle?>(null) }
    LifecycleStartEffect(player, lifecycleOwner = appLifecycleOwner) {
        val session = player.openMediaSession { step -> zapRelay.onStep(step) }
        mediaSession = session
        player.volume = 1f
        onStopOrDispose {
            session.close()
            mediaSession = null
        }
    }
    LaunchedEffect(player) {
        player.status.collect { s ->
            if (s.isPlaying || s.state == PlaybackState.READY) failure = null
            // A live stream never ends; the server closed the connection, so treat it as a failure and reconnect.
            if (s.state == PlaybackState.ENDED) failure = Failure(STREAM_ENDED, attempt = failure?.attempt ?: 0)
        }
    }
    LaunchedEffect(player) {
        player.errors.collect { error ->
            failure = Failure(error.code, attempt = failure?.attempt ?: 0, immediate = error.streamGone)
        }
    }

    fun nowPlayingFor(channel: Channel) = NowPlaying(
        title = lineup.nowAndNext(channel, now).first?.title ?: channel.name,
        subtitle = channel.label(),
        artworkUrl = channel.logo,
    )

    fun load(channel: Channel) {
        val metadata = nowPlayingFor(channel)
        mediaSession?.publish(metadata)
        tuneJob.job?.cancel()
        // Handed over from the guide preview mid-stream: keep it, and let the caption lead grow back from zero.
        // The same session is promoted to watch, so the stream continues and its lease grows to 90 s.
        val state = player.status.value.state
        if (player.currentChannelId == channel.guideId && (state == PlaybackState.READY || state == PlaybackState.BUFFERING)) {
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
            val stream = try {
                sessions.tune(channel.guideId, PlaybackIntent.WATCH)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The reconnect loop below tunes again with backoff, as it does for a stream that failed.
                failure = Failure((e as? SofaKingException)?.code ?: SESSION_UNAVAILABLE, attempt = failure?.attempt ?: 0)
                return@launch
            }
            player.setStream(stream, metadata)
            player.prepare()
        }
    }

    fun zapTo(target: Int) {
        numbers.clear()
        failure = null
        recovering = null
        if (target != index) {
            previousIndex = index
            previousChannel = currentChannels.getOrNull(index)
            index = target
        }
        overlayVisible = true
        overlayRequest++
        miniGuideOpen = false
    }

    /** Moves [step] channels through the lineup, wrapping at its ends; positive is down the guide, as [PlayerAction.zapStep] gives it. */
    fun zapStep(step: Int) {
        val size = currentChannels.size
        if (size > 0) zapTo((index + step).mod(size))
    }

    /** Last-channel recall; with nothing watched before, it just shows the info overlay. */
    fun recallLastChannel() {
        val previous = previousIndex
        if (previous != null) {
            zapTo(previous)
        } else {
            overlayVisible = true
            overlayRequest++
        }
    }

    SideEffect { zapRelay.onStep = { step -> zapStep(step) } }

    /** Typed digits name a channel in the ring, one outside it (the ring widens first), or no channel at all. */
    fun commitEntry(pending: ChannelNumberEntry) {
        val target = pending.indexIn(currentChannels)
        val outside = if (target == null) pending.indexIn(currentLineupChannels)?.let { currentLineupChannels[it] } else null
        when {
            target != null -> zapTo(target)
            outside != null -> {
                pendingNumber = outside.guideId
                widened = true
            }
            else -> numbers.missed(pending.digits)
        }
    }
    SideEffect { numbers.onCommit = ::commitEntry }

    // The lineup refreshes every few hours and may reorder or grow; the tuned channel keeps its stream and
    // its row follows it. A channel that vanished stays playing on the nearest row.
    // The same holds when the ring widens from the guide's filter to the whole lineup.
    LaunchedEffect(channels) {
        if (recovering != null) return@LaunchedEffect
        val current = tuned ?: startChannel
        if (current != null) {
            index = indexAfterRefresh(channels, current) ?: index.coerceIn(0, (channels.size - 1).coerceAtLeast(0))
            previousIndex = previousChannel?.let { indexAfterRefresh(channels, it) }
        }
        val number = pendingNumber ?: return@LaunchedEffect
        pendingNumber = null
        channels.indexOfFirst { it.guideId == number }.takeIf { it >= 0 }?.let(::zapTo)
    }

    val lifecycle = appLifecycleOwner.lifecycle
    LaunchedEffect(index, currentChannel?.guideId, lifecycle, recovering) {
        // While a dead id is being followed, nothing tunes until the row for its replacement is known.
        if (recovering != null) return@LaunchedEffect
        val channel = currentChannel ?: return@LaunchedEffect
        // Channels passed under quick presses are never tuned; the info bar follows each press at once.
        delay(zapSettleMs(tuned, channel))
        lifecycle.withStateAtLeast(Lifecycle.State.STARTED) {
            val before = tuned
            tuned = channel
            // A refresh that keeps the channel preserves its stream and session.
            if (before?.guideId != channel.guideId) {
                load(channel)
                currentOnChannelChanged(channel)
            }
        }
    }

    // Moves the session's programme title on with the minute clock when the show changes; the stream is not touched.
    LaunchedEffect(index, now, mediaSession) {
        val channel = currentChannels.getOrNull(index) ?: return@LaunchedEffect
        mediaSession?.publish(nowPlayingFor(channel))
    }

    LaunchedEffect(miniGuideOpen, miniGuideActivity) {
        if (!miniGuideOpen) return@LaunchedEffect
        delay(MINI_GUIDE_IDLE_MS)
        miniGuideOpen = false
    }
    LaunchedEffect(streamCardOpen, streamCardActivity) {
        if (!streamCardOpen) return@LaunchedEffect
        delay(STREAM_CARD_IDLE_MS)
        streamCardOpen = false
    }
    // The card's figures move while it is open: the buffer every second, the formats when the engine changes them.
    LaunchedEffect(streamCardOpen, player) {
        while (streamCardOpen) {
            streamInfo = player.streamInfo()
            streamBufferedMs = player.bufferedMs
            delay(1_000)
        }
    }
    // The pause pill counts how far behind live the picture is; a resume past the live-resume rule says "Live" for a moment.
    LaunchedEffect(paused) {
        if (!paused) {
            val wasPaused = pauseState
            pauseState = null
            if (wasPaused?.resumesLive == true) {
                liveToast = true
                delay(LIVE_TOAST_MS)
                liveToast = false
            }
            return@LaunchedEffect
        }
        val pausedAt = uptimeMs()
        while (true) {
            pauseState = pauseStatus(pausedAt, uptimeMs())
            delay(1_000)
        }
    }

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
    LaunchedEffect(failure) {
        val current = failure ?: run {
            recovering = null
            return@LaunchedEffect
        }
        if (current.code == CHANNEL_NOT_FOUND) {
            // The server's lineup has new ids (a re-import): retrying this one would fail forever. Reload the
            // lineup and follow the channel by number, then name; with nothing to follow, the guide takes over.
            val channel = currentChannels.getOrNull(index) ?: return@LaunchedEffect
            recovering = channel
            // The replacement is looked for in the whole lineup; the ring's ids are the ones that went stale.
            if (!widened) {
                widened = true
                index = currentLineupChannels.indexOfFirst { it.guideId == channel.guideId }.coerceAtLeast(0)
            }
            var attempt = current.attempt
            var reloaded = reloadLineup()
            while (reloaded == null) {
                // Unreachable or timed out: the reload, not the dead id, is what backs off and tries again.
                delay(reconnectDelayMs(attempt++))
                lifecycle.withStateAtLeast(Lifecycle.State.STARTED) {}
                reloaded = reloadLineup()
            }
            withTimeoutOrNull(LINEUP_SYNC_MS) { snapshotFlow { currentChannels }.first { it == reloaded.channels } }
            val target = resolveReplacement(currentChannels, channel)
            if (target == null) {
                onChannelGone(channel)
            } else {
                index = target
                failure = null
                recovering = null
            }
            return@LaunchedEffect
        }
        if (!(current.immediate && current.attempt == 0)) {
            // The card counts the wait down a second at a time.
            val until = uptimeMs() + reconnectDelayMs(current.attempt)
            while (true) {
                val remaining = until - uptimeMs()
                if (remaining <= 0L) break
                retryIn = ((remaining + 999L) / 1_000L).toInt()
                delay(minOf(remaining, 1_000L))
            }
        }
        retryIn = 0
        lifecycle.withStateAtLeast(Lifecycle.State.STARTED) {}
        val channel = currentChannels.getOrNull(index) ?: return@LaunchedEffect
        failure = current.copy(attempt = current.attempt + 1)
        load(channel)
    }
    LaunchedEffect(failure == null) { if (failure == null) retryIn = null }

    LaunchedEffect(overlayVisible, overlayRequest) {
        if (!overlayVisible) return@LaunchedEffect
        delay(ZAP_OVERLAY_MS)
        overlayVisible = false
    }
    LaunchedEffect(overlayVisible) {
        if (!overlayVisible || pointerDriven) return@LaunchedEffect
        legendOnThisBar = legendCount < AppSettings.KEY_LEGEND_BARS
        if (legendOnThisBar) currentOnKeyLegendShown(++legendCount)
    }

    LaunchedEffect(captionToast) {
        val toast = captionToast ?: return@LaunchedEffect
        delay(if (toast.timingMs != null) CAPTION_TIMING_WINDOW_MS else CAPTION_TOAST_MS)
        captionToast = null
    }

    val focus = remember { FocusRequester() }
    // Focus returns to the player surface whenever a panel closes, including after its list is removed.
    LaunchedEffect(miniGuideOpen, streamCardOpen) { if (!miniGuideOpen && !streamCardOpen) focus.requestFocus() }
    var swallowBackUp by remember { mutableStateOf(false) }
    BackHandler {
        when {
            // The press that closed a panel ends here: with the panel's focused row gone, its key-up can miss the
            // surface's key handler and arrive as a Back of its own.
            swallowBackUp -> swallowBackUp = false
            streamCardOpen -> streamCardOpen = false
            miniGuideOpen -> miniGuideOpen = false
            else -> onExit()
        }
    }

    /** The timing Left and Right adjust right after the CC key, where the engine applies one. */
    fun timingToast(enabled: Boolean, outcome: CaptionToggleOutcome, timingMs: Int) =
        CaptionToast(outcome, timingMs.takeIf { enabled && player.supportsCaptionLead })

    fun toggleCaptions() {
        val enabled = !captionsOn
        captionsOn = enabled
        player.setCaptionsEnabled(enabled)
        val channelHasText = player.hasTextTrack
        captionToast = timingToast(enabled, CaptionPolicy.toggleOutcome(enabled, channelHasText), channelAdvance)
        currentOnCaptionsChanged(enabled)
    }

    /** One step earlier (Right) or later (Left) for this channel; the toast restarts its window at the new timing. */
    fun adjustCaptionTiming(direction: Int) {
        val channel = currentChannels.getOrNull(index) ?: return
        val toast = captionToast ?: return
        val next = CaptionPolicy.stepAdvance(channelAdvance, direction)
        advanceOverrides = advanceOverrides + (channel.guideId to next)
        currentOnCaptionAdvanceChanged(channel, next)
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

    fun showInfo() {
        overlayVisible = true
        overlayRequest++
    }

    /** A row of the channel list: a channel in the ring tunes at once, one outside it widens the ring first. */
    fun tuneChannel(channel: Channel) {
        val target = currentChannels.indexOfFirst { it.guideId == channel.guideId }
        if (target >= 0) {
            zapTo(target)
        } else {
            miniGuideOpen = false
            pendingNumber = channel.guideId
            widened = true
        }
    }

    /** A failed channel tunes again now, however many automatic retries have run. */
    fun retry() {
        val channel = currentChannels.getOrNull(index) ?: return
        failure = null
        recovering = null
        load(channel)
    }

    /** OK is judged on key-up: a short press acts as [playerOk] decides, a long press recalls the last channel. */
    fun handleCenter(event: KeyEvent): Boolean {
        // With the mini-guide open, its focused row, chip or button takes OK; the press still counts against its idle timer.
        if (miniGuideOpen) {
            miniGuideActivity++
            return false
        }
        if (currentChannels.isEmpty()) return false
        return when (event.type) {
            KeyEventType.KeyDown -> {
                if (centerGesture.onKeyDown(event.isLongPress, event.repeatCountOrNull) == CenterPress.LONG) recallLastChannel()
                true
            }
            KeyEventType.KeyUp -> {
                if (centerGesture.onKeyUp() == CenterPress.SHORT) {
                    val channel = currentChannels.getOrNull(index)
                    when (playerOk(numbers.hasDigits, failure != null && channel != null, overlayVisible)) {
                        PlayerOk.COMMIT_NUMBER -> numbers.commit()
                        PlayerOk.RETRY -> retry()
                        PlayerOk.OPEN_CHANNEL_LIST -> openMiniGuide()
                        PlayerOk.SHOW_INFO -> {
                            overlayVisible = true
                            overlayRequest++
                        }
                    }
                }
                true
            }
            else -> false
        }
    }

    fun handleKey(key: Key): Boolean {
        if (currentChannels.isEmpty()) return false
        when (key.playerExit()) {
            PlayerExit.GUIDE_AT_NOW -> {
                onGuide()
                return true
            }
            PlayerExit.SEARCH -> {
                onSearch()
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
            PlayerAction.TOGGLE_CAPTIONS -> toggleCaptions()
            PlayerAction.ZAP_UP, PlayerAction.ZAP_DOWN, PlayerAction.CHANNEL_UP, PlayerAction.CHANNEL_DOWN -> zapStep(action.zapStep()!!)
            PlayerAction.LAST_CHANNEL -> recallLastChannel()
            PlayerAction.PLAY_PAUSE -> if (player.playWhenReady) player.pause() else player.play()
            PlayerAction.PLAY -> player.play()
            PlayerAction.PAUSE -> player.pause()
            PlayerAction.TOGGLE_INFO -> if (!numbers.hasDigits) overlayVisible = !overlayVisible
            PlayerAction.LEFT, PlayerAction.RIGHT -> {
                val adjustable = captionToast?.timingMs != null && captionsOn
                when (playerSideKey(adjustable, overlayVisible)) {
                    // Right shows captions earlier, Left later.
                    PlayerSideKey.ADJUST_CAPTION_TIMING -> adjustCaptionTiming(if (action == PlayerAction.RIGHT) 1 else -1)
                    PlayerSideKey.OPEN_CHANNEL_LIST -> openMiniGuide()
                    PlayerSideKey.SHOW_INFO -> showInfo()
                }
            }
        }
        return true
    }

    /** Info is judged on key-up: a short press toggles the info bar, a long press opens the stream card. */
    fun handleInfo(event: KeyEvent): Boolean {
        when (event.type) {
            KeyEventType.KeyDown -> if (infoGesture.onKeyDown(event.isLongPress, event.repeatCountOrNull) == CenterPress.LONG) openStreamCard()
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

    // A tap or click on the video: the channel list closes, or the info bar and its touch controls toggle. Focus
    // stays on the player surface, so the remote and keyboard keep working after a pointer has been used.
    fun handleTap() {
        if (streamCardOpen) {
            streamCardOpen = false
            focus.requestFocus()
            return
        }
        when (playerTap(miniGuideOpen, overlayVisible)) {
            PlayerTap.CLOSE_CHANNEL_LIST -> miniGuideOpen = false
            PlayerTap.HIDE_INFO -> overlayVisible = false
            PlayerTap.SHOW_INFO -> showInfo()
        }
        focus.requestFocus()
    }
    val currentHandleTap by rememberUpdatedState(::handleTap)
    val currentOpenMiniGuide by rememberUpdatedState(::openMiniGuide)

    /** A touch control's action, after which the info bar stays up for another while and the surface keeps focus. */
    fun control(action: () -> Unit): () -> Unit = {
        action()
        overlayRequest++
        focus.requestFocus()
    }

    fun handleKeyEvent(event: KeyEvent): Boolean {
        val key = event.key
        // The mini-guide's focused list swallows Back before BackHandler sees it, so close it here and
        // eat the matching key-up so the same press can't also leave the player.
        if (key == Key.Back) {
            if (event.type == KeyEventType.KeyDown && (miniGuideOpen || streamCardOpen)) {
                miniGuideOpen = false
                streamCardOpen = false
                swallowBackUp = true
                return true
            }
            if (event.type == KeyEventType.KeyUp && swallowBackUp) {
                swallowBackUp = false
                return true
            }
        }
        if (key == Key.Info) return handleInfo(event)
        if (key.isOk) {
            if (streamCardOpen) {
                // The focused track row takes OK; with nothing to pick, OK closes the card on its key-up.
                if (streamCardOk(audioTracks.isNotEmpty()) == StreamCardKey.CARD) return false
                if (event.type == KeyEventType.KeyUp) streamCardOpen = false
                return true
            }
            return handleCenter(event)
        }
        if (event.type != KeyEventType.KeyDown || currentChannels.isEmpty()) return false
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
                    handleKey(key)
                }
            }
        }
        if (!miniGuideOpen) return handleKey(key)
        miniGuideActivity++
        return when (key.inMiniGuide(miniGuideChipsFocused)) {
            // The focused row or chip handles OK.
            MiniGuideKey.LIST -> false
            MiniGuideKey.SWALLOWED -> true
            MiniGuideKey.PLAYER -> handleKey(key)
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
                currentChannels.getOrNull(index)?.let { channel ->
                    val title = lineup.nowAndNext(channel, now).first?.title
                    contentDescription = if (title == null) {
                        formatTemplate(nowPlayingChannelTemplate, channel.label())
                    } else {
                        formatTemplate(nowPlayingTemplate, channel.label(), title)
                    }
                }
            }
            .onPreviewKeyEvent { event ->
                attended = true
                handleKeyEvent(event)
            }
            .pointerInput(Unit) {
                detectTapGestures(onTap = {
                    attended = true
                    currentHandleTap()
                })
            }
            // A swipe up on a touch screen opens the channel list, the way a second OK does on the remote.
            .pointerInput(Unit) {
                var dragged = 0f
                detectVerticalDragGestures(
                    onDragStart = { dragged = 0f },
                    onDragEnd = { if (dragged < -SWIPE_UP_DP.dp.toPx()) currentOpenMiniGuide() },
                    onVerticalDrag = { _, dy -> dragged += dy },
                )
            },
    ) {
        player.Video(Modifier.fillMaxSize()) { Box(Modifier.fillMaxSize().background(SofaScrim)) }
        Captions(player, captionStyle, captionBottomFraction, Modifier.fillMaxSize())
        KeepScreenOn(playing && attended)

        val channel = currentChannels.getOrNull(index)
        if (channel == null) {
            Text(stringResource(Res.string.no_channels), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.align(Alignment.Center))
        } else {
            val popIn = PopIn
            val popOut = PopOut
            // Status pills stack top left so a caption toast never covers "Paused" or "Tuning".
            Column(
                Modifier.align(Alignment.TopStart).screenPadding(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AnimatedVisibility(rememberSettled(buffering && failure == null), enter = popIn, exit = popOut) {
                    StatusPill(stringResource(Res.string.tuning), busy = true)
                }
                AnimatedVisibility(paused && failure == null, enter = popIn, exit = popOut) {
                    PausePill(pauseState)
                }
                AnimatedVisibility(liveToast && !paused, enter = popIn, exit = popOut) {
                    StatusPill(stringResource(Res.string.player_live), MaterialTheme.typography.titleLarge)
                }
                AnimatedContent(
                    targetState = captionToast,
                    transitionSpec = { popIn togetherWith popOut },
                    label = "captionToast",
                ) { toast ->
                    if (toast != null) CaptionToastPill(toast.outcome, toast.timingMs)
                }
            }
            failure?.let { FailureCard(it.code) { retryIn } }
            ChannelNumberBadge(numbers)
            AnimatedVisibility(streamCardOpen, enter = PopIn, exit = ExitTransition.None) {
                Box(Modifier.fillMaxSize()) {
                    StreamCard(
                        tracks = audioTracks,
                        info = streamInfo,
                        stream = heldStream,
                        bufferedMs = streamBufferedMs,
                        onSelectTrack = { id ->
                            player.selectAudioTrack(id)
                            streamCardActivity++
                        },
                    )
                }
            }
            // The channel list arrives on a spring but leaves at once: while it animated out it would still
            // hold focus, and a quick second Back or Down would land in the list instead of the player.
            AnimatedVisibility(miniGuideOpen, enter = RiseIn, exit = ExitTransition.None) {
                Box(Modifier.fillMaxSize()) {
                    MiniGuide(
                        lineup = lineup,
                        scope = zapScope?.takeIf { scoped },
                        favorites = favorites,
                        favoritesOrder = favoritesOrder,
                        recent = recentGuideIds,
                        currentGuideId = channel.guideId,
                        previousGuideId = previousChannel?.guideId,
                        now = now,
                        onTune = ::tuneChannel,
                        onChipsFocused = { miniGuideChipsFocused = it },
                        actions = MiniGuideActions(
                            captionsOn = captionsOn,
                            paused = paused,
                            failed = failure != null,
                            hasAudioChoice = audioTracks.isNotEmpty(),
                            favorite = if (onToggleFavorite != null) channel.guideId in favorites else null,
                            onToggleFavorite = { currentOnToggleFavorite?.invoke(channel) },
                            // Captions close the list so Left and Right can move the timing while the toast shows.
                            onCaptions = {
                                toggleCaptions()
                                miniGuideOpen = false
                            },
                            onPlayPause = { if (player.playWhenReady) player.pause() else player.play() },
                            onRetry = {
                                retry()
                                miniGuideOpen = false
                            },
                            onStreamInfo = ::openStreamCard,
                        ),
                    )
                }
            }
            InfoBar(overlayVisible && !miniGuideOpen && !streamCardOpen) {
                val scopeLabel = zapScope?.takeIf { scoped }?.let { stringResource(Res.string.zap_scope, it.label, index + 1, channels.size) }
                InfoOverlay(
                    lineup = lineup,
                    channel = channel,
                    now = now,
                    captionsOn = captionsOn,
                    captionAdvanceMs = channelAdvance.takeIf { player.supportsCaptionLead },
                    scope = scopeLabel,
                    showKeyLegend = legendOnThisBar && !pointerDriven,
                )
                if (pointerDriven) {
                    TouchControls(
                        captionsOn = captionsOn,
                        paused = paused,
                        failed = failure != null,
                        hasAudioChoice = audioTracks.isNotEmpty(),
                        onGuide = onExit,
                        onChannelUp = control { zapStep(PlayerAction.CHANNEL_UP.zapStep()!!) },
                        onChannelDown = control { zapStep(PlayerAction.CHANNEL_DOWN.zapStep()!!) },
                        onChannelList = ::openMiniGuide,
                        onCaptions = control { toggleCaptions() },
                        onPlayPause = control { if (player.playWhenReady) player.pause() else player.play() },
                        onStreamInfo = ::openStreamCard,
                        onRetry = control { retry() },
                    )
                }
            }
        }
    }
}
