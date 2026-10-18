package dev.jamesrich.sofaking.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.withStateAtLeast
import co.touchlab.kermit.Logger
import dev.jamesrich.sofaking.contract.Wire
import dev.jamesrich.sofaking.data.PlaybackSessions
import dev.jamesrich.sofaking.data.PlaybackStream
import dev.jamesrich.sofaking.data.SofaKingException
import dev.jamesrich.sofaking.data.Subtitles
import dev.jamesrich.sofaking.resources.*
import dev.jamesrich.sofaking.ui.BackHandler
import dev.jamesrich.sofaking.ui.InputKind
import dev.jamesrich.sofaking.ui.KeepScreenOn
import dev.jamesrich.sofaking.ui.LocalLastInput
import dev.jamesrich.sofaking.ui.PopIn
import dev.jamesrich.sofaking.ui.PopOut
import dev.jamesrich.sofaking.ui.rememberSettled
import dev.jamesrich.sofaking.ui.repeatCountOrNull
import dev.jamesrich.sofaking.ui.screenPadding
import dev.jamesrich.sofaking.ui.theme.SofaOverlayScrim
import dev.jamesrich.sofaking.ui.theme.SofaOverlayTrack
import dev.jamesrich.sofaking.ui.theme.SofaScrim
import dev.jamesrich.sofaking.ui.uptimeMs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/**
 * The full screen for something the viewer moves through: a library item or a recording. [open] opens it on the
 * app's one session (through `LibraryViewModel.play` or the recordings' own call) and the screen plays whatever item
 * stream the session holds, including one it reopens after ON_START or a dropped lease or restarts for a seek past
 * what the server has written. [onPosition] gets the position in the item about once a second while it plays, and
 * [onStopped] where it stopped when the viewer leaves or it ends. Leaving stops the player and releases the session.
 * When it ends, [upNext] names what plays after it, which the Up next card counts down to and [onPlayNext] opens.
 */
@Composable
fun VodPlayerScreen(
    title: VodTitle,
    player: LivePlayer,
    sessions: PlaybackSessions,
    open: suspend () -> PlaybackStream,
    onPosition: (positionMs: Long, durationMs: Long?) -> Unit,
    onStopped: (positionMs: Long, durationMs: Long?) -> Unit,
    onExit: () -> Unit,
    /** Playback reached the end; leaving is the default. */
    onEnded: () -> Unit = onExit,
    /** Jump over a [VodMarkerKind.BREAK] when playback runs into it. */
    autoSkipBreaks: Boolean = false,
    /** What plays after this item, asked for when it ends; null ends as [onEnded] says. */
    upNext: suspend () -> VodUpNext? = { null },
    onPlayNext: () -> Unit = {},
    /** The audio and subtitle tracks to pick from; none hides the picker. */
    tracks: VodTracks = VodTracks(),
) {
    val held by sessions.current.collectAsStateWithLifecycle()
    val stream = held?.takeIf { it.itemId != null }
    val status by player.status.collectAsStateWithLifecycle()
    val paused = !status.playWhenReady
    val buffering = status.state == PlaybackState.BUFFERING
    var failure by remember { mutableStateOf<String?>(null) }
    var openRequest by remember { mutableIntStateOf(0) }
    var positionMs by remember { mutableStateOf(0L) }
    var durationMs by remember { mutableStateOf<Long?>(null) }
    // The stream position to seek to once the item at a new URL is ready: a file played as it is starts at 0.
    var pendingSeek by remember { mutableStateOf<Long?>(null) }
    var overlayVisible by remember { mutableStateOf(true) }
    var overlayRequest by remember { mutableIntStateOf(0) }
    val scrub = remember { VodScrub() }
    var scrubTarget by remember { mutableStateOf<Long?>(null) }
    var skippedToast by remember { mutableStateOf(false) }
    var ended by remember { mutableStateOf(false) }
    var next by remember { mutableStateOf<VodUpNext?>(null) }
    var tracksOpen by remember { mutableStateOf(false) }
    var audioTrack by remember { mutableStateOf(tracks.initialAudio) }
    var subtitleTrack by remember { mutableStateOf(tracks.initialSubtitle) }
    var subtitles by remember { mutableStateOf<Subtitles?>(null) }
    var subtitlesFailed by remember { mutableStateOf(false) }
    var nextSecondsLeft by remember { mutableIntStateOf(UP_NEXT_SECONDS) }
    val pointerDriven = LocalLastInput.current.kind == InputKind.POINTER
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val currentOnPosition by rememberUpdatedState(onPosition)
    val currentOnStopped by rememberUpdatedState(onStopped)
    val currentOnEnded by rememberUpdatedState(onEnded)
    val currentUpNext by rememberUpdatedState(upNext)
    val currentOnPlayNext by rememberUpdatedState(onPlayNext)
    val currentTitle by rememberUpdatedState(title)

    var mediaSession by remember { mutableStateOf<MediaSessionHandle?>(null) }

    fun showOverlay() {
        overlayVisible = true
        overlayRequest++
    }

    /** Moves to [targetMs] in the item: inside the stream the player holds, or a conversion restarted there. */
    fun seekItem(targetMs: Long) {
        val current = sessions.current.value?.takeIf { it.itemId != null } ?: return
        when (val plan = vodSeekPlan(current, targetMs, player.durationMs, current.itemDurationMs)) {
            is VodSeekPlan.InStream -> player.seekTo(plan.streamMs)
            is VodSeekPlan.Restart -> {
                player.stop()
                scope.launch {
                    try {
                        sessions.restartAt(plan.itemMs)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        log.w { "vod: restart failed: ${e.message}" }
                        failure = (e as? SofaKingException)?.code ?: SESSION_UNAVAILABLE
                    }
                }
            }
        }
        positionMs = targetMs
    }

    DisposableEffect(player) {
        val session = player.openMediaSession { step -> seekItem(positionMs + step * 30_000L) }
        mediaSession = session
        player.volume = 1f
        onDispose {
            session.close()
            mediaSession = null
        }
    }
    // Opens the item, again on a retry. The stream it publishes is loaded by the effect below.
    LaunchedEffect(openRequest) {
        failure = null
        try {
            open()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w { "vod: open failed: ${e.message}" }
            failure = (e as? SofaKingException)?.code ?: SESSION_UNAVAILABLE
        }
    }
    // Each new URL for the item (the first open, a restart, a reopen) is set on the player and prepared once the
    // host is started; a file played as it is then seeks to where it was asked to start.
    LaunchedEffect(stream?.url) {
        val s = stream ?: return@LaunchedEffect
        lifecycle.withStateAtLeast(Lifecycle.State.STARTED) {
            if (player.currentStreamUrl == s.url) return@withStateAtLeast
            val wasPaused = !player.playWhenReady && player.hasItem && player.currentChannelId.isNullOrEmpty()
            player.stop()
            player.setStream(s, NowPlaying(currentTitle.title, currentTitle.subtitle.orEmpty(), currentTitle.artworkUrl))
            pendingSeek = (s.startAtMs - s.offsetMs).takeIf { it > 0 }
            positionMs = s.startAtMs
            durationMs = s.itemDurationMs
            player.playWhenReady = !wasPaused
            player.prepare()
        }
    }
    LaunchedEffect(status.state, pendingSeek) {
        val target = pendingSeek ?: return@LaunchedEffect
        if (status.state == PlaybackState.READY && player.isSeekable) {
            player.seekTo(target)
            pendingSeek = null
        }
    }
    LaunchedEffect(player) {
        player.errors.collect { error -> failure = error.code }
    }
    LaunchedEffect(status.state) {
        if (status.state == PlaybackState.READY || status.isPlaying) failure = null
        if (status.state == PlaybackState.ENDED && !ended && stream != null) {
            ended = true
            val end = durationMs ?: positionMs
            currentOnStopped(end, durationMs)
            next = currentUpNext()
            if (next == null) currentOnEnded()
        }
    }
    // The Up next card counts down once a second and then plays what it names.
    LaunchedEffect(next) {
        if (next == null) return@LaunchedEffect
        for (left in UP_NEXT_SECONDS downTo 1) {
            nextSecondsLeft = left
            delay(1_000L)
        }
        // Not while another app is in front: the next item opens once this one is back.
        lifecycle.withStateAtLeast(Lifecycle.State.STARTED) {}
        currentOnPlayNext()
    }
    // The clock of the screen: the position in the item, the length, a run into a break, a scrub that has
    // settled, and the position report once a second.
    LaunchedEffect(Unit) {
        var lastReport = 0L
        while (true) {
            delay(TICK_MS)
            scrub.settled(uptimeMs())?.let { target ->
                scrubTarget = null
                seekItem(target)
            }
            val current = sessions.current.value?.takeIf { it.itemId != null } ?: continue
            if (!player.isSeekable || pendingSeek != null) continue
            val previous = positionMs
            val now = player.positionMs + current.offsetMs
            positionMs = now
            durationMs = current.itemDurationMs ?: player.durationMs?.let { it + current.offsetMs }
            if (autoSkipBreaks && scrubTarget == null) {
                breakToSkip(currentTitle.markers, previous, now)?.let { end ->
                    seekItem(end)
                    skippedToast = true
                }
            }
            val clock = uptimeMs()
            if (status.isPlaying && clock - lastReport >= REPORT_MS) {
                lastReport = clock
                currentOnPosition(positionMs, durationMs)
            }
        }
    }
    // The picked subtitle track's cues, read once per pick; a track the server cannot give turns them off.
    LaunchedEffect(subtitleTrack) {
        subtitles = null
        val index = subtitleTrack?.takeUnless(tracks::isPicture) ?: return@LaunchedEffect
        subtitles = tracks.loadSubtitles(index)
        if (subtitles == null) {
            subtitleTrack = null
            subtitlesFailed = true
        }
    }
    LaunchedEffect(subtitlesFailed) {
        if (!subtitlesFailed) return@LaunchedEffect
        delay(TOAST_MS)
        subtitlesFailed = false
    }
    LaunchedEffect(skippedToast) {
        if (!skippedToast) return@LaunchedEffect
        delay(TOAST_MS)
        skippedToast = false
    }
    // The bar stays up while paused or scrubbing, and leaves a few seconds after the last key otherwise.
    LaunchedEffect(overlayVisible, overlayRequest, paused, scrubTarget, failure) {
        if (!overlayVisible || paused || scrubTarget != null || failure != null || pointerDriven) return@LaunchedEffect
        delay(OVERLAY_MS)
        overlayVisible = false
    }
    // The app stops the player and releases the session while another app is in front (see App); the screen
    // saves where it was and, back in front, reopens the item there, which the effect above loads and seeks.
    var backgrounded by remember { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        if (ended || stream == null) return@LifecycleEventEffect
        backgrounded = true
        currentOnStopped(positionMs, durationMs)
    }
    LifecycleEventEffect(Lifecycle.Event.ON_START) {
        if (!backgrounded) return@LifecycleEventEffect
        backgrounded = false
        scope.launch {
            try {
                if (sessions.reopen() == null) openRequest++
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.w { "vod: reopen failed: ${e.message}" }
                failure = (e as? SofaKingException)?.code ?: SESSION_UNAVAILABLE
            }
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            if (!ended) currentOnStopped(positionMs, durationMs)
            player.stop()
            sessions.releaseAsync(forget = true)
        }
    }
    LaunchedEffect(title, mediaSession) {
        mediaSession?.publish(NowPlaying(title.title, title.subtitle.orEmpty(), title.artworkUrl))
    }

    fun togglePause() {
        if (player.playWhenReady) player.pause() else player.play()
        showOverlay()
    }

    fun retry() {
        failure = null
        player.stop()
        // A file played as it is picks up where it failed; a converted one restarts there.
        if (stream?.mode == Wire.VodMode.DIRECT) {
            pendingSeek = (positionMs - stream.offsetMs).takeIf { it > 0 }
            player.prepare()
        } else if (stream != null) {
            seekItem(positionMs)
            if (player.currentStreamUrl == stream.url) player.prepare()
        } else {
            openRequest++
        }
    }

    // Plays the item again from here with the picked audio and, when the subtitle is pictures, that subtitle drawn in.
    fun reopenWithTracks() {
        val audio = audioTrack
        val picture = subtitleTrack?.takeIf(tracks::isPicture)
        player.stop()
        scope.launch {
            try {
                tracks.select(audio, picture, positionMs)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.w { "vod: track switch failed: ${e.message}" }
                failure = (e as? SofaKingException)?.code ?: SESSION_UNAVAILABLE
            }
        }
    }

    fun pickAudio(index: Int) {
        tracksOpen = false
        if (index == audioTrack) return
        audioTrack = index
        reopenWithTracks()
    }

    // A text subtitle is drawn here over the same stream; a picture one, on or off, needs a new stream.
    fun pickSubtitle(index: Int?) {
        tracksOpen = false
        if (index == subtitleTrack) return
        val restream = tracks.isPicture(index) || tracks.isPicture(subtitleTrack)
        subtitleTrack = index
        if (restream) reopenWithTracks()
    }

    fun handleKeyEvent(event: KeyEvent): Boolean {
        // The card's rows take the D-pad and OK; Back closes it through the BackHandler.
        if (tracksOpen) return false
        if (next != null) {
            // With the Up next card up, OK and play/pause play it now; Back leaves through the BackHandler.
            if (event.key !in UP_NEXT_PLAY_KEYS) return false
            if (event.type == KeyEventType.KeyUp) onPlayNext()
            return true
        }
        val action = event.key.vodAction() ?: return false
        if (event.type != KeyEventType.KeyDown) return true
        when (action) {
            VodAction.BACK, VodAction.FORWARD -> {
                if (failure != null) return true
                showOverlay()
                val direction = if (action == VodAction.BACK) -1 else 1
                scrubTarget = scrub.press(direction, positionMs, durationMs, event.repeatCountOrNull ?: 0, uptimeMs())
            }
            VodAction.PLAY_PAUSE -> when {
                failure != null -> retry()
                scrubTarget != null -> {
                    scrubTarget = null
                    scrub.commit()?.let(::seekItem)
                    showOverlay()
                }
                else -> togglePause()
            }
            VodAction.PLAY -> if (!player.playWhenReady) togglePause()
            VodAction.PAUSE -> if (player.playWhenReady) togglePause()
            VodAction.SHOW_INFO -> when {
                // Down on the bar opens the Audio and subtitles card.
                event.key == Key.DirectionDown && overlayVisible && tracks.pickable && failure == null -> tracksOpen = true
                overlayVisible && !paused && event.key == Key.Info -> overlayVisible = false
                else -> showOverlay()
            }
        }
        return true
    }

    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    BackHandler {
        if (tracksOpen) {
            tracksOpen = false
            focus.requestFocus()
        } else if (scrubTarget != null) {
            scrub.cancel()
            scrubTarget = null
        } else {
            onExit()
        }
    }
    val positionText = formatPlayTime(scrubTarget ?: positionMs)
    val durationText = durationMs?.let(::formatPlayTime)
    val positionDescription = if (durationText != null) stringResource(Res.string.vod_position_description, positionText, durationText) else positionText

    Box(
        Modifier
            .fillMaxSize()
            .background(SofaScrim)
            .focusRequester(focus)
            .focusable()
            .semantics { contentDescription = title.title }
            .onPreviewKeyEvent(::handleKeyEvent)
            .pointerInput(Unit) { detectTapGestures(onTap = { if (overlayVisible) overlayVisible = false else showOverlay() }) },
    ) {
        player.Video(Modifier.fillMaxSize()) { Box(Modifier.fillMaxSize().background(SofaScrim)) }
        KeepScreenOn(status.isPlaying)

        Column(Modifier.align(Alignment.TopStart).screenPadding(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AnimatedVisibility(rememberSettled((buffering || stream == null) && failure == null), enter = PopIn, exit = PopOut) {
                StatusPill(stringResource(Res.string.vod_starting), busy = true)
            }
            AnimatedVisibility(paused && failure == null && stream != null, enter = PopIn, exit = PopOut) {
                StatusPill(stringResource(Res.string.player_paused))
            }
            AnimatedVisibility(skippedToast, enter = PopIn, exit = PopOut) {
                StatusPill(stringResource(Res.string.vod_break_skipped))
            }
            AnimatedVisibility(subtitlesFailed, enter = PopIn, exit = PopOut) {
                StatusPill(stringResource(Res.string.vod_subtitles_failed))
            }
        }
        subtitles?.let { cues -> cues.textAt(positionMs)?.let { SubtitleText(it, raised = overlayVisible) } }
        failure?.let { FailureCard(it) }
        next?.let { UpNextCard(it, nextSecondsLeft, pointerDriven, onPlay = onPlayNext, onCancel = onExit) }

        InfoBar((overlayVisible || failure != null) && next == null) {
            Column(
                Modifier
                    .widthIn(max = 1200.dp)
                    .fillMaxWidth()
                    .background(SofaOverlayScrim, BarShape)
                    .padding(24.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite },
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(title.title, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        title.subtitle?.let {
                            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    stream?.mode?.let { mode -> modeLabel(mode)?.let { Text(it, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
                }
                VodSeekBar(
                    positionMs = positionMs,
                    durationMs = durationMs,
                    scrubMs = scrubTarget,
                    markers = title.markers,
                    onSeek = if (pointerDriven) { target -> seekItem(target); showOverlay() } else null,
                )
                Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = positionDescription }) {
                    Text(positionText, style = MaterialTheme.typography.titleMedium, color = if (scrubTarget != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                    Spacer(Modifier.weight(1f))
                    durationText?.let { Text(it, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                if (tracks.pickable && !pointerDriven) {
                    Text(stringResource(Res.string.vod_tracks_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (pointerDriven) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
                        ControlIcon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, stringResource(Res.string.vod_control_back), { seekItem(positionMs - 10_000L); showOverlay() })
                        if (failure != null) {
                            ControlIcon(Icons.Filled.Refresh, stringResource(Res.string.player_control_retry), ::retry, primary = true)
                        } else {
                            ControlIcon(
                                if (paused) Icons.Filled.PlayArrow else PlayerIcons.Pause,
                                stringResource(if (paused) Res.string.player_control_play else Res.string.player_control_pause),
                                ::togglePause,
                                primary = true,
                            )
                        }
                        ControlIcon(Icons.AutoMirrored.Filled.KeyboardArrowRight, stringResource(Res.string.vod_control_forward), { seekItem(positionMs + 30_000L); showOverlay() })
                        if (tracks.pickable) {
                            ControlIcon(PlayerIcons.ClosedCaption, stringResource(Res.string.vod_tracks_title), { tracksOpen = !tracksOpen })
                        }
                    }
                }
            }
        }
        if (tracksOpen) {
            TracksCard(tracks, audioTrack, subtitleTrack, onAudio = { pickAudio(it); focus.requestFocus() }, onSubtitle = { pickSubtitle(it); focus.requestFocus() })
        }
    }
}

/** The bar under the title: what has played, the breaks, and where a scrub would land. [onSeek] takes a tap or click. */
@Composable
private fun VodSeekBar(positionMs: Long, durationMs: Long?, scrubMs: Long?, markers: List<VodMarker>, onSeek: ((Long) -> Unit)?) {
    val played = MaterialTheme.colorScheme.primary
    val marker = MaterialTheme.colorScheme.tertiary
    val cursor = MaterialTheme.colorScheme.onSurface
    val length = durationMs?.takeIf { it > 0 }
    val tap = if (onSeek != null && length != null) Modifier.pointerInput(length) {
        detectTapGestures { offset -> onSeek((offset.x / size.width * length).toLong()) }
    } else Modifier
    Canvas(Modifier.fillMaxWidth().height(if (onSeek != null) 24.dp else 12.dp).then(tap)) {
        val barHeight = 6.dp.toPx()
        val top = (size.height - barHeight) / 2
        val radius = CornerRadius(barHeight / 2)
        drawRoundRect(SofaOverlayTrack, Offset(0f, top), Size(size.width, barHeight), radius)
        if (length == null) return@Canvas
        fun x(ms: Long) = (ms.coerceIn(0, length).toFloat() / length) * size.width
        drawRoundRect(played, Offset(0f, top), Size(x(positionMs), barHeight), radius)
        markers.forEach { m ->
            drawRect(marker.copy(alpha = 0.8f), Offset(x(m.startMs), top), Size((x(m.endMs) - x(m.startMs)).coerceAtLeast(2f), barHeight))
        }
        scrubMs?.let { s ->
            val w = 4.dp.toPx()
            drawRect(cursor, Offset(x(s) - w / 2, 0f), Size(w, size.height))
        }
    }
}

@Composable
private fun modeLabel(mode: String): String? = when (mode) {
    Wire.VodMode.DIRECT -> stringResource(Res.string.vod_mode_direct)
    Wire.VodMode.REMUX -> stringResource(Res.string.vod_mode_remux)
    Wire.VodMode.AUDIO -> stringResource(Res.string.vod_mode_audio)
    Wire.VodMode.TRANSCODE -> stringResource(Res.string.vod_mode_transcode)
    else -> null
}

private val UP_NEXT_PLAY_KEYS = setOf(Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.MediaPlayPause, Key.MediaPlay, Key.Spacebar)
private val BarShape = RoundedCornerShape(8.dp)
private const val SESSION_UNAVAILABLE = "SESSION_UNAVAILABLE"
private val log = Logger.withTag("SofaKing")
private const val TICK_MS = 250L
private const val REPORT_MS = 1_000L
private const val OVERLAY_MS = 4_000L
private const val TOAST_MS = 3_000L
