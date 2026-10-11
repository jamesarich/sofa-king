package dev.jamesrich.sofaking.ui.player

import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.withStateAtLeast
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import dev.jamesrich.sofaking.R
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
import dev.jamesrich.sofaking.ui.rememberMinuteNow
import dev.jamesrich.sofaking.ui.rememberSettled
import dev.jamesrich.sofaking.ui.theme.SofaDusk
import dev.jamesrich.sofaking.ui.theme.SofaScrim
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private const val ZAP_OVERLAY_MS = 5_000L
private const val DIGIT_COMMIT_MS = 1_500L
private const val NOT_FOUND_MS = 2_000L
private const val CAPTION_TOAST_MS = 2_000L
private const val MINI_GUIDE_IDLE_MS = 8_000L
private const val METADATA_REFRESH_MS = 60_000L
private const val STREAM_ENDED = "STREAM_ENDED"
/** The server's answer when a session is asked for a channel id its lineup no longer has. */
internal const val CHANNEL_NOT_FOUND = "CHANNEL_NOT_FOUND"
/** How long the player waits for the lineup it asked for to catch up with the one it holds. */
private const val LINEUP_SYNC_MS = 2_000L
private const val SESSION_UNAVAILABLE = "SESSION_UNAVAILABLE"
private const val TAG = "SofaKing"

/** Digit-entry miss; compared by identity so a repeated miss restarts its timer. */
private class Notice(val digits: String)

/** Caption toggle feedback; compared by identity so a repeated press restarts its timer. */
private class CaptionToast(val outcome: CaptionToggleOutcome)

/**
 * A stream that failed or ended; [attempt] counts the automatic reconnects made for it so far. [immediate]
 * marks a 410 from the relay, whose first reconnect re-reads the session without waiting for the backoff.
 */
private data class Failure(val code: String, val attempt: Int, val immediate: Boolean = false)

/** The tune in flight, so a zap cancels the previous channel's session request before it lands on the player. */
private class TuneJob {
    var job: Job? = null
}


@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(
    lineup: Lineup,
    startIndex: Int,
    player: ExoPlayer,
    sessions: PlaybackSessions,
    captionSync: CaptionSync,
    onExit: () -> Unit,
    onChannelChanged: (Channel) -> Unit,
    onGuide: () -> Unit = onExit,
    reloadLineup: suspend () -> Lineup? = { null },
    onChannelGone: (Channel) -> Unit = { onExit() },
    captionsEnabled: Boolean = false,
    onCaptionsChanged: (Boolean) -> Unit = {},
    captionAdvanceFor: (Channel) -> Int = { AppSettings.DEFAULT_CAPTION_ADVANCE_MS },
    onCaptionAdvanceChanged: (Channel, Int) -> Unit = { _, _ -> },
    captionStyle: CaptionStyle = CaptionStyle.SYSTEM,
    smoothCaptions: Boolean = true,
) {
    val context = LocalContext.current
    val channels = lineup.channels
    val currentChannels by rememberUpdatedState(channels)
    val currentOnChannelChanged by rememberUpdatedState(onChannelChanged)
    val currentOnCaptionsChanged by rememberUpdatedState(onCaptionsChanged)
    val currentOnCaptionAdvanceChanged by rememberUpdatedState(onCaptionAdvanceChanged)
    val windowHeightDp = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp().value.toInt() }
    val captionBottomFraction = CaptionPolicy.bottomPaddingFraction(windowHeightDp)
    var index by remember { mutableIntStateOf(startIndex.coerceIn(0, (channels.size - 1).coerceAtLeast(0))) }
    var previousIndex by remember { mutableStateOf<Int?>(null) }
    // What load() last tuned and what was watched before it, so a refreshed lineup can be followed by identity.
    var tuned by remember { mutableStateOf<Channel?>(null) }
    var previousChannel by remember { mutableStateOf<Channel?>(null) }
    var entry by remember { mutableStateOf(ChannelNumberEntry()) }
    var notice by remember { mutableStateOf<Notice?>(null) }
    var overlayVisible by remember { mutableStateOf(true) }
    var overlayRequest by remember { mutableIntStateOf(0) }
    // The shared player may arrive already playing the channel from the guide preview.
    var playing by remember { mutableStateOf(player.isPlaying) }
    var buffering by remember { mutableStateOf(player.playbackState == Player.STATE_BUFFERING) }
    var failure by remember { mutableStateOf<Failure?>(null) }
    // The channel whose id the server no longer knows, while the lineup is reloaded to find it again.
    var recovering by remember { mutableStateOf<Channel?>(null) }
    var captionsOn by remember { mutableStateOf(captionsEnabled) }
    var captionToast by remember { mutableStateOf<CaptionToast?>(null) }
    var miniGuideOpen by remember { mutableStateOf(false) }
    // Bumped on every key while the mini-guide is open, so its idle timer restarts.
    var miniGuideActivity by remember { mutableIntStateOf(0) }
    var paused by remember { mutableStateOf(!player.playWhenReady) }
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

    // The session and the system's next/previous/play/pause go through this wrapper; the view and listeners use the ExoPlayer.
    val wrapper = remember(player) { LivePlayerWrapper(player) }
    DisposableEffect(player) {
        player.volume = 1f
        // The preview never watched track changes, so the caption choice is made again on arrival.
        preferCaptionTrack(player)
        val session = MediaSession.Builder(context, wrapper).build()
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing = isPlaying
                if (isPlaying) failure = null
            }

            override fun onTracksChanged(tracks: Tracks) {
                preferCaptionTrack(player)
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                paused = !playWhenReady
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                buffering = playbackState == Player.STATE_BUFFERING
                if (playbackState == Player.STATE_READY) failure = null
                // A live stream never ends; the server closed the connection, so treat it as a failure and reconnect.
                if (playbackState == Player.STATE_ENDED) failure = Failure(STREAM_ENDED, attempt = failure?.attempt ?: 0)
            }

            override fun onPlayerError(error: PlaybackException) {
                if (player.recoverIfBehindLive(error)) return
                failure = Failure(error.errorCodeName, attempt = failure?.attempt ?: 0, immediate = error.isStreamGone())
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            session.release()
        }
    }

    fun nowPlayingFor(channel: Channel) =
        nowPlayingMetadata(channel, lineup.nowAndNext(channel, Instant.now()).first?.title)

    fun load(channel: Channel) {
        val metadata = nowPlayingFor(channel)
        wrapper.publishNowPlaying(metadata)
        tuneJob.job?.cancel()
        // Handed over from the guide preview mid-stream: keep it, and let the caption lead grow back from zero.
        // The same session is promoted to watch, so the stream continues and its lease grows to 90 s.
        if (player.currentChannelId == channel.guideId && (player.playbackState == Player.STATE_READY || player.playbackState == Player.STATE_BUFFERING)) {
            captionSync.effectiveUs = 0L
            tuneJob.job = scope.launch {
                try {
                    sessions.setIntent(channel.guideId, PlaybackIntent.WATCH)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "session: promote failed", e)
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
            player.setMediaItem(mediaItemFor(stream, metadata))
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
        if (size > 0) zapTo(Math.floorMod(index + step, size))
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

    SideEffect { wrapper.onZapStep = { step -> zapStep(step) } }

    fun commitEntry() {
        val pending = entry
        if (pending.digits.isEmpty()) return
        val target = pending.indexIn(currentChannels)
        if (target != null) {
            zapTo(target)
        } else {
            entry = ChannelNumberEntry()
            notice = Notice(pending.digits)
        }
    }

    // The lineup refreshes every few hours and may reorder or grow; the tuned channel keeps its stream and
    // its row follows it. A channel that vanished stays playing on the nearest row.
    LaunchedEffect(channels) {
        if (recovering != null) return@LaunchedEffect
        val current = tuned ?: return@LaunchedEffect
        index = indexAfterRefresh(channels, current) ?: index.coerceIn(0, (channels.size - 1).coerceAtLeast(0))
        previousIndex = previousChannel?.let { indexAfterRefresh(channels, it) }
    }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(index, currentChannel?.guideId, lifecycle, recovering) {
        // While a dead id is being followed, nothing tunes until the row for its replacement is known.
        if (recovering != null) return@LaunchedEffect
        val channel = currentChannel ?: return@LaunchedEffect
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
            wrapper.publishNowPlaying(nowPlayingFor(channel))
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
            if (!player.isPlaying) continue
            captionSync.effectiveUs = CaptionSync.nextEffectiveUs(
                currentUs = captionSync.effectiveUs,
                advanceUs = captionSync.advanceUs,
                bufferedUs = player.totalBufferedDuration * 1_000L,
            )
            if (++tick % 8 == 0) {
                Log.d(TAG, "captions: buffered=${player.totalBufferedDuration}ms lead=${captionSync.effectiveUs / 1000}ms of ${captionSync.advanceUs / 1000}ms")
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
        val channel = currentChannels.getOrNull(index) ?: return@LaunchedEffect
        if (current.code == CHANNEL_NOT_FOUND) {
            // The server's lineup has new ids (a re-import): retrying this one would fail forever. Reload the
            // lineup and follow the channel by number, then name; with nothing to follow, the guide takes over.
            recovering = channel
            val lineup = reloadLineup()
            if (lineup == null) {
                // Unreachable or timed out: wait as for any failure, then the tune asks the server again.
                delay(reconnectDelayMs(current.attempt))
                lifecycle.withStateAtLeast(Lifecycle.State.STARTED) {}
                recovering = null
                failure = current.copy(attempt = current.attempt + 1)
                load(channel)
                return@LaunchedEffect
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
        player.trackSelectionParameters = captionParameters(player.trackSelectionParameters, enabled)
        val channelHasText = CaptionPolicy.hasTextTrack(player.currentTracks.groups.map { it.type })
        captionToast = CaptionToast(CaptionPolicy.toggleOutcome(enabled, channelHasText))
        currentOnCaptionsChanged(enabled)
    }

    fun openMiniGuide() {
        overlayVisible = false
        miniGuideOpen = true
        miniGuideActivity++
    }

    /** OK is judged on key-up: a short press toggles the overlay (or commits typed digits), a long press recalls the last channel. */
    fun handleCenter(event: KeyEvent): Boolean {
        // With the mini-guide open, its focused row takes OK.
        if (miniGuideOpen || currentChannels.isEmpty()) return false
        val native = event.nativeKeyEvent
        return when (event.type) {
            KeyEventType.KeyDown -> {
                if (centerGesture.onKeyDown(native.isLongPress, native.repeatCount) == CenterPress.LONG) recallLastChannel()
                true
            }
            KeyEventType.KeyUp -> {
                if (centerGesture.onKeyUp() == CenterPress.SHORT) {
                    val channel = currentChannels.getOrNull(index)
                    when {
                        entry.digits.isNotEmpty() -> commitEntry()
                        // A failed channel retries on OK, however many automatic retries have run.
                        failure != null && channel != null -> {
                            failure = null
                            recovering = null
                            load(channel)
                        }
                        else -> overlayVisible = !overlayVisible
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
            null -> Unit
        }
        key.digitValue()?.let { digit ->
            entry = entry.append(digit)
            return true
        }
        return when (key) {
            Key.Captions, Key.C -> { toggleCaptions(); true }
            Key.DirectionUp, Key.ChannelUp -> { zapStep(1); true }
            // Down zaps like Up; with the info bar up it opens the channel list instead.
            Key.DirectionDown -> { if (overlayVisible) openMiniGuide() else zapStep(-1); true }
            Key.ChannelDown -> { zapStep(-1); true }
            Key.MediaPrevious, Key.LastChannel -> { recallLastChannel(); true }
            Key.MediaPlayPause -> { if (wrapper.playWhenReady) wrapper.pause() else wrapper.play(); true }
            Key.MediaPlay -> { wrapper.play(); true }
            Key.MediaPause -> { wrapper.pause(); true }
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
            .onPreviewKeyEvent { event -> handleKeyEvent(event) },
    ) {
        LiveVideo(player, Modifier.fillMaxSize()) { Box(Modifier.fillMaxSize().background(SofaScrim)) }
        Captions(player, captionStyle, captionBottomFraction, Modifier.fillMaxSize())
        KeepScreenOn(playing)

        val channel = currentChannels.getOrNull(index)
        if (channel == null) {
            Text(stringResource(R.string.no_channels), style = MaterialTheme.typography.titleLarge, color = SofaDusk, modifier = Modifier.align(Alignment.Center))
        } else {
            // Status pills stack top left so a caption toast never covers "Paused" or "Tuning".
            Column(
                Modifier.align(Alignment.TopStart).padding(horizontal = 48.dp, vertical = 27.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AnimatedVisibility(rememberSettled(buffering && failure == null), enter = PopIn, exit = PopOut) {
                    StatusPill(stringResource(R.string.tuning), busy = true)
                }
                AnimatedVisibility(paused && failure == null, enter = PopIn, exit = PopOut) {
                    StatusPill(stringResource(R.string.player_paused))
                }
                AnimatedContent(
                    targetState = captionToast,
                    transitionSpec = { PopIn togetherWith PopOut },
                    label = "captionToast",
                ) { toast ->
                    if (toast != null) StatusPill(stringResource(toast.outcome.labelRes()), MaterialTheme.typography.titleLarge)
                }
            }
            failure?.let { FailureCard(it.code) }
            val pendingDigits = entry.digits
            val noticeText = notice?.let { stringResource(R.string.no_channel_number, it.digits) }
            // Keyed on presence, so each typed digit updates the badge in place instead of popping it again.
            AnimatedContent(
                targetState = pendingDigits.ifEmpty { noticeText.orEmpty() },
                modifier = Modifier.align(Alignment.TopEnd),
                transitionSpec = { PopIn togetherWith PopOut },
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
                    InfoOverlay(lineup = lineup, channel = channel, now = now, captionsOn = captionsOn, captionAdvanceMs = channelAdvance)
                }
            }
        }
    }
}
