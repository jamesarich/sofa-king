package dev.jamesrich.sofaking.ui.player

import dev.jamesrich.sofaking.ui.formatPlayTime
import dev.jamesrich.sofaking.ui.theme.SofaIcons
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalWindowInfo
import dev.jamesrich.sofaking.data.CaptionStyle
import org.jetbrains.compose.resources.StringResource
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.snapshotFlow
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.withStateAtLeast
import co.touchlab.kermit.Logger
import dev.jamesrich.sofaking.ui.appLifecycleOwner
import dev.jamesrich.sofaking.contract.Wire
import dev.jamesrich.sofaking.data.PlaybackSessions
import dev.jamesrich.sofaking.data.PlaybackStream
import dev.jamesrich.sofaking.data.Subtitles
import dev.jamesrich.sofaking.resources.*
import dev.jamesrich.sofaking.ui.BackHandler
import dev.jamesrich.sofaking.ui.InputKind
import dev.jamesrich.sofaking.ui.KeepScreenOn
import dev.jamesrich.sofaking.ui.LocalInPictureInPicture
import dev.jamesrich.sofaking.ui.LocalLastInput
import dev.jamesrich.sofaking.ui.LocalWindowFullScreen
import dev.jamesrich.sofaking.ui.PopIn
import dev.jamesrich.sofaking.ui.PopOut
import dev.jamesrich.sofaking.ui.rememberMinuteNow
import dev.jamesrich.sofaking.ui.rememberSettled
import dev.jamesrich.sofaking.ui.rememberTimeFormatter
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
    /** The [PlaybackStream.itemId] [open] plays: the library item's id, or the recording's. */
    itemId: String,
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
    /** A recording's captions, which the player draws, on or off; null for a library item, whose subtitles are in [tracks]. */
    captionsOn: Boolean? = null,
    onToggleCaptions: () -> Unit = {},
    captionStyle: CaptionStyle = CaptionStyle.SYSTEM,
    /** How the picture meets the screen, and the next one picked from the bar or the Zoom key. */
    zoom: VideoZoom = VideoZoom.FIT,
    onZoom: (VideoZoom) -> Unit = {},
) {
    val held by sessions.current.collectAsStateWithLifecycle()
    // Up next composes the next page beside this one while they cross-fade, so each plays, seeks and releases only
    // its own item's session.
    var loadedUrl by remember { mutableStateOf<String?>(null) }
    val stream = held?.takeIf { it.itemId == itemId }
    val status by player.status.collectAsStateWithLifecycle()
    val paused = !status.playWhenReady
    val buffering = status.state == PlaybackState.BUFFERING
    var failure by remember { mutableStateOf<String?>(null) }
    var openRequest by remember { mutableIntStateOf(0) }
    var positionMs by remember { mutableLongStateOf(0L) }
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
    // What plays after this item, looked up once it opens, for the Next episode button.
    var upcoming by remember { mutableStateOf<VodUpNext?>(null) }
    // The subtitle track CC turns back on.
    var lastSubtitle by remember { mutableStateOf(tracks.initialSubtitle) }
    // CC's answer, or the zoom just picked, for a moment.
    var toast by remember { mutableStateOf<VodToast?>(null) }
    // True while a button on the bar has focus, so the D-pad moves along the row instead of seeking.
    var rowFocused by remember { mutableStateOf(false) }
    val rowEntry = remember { FocusRequester() }
    // Set when playback runs into a break it does not skip, so Skip break takes focus and OK skips, as other players' skip pill does.
    var focusSkip by remember { mutableStateOf(false) }
    val pointerDriven = LocalLastInput.current.kind == InputKind.POINTER
    val fullScreen = LocalWindowFullScreen.current
    val inPictureInPicture = LocalInPictureInPicture.current
    val volume = LocalPlayerVolume.current
    val scope = rememberCoroutineScope()
    val lifecycle = appLifecycleOwner.lifecycle
    val currentOnPosition by rememberUpdatedState(onPosition)
    val currentOnStopped by rememberUpdatedState(onStopped)
    val currentOnEnded by rememberUpdatedState(onEnded)
    val currentUpNext by rememberUpdatedState(upNext)
    val currentOnPlayNext by rememberUpdatedState(onPlayNext)
    val currentTitle by rememberUpdatedState(title)
    val currentCaptionsOn by rememberUpdatedState(captionsOn)
    val currentOnToggleCaptions by rememberUpdatedState(onToggleCaptions)
    // Read from the ticking position, so the screen recomposes only when they change.
    val breakEnd by remember(title.markers) { derivedStateOf { breakEndAt(title.markers, positionMs) } }
    val pastStart by remember { derivedStateOf { positionMs > START_OVER_AFTER_MS } }
    val windowHeightDp = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp().value.toInt() }

    var mediaSession by remember { mutableStateOf<MediaSessionHandle?>(null) }
    val focus = remember { FocusRequester() }

    fun showOverlay() {
        overlayVisible = true
        overlayRequest++
    }

    /** Moves to [targetMs] in the item: inside the stream the player holds, or a conversion restarted there. */
    fun seekItem(targetMs: Long) {
        val current = sessions.current.value?.takeIf { it.itemId == itemId } ?: return
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
                        failure = failureCode(e)
                    }
                }
            }
        }
        positionMs = targetMs
    }

    // Open only while the app is in front, as the app stops the player behind another app.
    LifecycleStartEffect(player, lifecycleOwner = appLifecycleOwner) {
        val session = player.openMediaSession { step ->
            seekItem(positionMs + step * if (step < 0) VodScrub.BACK_STEP_MS else VodScrub.FORWARD_STEP_MS)
        }
        mediaSession = session
        player.volume = volume.gain
        onStopOrDispose {
            session.close()
            mediaSession = null
        }
    }
    LaunchedEffect(player, volume) { snapshotFlow { volume.gain }.collect { player.volume = it } }
    // Opens the item, again on a retry. The stream it publishes is loaded by the effect below.
    LaunchedEffect(openRequest) {
        failure = null
        try {
            open()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w { "vod: open failed: ${e.message}" }
            failure = failureCode(e)
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
            loadedUrl = s.url
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
        if (status.state == PlaybackState.ENDED && stream != null) ended = true
    }
    // Its own effect, so the player's next state change (the app stopping it, say) cannot cancel the lookup.
    LaunchedEffect(ended) {
        if (!ended) return@LaunchedEffect
        currentOnStopped(durationMs ?: positionMs, durationMs)
        next = currentUpNext()
        if (next == null) currentOnEnded()
    }
    LaunchedEffect(Unit) {
        upcoming = try {
            currentUpNext()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w { "vod: up next failed: ${e.message}" }
            null
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
            val current = sessions.current.value?.takeIf { it.itemId == itemId } ?: continue
            if (!player.isSeekable || pendingSeek != null) continue
            val previous = positionMs
            val now = player.positionMs + current.offsetMs
            positionMs = now
            durationMs = current.itemDurationMs ?: player.durationMs?.let { it + current.offsetMs }
            if (scrubTarget == null) {
                breakToSkip(currentTitle.markers, previous, now)?.let { end ->
                    if (autoSkipBreaks) {
                        seekItem(end)
                        skippedToast = true
                    } else {
                        // The bar comes up with Skip break on it, focused for the remote.
                        showOverlay()
                        focusSkip = true
                    }
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
    LaunchedEffect(toast) {
        if (toast == null) return@LaunchedEffect
        delay(TOAST_MS)
        toast = null
    }
    LaunchedEffect(skippedToast) {
        if (!skippedToast) return@LaunchedEffect
        delay(TOAST_MS)
        skippedToast = false
    }
    // The bar stays up while paused or scrubbing, and leaves a few seconds after the last key, tap or mouse movement
    // otherwise; a button that had focus hands it back to the picture.
    LaunchedEffect(overlayVisible, overlayRequest, paused, scrubTarget, failure) {
        if (!overlayVisible || paused || scrubTarget != null || failure != null) return@LaunchedEffect
        delay(BAR_IDLE_MS)
        overlayVisible = false
    }
    // The app stops the player and releases the session while another app is in front (see App); the screen
    // saves where it was and, back in front, reopens the item there, which the effect above loads and seeks.
    var backgrounded by remember { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP, appLifecycleOwner) {
        if (ended || stream == null) return@LifecycleEventEffect
        backgrounded = true
        currentOnStopped(positionMs, durationMs)
    }
    LifecycleEventEffect(Lifecycle.Event.ON_START, appLifecycleOwner) {
        if (!backgrounded) return@LifecycleEventEffect
        backgrounded = false
        scope.launch {
            try {
                if (sessions.reopen() == null) openRequest++
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.w { "vod: reopen failed: ${e.message}" }
                failure = failureCode(e)
            }
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            if (!ended) currentOnStopped(positionMs, durationMs)
            // Stopped only on this page's stream, or on nothing yet while no other item holds the session.
            val other = sessions.current.value?.itemId?.let { it != itemId } == true
            if (if (loadedUrl == null) !other else player.currentStreamUrl == loadedUrl) player.stop()
            sessions.releaseAsync(forget = true, itemId = itemId)
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
                failure = failureCode(e)
            }
        }
    }

    fun pickAudio(index: Int) {
        tracksOpen = false
        if (index == audioTrack) return
        audioTrack = index
        tracks.onPicked(audioTrack, subtitleTrack)
        reopenWithTracks()
    }

    // A text subtitle is drawn here over the same stream; a picture one, on or off, needs a new stream.
    fun pickSubtitle(index: Int?) {
        tracksOpen = false
        if (index == subtitleTrack) return
        if (index != null) lastSubtitle = index
        val restream = tracks.isPicture(index) || tracks.isPicture(subtitleTrack)
        subtitleTrack = index
        tracks.onPicked(audioTrack, subtitleTrack)
        if (restream) reopenWithTracks()
    }

    // The card stands in for the bar, which would otherwise run under it; the next key brings the bar back.
    fun openTracks() {
        tracksOpen = true
        overlayVisible = false
    }

    // CC and C: a recording's captions on or off; a library item's subtitles off, or back on with the last ones shown.
    fun toggleCaptions() {
        val captions = currentCaptionsOn
        if (captions != null) {
            currentOnToggleCaptions()
            toast = VodToast(if (captions) Res.string.captions_off else Res.string.captions_on)
            return
        }
        if (subtitleTrack != null) {
            pickSubtitle(null)
            toast = VodToast(Res.string.vod_subtitles_off)
            return
        }
        val target = tracks.subtitleToTurnOn(lastSubtitle)
        if (target == null) {
            toast = VodToast(Res.string.vod_no_subtitles)
        } else {
            pickSubtitle(target)
            toast = VodToast(Res.string.vod_subtitles_on)
        }
    }

    fun stepZoom() {
        val next = zoom.next()
        onZoom(next)
        toast = VodToast(Res.string.player_zoom_notice, next.label)
    }

    val buttons = vodButtons(
        failed = failure != null,
        inBreak = breakEnd != null,
        tracksPickable = tracks.pickable,
        captions = captionsOn != null,
        pastStart = pastStart,
        hasNext = upcoming != null,
    )

    fun handleKeyEvent(event: KeyEvent): Boolean {
        // The card's rows take the D-pad and OK; Back closes it through the BackHandler.
        if (tracksOpen) return false
        if (next != null) {
            // With the Up next card up, OK and play/pause play it now; Back leaves through the BackHandler.
            if (event.key !in UP_NEXT_PLAY_KEYS) return false
            if (event.type == KeyEventType.KeyUp) onPlayNext()
            return true
        }
        if (rowFocused) {
            showOverlay()
            when (event.key.inButtonRow()) {
                ButtonRowKey.ROW -> return false
                // Up, Back and Escape hand focus back to the picture on key-up, so the key-up cannot act there too.
                ButtonRowKey.LEAVE -> {
                    if (event.type == KeyEventType.KeyUp) focus.requestFocus()
                    return true
                }
                ButtonRowKey.PLAYER -> focus.requestFocus()
            }
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
                overlayVisible && !paused && event.key == Key.Info -> overlayVisible = false
                else -> showOverlay()
            }
            // Down shows the bar, and on the bar moves into its buttons.
            VodAction.BUTTONS -> {
                if (overlayVisible && buttons.isNotEmpty()) rowEntry.requestFocus()
                showOverlay()
            }
            VodAction.CAPTIONS -> toggleCaptions()
            VodAction.TRACKS -> if (tracks.pickable && failure == null) openTracks() else showOverlay()
            VodAction.ZOOM -> stepZoom()
            VodAction.STOP -> onExit()
        }
        return true
    }

    LaunchedEffect(Unit) { focus.requestFocus() }
    // A button that had focus when the bar left, or when it was removed, hands it back to the picture.
    LaunchedEffect(overlayVisible, buttons.size) { if (rowFocused && (!overlayVisible || buttons.isEmpty())) focus.requestFocus() }
    LaunchedEffect(focusSkip, overlayVisible, buttons.firstOrNull()) {
        if (!focusSkip) return@LaunchedEffect
        focusSkip = false
        if (!overlayVisible || pointerDriven || buttons.firstOrNull() != VodButton.SKIP_BREAK) return@LaunchedEffect
        // The bar's row is laid out once the frame that shows it is drawn.
        withFrameNanos {}
        rowEntry.requestFocus()
    }
    BackHandler {
        // Where Back skips the key path (Android with predictive back), a focused bar button hands focus back here.
        if (rowFocused) {
            focus.requestFocus()
        } else if (tracksOpen) {
            tracksOpen = false
            focus.requestFocus()
        } else if (scrubTarget != null) {
            scrub.cancel()
            scrubTarget = null
        } else {
            onExit()
        }
    }
    val density = LocalDensity.current
    // The info bar's height as last drawn, so a subtitle sits just above it while it shows.
    var barHeight by remember { mutableStateOf(0.dp) }

    Box(
        Modifier
            .fillMaxSize()
            .background(SofaScrim)
            .focusRequester(focus)
            .focusable()
            .semantics { contentDescription = title.title }
            .onPreviewKeyEvent(::handleKeyEvent)
            // A double click switches a window the app can make full screen; on a phone or tablet a double tap on the
            // picture's left or right third steps back or forward, as the buttons do.
            .pointerInput(fullScreen) {
                detectTapGestures(
                    onDoubleTap = { offset ->
                        if (fullScreen != null) {
                            fullScreen.toggle()
                        } else {
                            when (doubleTapSeek(offset.x, size.width.toFloat())) {
                                -1 -> seekItem(positionMs - VodScrub.BACK_STEP_MS)
                                1 -> seekItem(positionMs + VodScrub.FORWARD_STEP_MS)
                            }
                            showOverlay()
                        }
                    },
                    onTap = { if (overlayVisible) overlayVisible = false else showOverlay() },
                )
            }
            .showBarOnMouseMove(LocalLastInput.current, ::showOverlay),
    ) {
        player.Video(Modifier.fillMaxSize(), zoom) { Box(Modifier.fillMaxSize().background(SofaScrim)) }
        // A recording's captions are the stream's own, drawn by the player as on live TV.
        if (captionsOn == true) Captions(player, captionStyle, CaptionPolicy.bottomPaddingFraction(windowHeightDp), Modifier.fillMaxSize())
        KeepScreenOn(status.isPlaying)
        // Picture-in-picture shows only the picture: the subtitles are drawn at the full screen's size.
        if (inPictureInPicture) return@Box

        val barVisible = (overlayVisible || failure != null) && next == null
        Column(Modifier.align(Alignment.TopStart).screenPadding(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AnimatedVisibility(barVisible && pointerDriven, enter = PopIn, exit = PopOut) {
                PlayerBackControl(onExit)
            }
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
            val popIn = PopIn
            val popOut = PopOut
            AnimatedContent(toast, transitionSpec = { popIn togetherWith popOut }, label = "toast") { shown ->
                if (shown != null) StatusPill(shown.arg?.let { stringResource(shown.text, stringResource(it)) } ?: stringResource(shown.text))
            }
            VolumeToast()
        }
        failure?.let { FailureCard(it, live = false) }
        next?.let { UpNextCard(it, nextSecondsLeft, pointerDriven, onPlay = onPlayNext, onCancel = onExit) }

        InfoBar(barVisible) {
            Column(
                Modifier
                    .onSizeChanged { barHeight = with(density) { it.height.toDp() } }
                    .widthIn(max = 1200.dp)
                    .fillMaxWidth()
                    .background(SofaOverlayScrim, BarShape)
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    // Announced as the bar comes up; the ticking times below are not, or a screen reader would read
                    // every second of them.
                    Column(Modifier.weight(1f).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }) {
                        Text(title.title, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        title.subtitle?.let {
                            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    stream?.mode?.let { mode -> modeLabel(mode)?.let { Text(it, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
                }
                val length = durationMs?.takeIf { it > 0 }
                if (pointerDriven && length != null) {
                    VodSeekSlider(
                        positionMs = { positionMs },
                        lengthMs = length,
                        markers = title.markers,
                        onScrub = { target ->
                            scrubTarget = target
                            showOverlay()
                        },
                        onSeek = { target ->
                            scrubTarget = null
                            seekItem(target)
                            showOverlay()
                            focus.requestFocus()
                        },
                    )
                } else {
                    VodSeekBar(positionMs = { positionMs }, durationMs = durationMs, scrubMs = scrubTarget, markers = title.markers)
                }
                VodTimes(positionMs = { positionMs }, scrubMs = scrubTarget, durationMs = durationMs)
                val items = buttons.map { button ->
                    vodButtonItem(
                        button,
                        captionsOn = captionsOn == true,
                        zoom = zoom,
                        onClick = {
                            when (button) {
                                VodButton.SKIP_BREAK -> breakEnd?.let(::seekItem)
                                VodButton.TRACKS -> if (tracksOpen) tracksOpen = false else openTracks()
                                VodButton.CAPTIONS -> toggleCaptions()
                                VodButton.ZOOM -> stepZoom()
                                VodButton.START_OVER -> seekItem(0)
                                VodButton.NEXT -> onPlayNext()
                            }
                            showOverlay()
                            // A seek moves on and the picture takes the keys again; Captions and Zoom stay for another press.
                            if (button !in STAYING_BUTTONS) focus.requestFocus()
                        },
                    )
                }
                if (pointerDriven) {
                    FlowRow(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        itemVerticalAlignment = Alignment.CenterVertically,
                    ) {
                        ControlIcon(SofaIcons.Replay10, stringResource(Res.string.vod_control_back), { seekItem(positionMs - VodScrub.BACK_STEP_MS); showOverlay() })
                        PlayPauseControl(paused, failed = failure != null, onPlayPause = ::togglePause, onRetry = ::retry, primary = true)
                        ControlIcon(SofaIcons.Forward30, stringResource(Res.string.vod_control_forward), { seekItem(positionMs + VodScrub.FORWARD_STEP_MS); showOverlay() })
                        PlayerButtonRow(items, entry = rowEntry, onFocused = { rowFocused = it }, showLabel = false)
                        VolumeControl(onAdjust = ::showOverlay, onDone = { showOverlay(); focus.requestFocus() })
                        FullScreenControl()
                    }
                } else if (items.isNotEmpty()) {
                    PlayerButtonRow(items, entry = rowEntry, onFocused = { rowFocused = it }, showLabel = true)
                    if (!rowFocused) {
                        Text(stringResource(Res.string.vod_options_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        // Drawn over the bar and clear of its top edge; it waits while a card would cover it.
        if (!tracksOpen && failure == null && next == null) {
            subtitles?.let { cues -> VodSubtitle(cues, { positionMs }, bottom = if (barVisible) barHeight + SUBTITLE_GAP else SUBTITLE_GAP) }
        }
        if (tracksOpen) {
            TracksCard(tracks, audioTrack, subtitleTrack, onAudio = { pickAudio(it); focus.requestFocus() }, onSubtitle = { pickSubtitle(it); focus.requestFocus() })
        }
    }
}

// The position ticks four times a second, so it is passed as a lambda and read only by these small composables; the
// screen itself recomposes only when something else changes.

/** Where the item is, when it ends and how long it is, under the seek bar. */
@Composable
private fun VodTimes(positionMs: () -> Long, scrubMs: Long?, durationMs: Long?) {
    val positionText = formatPlayTime(scrubMs ?: positionMs())
    val durationText = durationMs?.let(::formatPlayTime)
    val now by rememberMinuteNow()
    val timeFormat = rememberTimeFormatter()
    val endsText = vodEndsAt(now, scrubMs ?: positionMs(), durationMs)?.let { stringResource(Res.string.vod_ends_at, timeFormat.format(it)) }
    val description = if (durationText != null) stringResource(Res.string.vod_position_description, positionText, durationText) else positionText
    Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = description }) {
        Text(positionText, style = MaterialTheme.typography.titleMedium, color = if (scrubMs != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.weight(1f))
        endsText?.let { Text(it, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Spacer(Modifier.weight(1f))
        durationText?.let { Text(it, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun BoxScope.VodSubtitle(cues: Subtitles, positionMs: () -> Long, bottom: Dp) {
    cues.textAt(positionMs())?.let { SubtitleText(it, bottom = bottom) }
}

/** The bar under the title for the remote: what has played, the breaks, and where a scrub would land. */
@Composable
private fun VodSeekBar(positionMs: () -> Long, durationMs: Long?, scrubMs: Long?, markers: List<VodMarker>) {
    val played = MaterialTheme.colorScheme.primary
    val marker = MaterialTheme.colorScheme.tertiary
    val cursor = MaterialTheme.colorScheme.onSurface
    val length = durationMs?.takeIf { it > 0 }
    Canvas(Modifier.fillMaxWidth().height(12.dp)) {
        drawSeekTrack(length?.let { positionMs().toFloat() / it } ?: 0f, length, markers, played, marker)
        if (length == null) return@Canvas
        scrubMs?.let { s ->
            val w = 4.dp.toPx()
            drawRect(cursor, Offset(s.coerceIn(0, length).toFloat() / length * size.width - w / 2, 0f), Size(w, size.height))
        }
    }
}

/**
 * The seek bar for a finger or a mouse: Material's slider over the same track, so a drag shows where it would land
 * ([onScrub], which the times follow) and a release or a tap seeks there ([onSeek]).
 */
@Composable
private fun VodSeekSlider(positionMs: () -> Long, lengthMs: Long, markers: List<VodMarker>, onScrub: (Long) -> Unit, onSeek: (Long) -> Unit) {
    val state = remember(lengthMs) { SliderState(value = positionMs().coerceIn(0, lengthMs).toFloat(), trackRange = 0f..lengthMs.toFloat()) }
    var dragging by remember { mutableStateOf(false) }
    LaunchedEffect(state) {
        snapshotFlow { positionMs() }.collect { if (!dragging) state.value = it.coerceIn(0, lengthMs).toFloat() }
    }
    val played = MaterialTheme.colorScheme.primary
    val marker = MaterialTheme.colorScheme.tertiary
    val description = stringResource(Res.string.vod_seek_description)
    Slider(
        state = state,
        onValueChange = {
            dragging = true
            state.value = it
            onScrub(it.toLong())
        },
        onValueChangeFinished = {
            dragging = false
            onSeek(state.value.toLong())
        },
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = description },
        track = { s ->
            Canvas(Modifier.fillMaxWidth().height(12.dp)) {
                drawSeekTrack(s.value / lengthMs, lengthMs, markers, played, marker)
            }
        },
    )
}

/** The seek bar's track: [playedFraction] of it filled, with the breaks marked over it. */
private fun DrawScope.drawSeekTrack(playedFraction: Float, lengthMs: Long?, markers: List<VodMarker>, played: Color, marker: Color) {
    val barHeight = 6.dp.toPx()
    val top = (size.height - barHeight) / 2
    val radius = CornerRadius(barHeight / 2)
    drawRoundRect(SofaOverlayTrack, Offset(0f, top), Size(size.width, barHeight), radius)
    if (lengthMs == null) return
    fun x(ms: Long) = (ms.coerceIn(0, lengthMs).toFloat() / lengthMs) * size.width
    drawRoundRect(played, Offset(0f, top), Size(playedFraction.coerceIn(0f, 1f) * size.width, barHeight), radius)
    markers.forEach { m ->
        drawRect(marker.copy(alpha = 0.8f), Offset(x(m.startMs), top), Size((x(m.endMs) - x(m.startMs)).coerceAtLeast(2f), barHeight))
    }
}

/** The row's button for [button], with a recording's captions shown as [captionsOn] and the picture at [zoom]. */
@Composable
private fun vodButtonItem(button: VodButton, captionsOn: Boolean, zoom: VideoZoom, onClick: () -> Unit): PlayerButtonItem = when (button) {
    VodButton.SKIP_BREAK -> PlayerButtonItem(SofaIcons.FastForward, stringResource(Res.string.vod_control_skip_break), onClick)
    VodButton.TRACKS -> PlayerButtonItem(SofaIcons.Subtitles, stringResource(Res.string.vod_tracks_title), onClick)
    VodButton.CAPTIONS -> PlayerButtonItem(
        if (captionsOn) SofaIcons.ClosedCaption else SofaIcons.ClosedCaptionOff,
        stringResource(Res.string.player_control_captions),
        onClick,
        state = stringResource(if (captionsOn) Res.string.captions_on else Res.string.captions_off),
    )
    VodButton.ZOOM -> zoomButtonItem(zoom, onClick)
    VodButton.START_OVER -> PlayerButtonItem(SofaIcons.Replay, stringResource(Res.string.vod_control_start_over), onClick)
    VodButton.NEXT -> PlayerButtonItem(SofaIcons.SkipNext, stringResource(Res.string.vod_control_next), onClick)
}

/** The buttons that keep focus after a press, as each press steps them on (the track card takes it itself). */
private val STAYING_BUTTONS = setOf(VodButton.CAPTIONS, VodButton.ZOOM, VodButton.TRACKS)

/** A CC or Zoom press's answer, [text] with [arg] in it if any, shown for a moment; compared by identity so a repeated press restarts it. */
private class VodToast(val text: StringResource, val arg: StringResource? = null)

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
private val SUBTITLE_GAP = 24.dp
private val log = Logger.withTag("SofaKing")
private const val TICK_MS = 250L
private const val REPORT_MS = 1_000L
private const val TOAST_MS = 3_000L
