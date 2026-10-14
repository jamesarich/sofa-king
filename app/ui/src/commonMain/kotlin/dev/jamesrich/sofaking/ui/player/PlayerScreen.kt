package dev.jamesrich.sofaking.ui.player

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
import androidx.compose.runtime.DisposableEffect
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
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.withStateAtLeast
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import co.touchlab.kermit.Logger
import dev.jamesrich.sofaking.ui.KeepScreenOn
import dev.jamesrich.sofaking.ui.formatTemplate
import dev.jamesrich.sofaking.ui.isLongPress
import dev.jamesrich.sofaking.ui.repeatCountOrNull
import kotlin.time.Clock
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
import dev.jamesrich.sofaking.ui.SinkOut
import dev.jamesrich.sofaking.ui.label
import dev.jamesrich.sofaking.ui.rememberMinuteNow
import dev.jamesrich.sofaking.ui.rememberSettled
import dev.jamesrich.sofaking.ui.theme.SofaDusk
import dev.jamesrich.sofaking.ui.theme.SofaScrim
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import dev.jamesrich.sofaking.ui.InputKind
import dev.jamesrich.sofaking.ui.LocalLastInput
import dev.jamesrich.sofaking.ui.screenPadding

private const val ZAP_OVERLAY_MS = 5_000L
private const val DIGIT_COMMIT_MS = 1_500L
private const val NOT_FOUND_MS = 2_000L
private const val CAPTION_TOAST_MS = 2_000L
private const val MINI_GUIDE_IDLE_MS = 8_000L
private const val METADATA_REFRESH_MS = 60_000L
private const val STREAM_ENDED = "STREAM_ENDED"
/** The server's answer when a session is asked for a channel id its lineup no longer has. */
internal const val CHANNEL_NOT_FOUND = Wire.ErrorCode.CHANNEL_NOT_FOUND
/** How long the player waits for the lineup it asked for to catch up with the one it holds. */
private const val LINEUP_SYNC_MS = 2_000L
private const val SESSION_UNAVAILABLE = "SESSION_UNAVAILABLE"
private val log = Logger.withTag("SofaKing")

/** Digit-entry miss; compared by identity so a repeated miss restarts its timer. */
private class Notice(val digits: String)

/** Caption toggle feedback; compared by identity so a repeated press restarts its timer. */
private class CaptionToast(val outcome: CaptionToggleOutcome)

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

@Composable
fun PlayerScreen(
    lineup: Lineup,
    startIndex: Int,
    /** The channel watched before this player opened, for Last; it may have been left through the guide. */
    previousGuideId: String? = null,
    /** The guide filter the channel was picked from; null cycles the whole lineup. */
    zapScope: ZapScope? = null,
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
    val windowHeightDp = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp().value.toInt() }
    val captionBottomFraction = CaptionPolicy.bottomPaddingFraction(windowHeightDp)
    var index by remember { mutableIntStateOf(channels.indexOfFirst { it.guideId == startChannel?.guideId }.coerceAtLeast(0)) }
    // What load() last tuned and what was watched before it, so a refreshed lineup can be followed by identity.
    var tuned by remember { mutableStateOf<Channel?>(null) }
    var previousChannel by remember { mutableStateOf(lineup.channels.firstOrNull { it.guideId == previousGuideId }) }
    var previousIndex by remember { mutableStateOf(channels.indexOfFirst { it.guideId == previousGuideId }.takeIf { it >= 0 }) }
    var entry by remember { mutableStateOf(ChannelNumberEntry()) }
    var notice by remember { mutableStateOf<Notice?>(null) }
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
    val centerGesture = remember { CenterKeyGesture() }
    val now by rememberMinuteNow()
    val scope = rememberCoroutineScope()
    val tuneJob = remember { TuneJob() }

    // Each channel's captioner runs a different distance behind; adjustments show before the store round-trips.
    var advanceOverrides by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    val currentChannel = currentChannels.getOrNull(index)
    val channelAdvance = currentChannel?.let { advanceOverrides[it.guideId] ?: captionAdvanceFor(it) } ?: 0
    val captionLead = if (captionsOn) channelAdvance * 1_000L else 0L
    SideEffect {
        captionSync.advanceUs = captionLead
        captionSync.smoothingEnabled = smoothCaptions
    }

    // The system's next/previous/play/pause (MediaSession, media keys) reach the player through this session.
    val zapRelay = remember { ZapRelay() }
    // Opened and closed by the effect, never during composition; the publishers below all run after it.
    var mediaSession by remember { mutableStateOf<MediaSessionHandle?>(null) }
    DisposableEffect(player) {
        val session = player.openMediaSession { step -> zapRelay.onStep(step) }
        mediaSession = session
        player.volume = 1f
        onDispose {
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
        title = lineup.nowAndNext(channel, Clock.System.now()).first?.title ?: channel.name,
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
        entry = ChannelNumberEntry()
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

    fun commitEntry() {
        val pending = entry
        if (pending.digits.isEmpty()) return
        val target = pending.indexIn(currentChannels)
        val outside = if (target == null) pending.indexIn(currentLineupChannels)?.let { currentLineupChannels[it] } else null
        if (target != null) {
            zapTo(target)
        } else if (outside != null) {
            entry = ChannelNumberEntry()
            pendingNumber = outside.guideId
            widened = true
        } else {
            entry = ChannelNumberEntry()
            notice = Notice(pending.digits)
        }
    }

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

    val lifecycle = LocalLifecycleOwner.current.lifecycle
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

    // Moves the session's programme title on when the show changes; the stream is not touched.
    LaunchedEffect(index) {
        while (true) {
            delay(METADATA_REFRESH_MS)
            val channel = currentChannels.getOrNull(index) ?: continue
            mediaSession?.publish(nowPlayingFor(channel))
        }
    }

    LaunchedEffect(miniGuideOpen, miniGuideActivity) {
        if (!miniGuideOpen) return@LaunchedEffect
        delay(MINI_GUIDE_IDLE_MS)
        miniGuideOpen = false
    }

    // Keeps the caption look-ahead inside what's downloaded; see CaptionSync.nextEffectiveUs. Nothing runs
    // while captions are off or the channel has no advance.
    val captionLeadActive = captionLead > 0L
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
            var lineup = reloadLineup()
            while (lineup == null) {
                // Unreachable or timed out: the reload, not the dead id, is what backs off and tries again.
                delay(reconnectDelayMs(attempt++))
                lifecycle.withStateAtLeast(Lifecycle.State.STARTED) {}
                lineup = reloadLineup()
            }
            withTimeoutOrNull(LINEUP_SYNC_MS) { snapshotFlow { currentChannels }.first { it == lineup.channels } }
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
        if (!(current.immediate && current.attempt == 0)) delay(reconnectDelayMs(current.attempt))
        lifecycle.withStateAtLeast(Lifecycle.State.STARTED) {}
        val channel = currentChannels.getOrNull(index) ?: return@LaunchedEffect
        failure = current.copy(attempt = current.attempt + 1)
        load(channel)
    }

    LaunchedEffect(entry) {
        if (entry.digits.isEmpty()) return@LaunchedEffect
        delay(DIGIT_COMMIT_MS)
        commitEntry()
    }

    LaunchedEffect(notice) {
        if (notice == null) return@LaunchedEffect
        delay(NOT_FOUND_MS)
        notice = null
    }

    LaunchedEffect(overlayVisible, overlayRequest) {
        if (!overlayVisible) return@LaunchedEffect
        delay(ZAP_OVERLAY_MS)
        overlayVisible = false
    }

    LaunchedEffect(captionToast) {
        if (captionToast == null) return@LaunchedEffect
        delay(CAPTION_TOAST_MS)
        captionToast = null
    }

    val focus = remember { FocusRequester() }
    // Focus returns to the player surface whenever the mini-guide closes, including after its list is removed.
    LaunchedEffect(miniGuideOpen) { if (!miniGuideOpen) focus.requestFocus() }
    var swallowBackUp by remember { mutableStateOf(false) }
    BackHandler {
        if (miniGuideOpen) miniGuideOpen = false else onExit()
    }

    fun toggleCaptions() {
        val enabled = !captionsOn
        captionsOn = enabled
        player.setCaptionsEnabled(enabled)
        val channelHasText = player.hasTextTrack
        captionToast = CaptionToast(CaptionPolicy.toggleOutcome(enabled, channelHasText))
        currentOnCaptionsChanged(enabled)
    }

    fun openMiniGuide() {
        overlayVisible = false
        miniGuideOpen = true
        miniGuideActivity++
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
        // With the mini-guide open, its focused row takes OK.
        if (miniGuideOpen || currentChannels.isEmpty()) return false
        return when (event.type) {
            KeyEventType.KeyDown -> {
                if (centerGesture.onKeyDown(event.isLongPress, event.repeatCountOrNull) == CenterPress.LONG) recallLastChannel()
                true
            }
            KeyEventType.KeyUp -> {
                if (centerGesture.onKeyUp() == CenterPress.SHORT) {
                    val channel = currentChannels.getOrNull(index)
                    when (playerOk(entry.digits.isNotEmpty(), failure != null && channel != null, overlayVisible)) {
                        PlayerOk.COMMIT_NUMBER -> commitEntry()
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
            entry = entry.append(digit)
            return true
        }
        return when (key) {
            Key.Captions, Key.C -> { toggleCaptions(); true }
            Key.DirectionUp, Key.ChannelUp -> { zapStep(1); true }
            Key.DirectionDown, Key.ChannelDown -> { zapStep(-1); true }
            Key.MediaPrevious, Key.LastChannel -> { recallLastChannel(); true }
            Key.MediaPlayPause -> { if (player.playWhenReady) player.pause() else player.play(); true }
            Key.MediaPlay -> { player.play(); true }
            Key.MediaPause -> { player.pause(); true }
            Key.Info -> { if (entry.digits.isEmpty()) overlayVisible = !overlayVisible; true }
            Key.DirectionLeft, Key.DirectionRight -> {
                val channel = currentChannels.getOrNull(index)
                if (captionsOn && overlayVisible && channel != null) {
                    // Right shows captions earlier, Left later; the overlay stays up while adjusting.
                    val direction = if (key == Key.DirectionRight) 1 else -1
                    val next = CaptionPolicy.stepAdvance(channelAdvance, direction)
                    advanceOverrides = advanceOverrides + (channel.guideId to next)
                    currentOnCaptionAdvanceChanged(channel, next)
                    overlayRequest++
                } else {
                    overlayVisible = !overlayVisible
                }
                true
            }
            else -> false
        }
    }

    // A tap or click on the video: the channel list closes, or the info bar and its touch controls toggle. Focus
    // stays on the player surface, so the remote and keyboard keep working after a pointer has been used.
    fun handleTap() {
        when (playerTap(miniGuideOpen, overlayVisible)) {
            PlayerTap.CLOSE_CHANNEL_LIST -> miniGuideOpen = false
            PlayerTap.HIDE_INFO -> overlayVisible = false
            PlayerTap.SHOW_INFO -> {
                overlayVisible = true
                overlayRequest++
            }
        }
        focus.requestFocus()
    }
    val currentHandleTap by rememberUpdatedState(::handleTap)

    /** A touch control's action, after which the info bar stays up for another while and the surface keeps focus. */
    fun control(action: () -> Unit): () -> Unit = {
        action()
        overlayRequest++
        focus.requestFocus()
    }
    val pointerDriven = LocalLastInput.current.kind == InputKind.POINTER

    fun handleKeyEvent(event: KeyEvent): Boolean {
        val key = event.key
        // The mini-guide's focused list swallows Back before BackHandler sees it, so close it here and
        // eat the matching key-up so the same press can't also leave the player.
        if (key == Key.Back) {
            if (event.type == KeyEventType.KeyDown && miniGuideOpen) {
                miniGuideOpen = false
                swallowBackUp = true
                return true
            }
            if (event.type == KeyEventType.KeyUp && swallowBackUp) {
                swallowBackUp = false
                return true
            }
        }
        if (key == Key.DirectionCenter || key == Key.Enter) return handleCenter(event)
        if (event.type != KeyEventType.KeyDown || currentChannels.isEmpty()) return false
        if (!miniGuideOpen) return handleKey(key)
        miniGuideActivity++
        return when (key) {
            // Up and Down move focus inside the list; the focused row handles OK.
            Key.DirectionUp, Key.DirectionDown -> false
            Key.DirectionLeft, Key.DirectionRight -> true
            else -> handleKey(key)
        }
    }

    Box(
        Modifier
            .fillMaxSize()
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
            .onPreviewKeyEvent { event -> handleKeyEvent(event) }
            .pointerInput(Unit) { detectTapGestures(onTap = { currentHandleTap() }) },
    ) {
        player.Video(Modifier.fillMaxSize()) { Box(Modifier.fillMaxSize().background(SofaScrim)) }
        Captions(player, captionStyle, captionBottomFraction, Modifier.fillMaxSize())
        KeepScreenOn(playing)

        val channel = currentChannels.getOrNull(index)
        if (channel == null) {
            Text(stringResource(Res.string.no_channels), style = MaterialTheme.typography.titleLarge, color = SofaDusk, modifier = Modifier.align(Alignment.Center))
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
                    StatusPill(stringResource(Res.string.player_paused))
                }
                AnimatedContent(
                    targetState = captionToast,
                    transitionSpec = { popIn togetherWith popOut },
                    label = "captionToast",
                ) { toast ->
                    if (toast != null) StatusPill(stringResource(toast.outcome.labelRes()), MaterialTheme.typography.titleLarge)
                }
            }
            failure?.let { FailureCard(it.code) }
            val pendingDigits = entry.digits
            val noticeText = notice?.let { stringResource(Res.string.no_channel_number, it.digits) }
            // Keyed on presence, so each typed digit updates the badge in place instead of popping it again.
            AnimatedContent(
                targetState = pendingDigits.ifEmpty { noticeText.orEmpty() },
                modifier = Modifier.align(Alignment.TopEnd),
                transitionSpec = { popIn togetherWith popOut },
                contentKey = { it.isNotEmpty() },
                label = "digits",
            ) { text ->
                if (text.isNotEmpty()) DigitsBadge(text)
            }
            // The channel list arrives on a spring but leaves at once: while it animated out it would still
            // hold focus, and a quick second Back or Down would land in the list instead of the player.
            AnimatedVisibility(miniGuideOpen, enter = RiseIn, exit = ExitTransition.None) {
                Box(Modifier.fillMaxSize()) {
                    MiniGuide(
                        lineup = lineup,
                        channels = currentChannels,
                        currentIndex = index,
                        previousIndex = previousIndex,
                        now = now,
                        onTune = { target -> zapTo(target) },
                    )
                }
            }
            AnimatedVisibility(overlayVisible && !miniGuideOpen, enter = RiseIn, exit = SinkOut) {
                Box(Modifier.fillMaxSize()) {
                    val scopeLabel = zapScope?.takeIf { scoped }?.let { stringResource(Res.string.zap_scope, it.label, index + 1, channels.size) }
                    InfoOverlay(
                        lineup = lineup,
                        channel = channel,
                        now = now,
                        captionsOn = captionsOn,
                        captionAdvanceMs = channelAdvance,
                        scope = scopeLabel,
                        showKeyHint = !pointerDriven,
                    )
                    if (pointerDriven) {
                        TouchControls(
                            captionsOn = captionsOn,
                            paused = paused,
                            failed = failure != null,
                            onGuide = onExit,
                            onChannelUp = control { zapStep(1) },
                            onChannelDown = control { zapStep(-1) },
                            onChannelList = ::openMiniGuide,
                            onCaptions = control { toggleCaptions() },
                            onPlayPause = control { if (player.playWhenReady) player.pause() else player.play() },
                            onRetry = control { retry() },
                        )
                    }
                }
            }
        }
    }
}
