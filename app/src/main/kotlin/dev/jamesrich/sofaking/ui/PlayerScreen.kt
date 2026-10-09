package dev.jamesrich.sofaking.ui

import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.session.MediaSession
import androidx.media3.ui.PlayerView
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import dev.jamesrich.sofaking.R
import dev.jamesrich.sofaking.data.AppSettings
import dev.jamesrich.sofaking.data.Channel
import dev.jamesrich.sofaking.data.Lineup
import dev.jamesrich.sofaking.ui.player.CaptionPolicy
import dev.jamesrich.sofaking.ui.player.CaptionSync
import dev.jamesrich.sofaking.ui.player.CaptionSyncLoadControl
import dev.jamesrich.sofaking.ui.player.CaptionSyncRenderersFactory
import dev.jamesrich.sofaking.ui.player.CaptionToggleOutcome
import dev.jamesrich.sofaking.ui.player.ChannelNumberEntry
import dev.jamesrich.sofaking.ui.player.programmeProgress
import android.util.Log
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private const val ZAP_OVERLAY_MS = 5_000L
private const val DIGIT_COMMIT_MS = 1_500L
private const val NOT_FOUND_MS = 2_000L
private const val RETRY_MS = 3_000L
private const val CAPTION_TOAST_MS = 2_000L
private const val TAG = "SofaKing"

/** Digit-entry miss; compared by identity so a repeated miss restarts its timer. */
private class Notice(val digits: String)

/** Caption toggle feedback; compared by identity so a repeated press restarts its timer. */
private class CaptionToast(val outcome: CaptionToggleOutcome)

/** A playback error that is not behind-live-window; [retriesUsed] is 0 until the one automatic retry. */
private data class Failure(val code: String, val retriesUsed: Int)

/** CEA-608 CC1 and CEA-708 service 1, declared together so the SEI reader emits both. */
@OptIn(UnstableApi::class)
private fun captionFormats(): List<Format> = listOf(
    Format.Builder().setSampleMimeType(MimeTypes.APPLICATION_CEA608).setAccessibilityChannel(1).build(),
    Format.Builder().setSampleMimeType(MimeTypes.APPLICATION_CEA708).setAccessibilityChannel(1).build(),
)

/** Text tracks are disabled rather than unselected so a zap keeps the user's choice. */
private fun captionParameters(base: TrackSelectionParameters, enabled: Boolean): TrackSelectionParameters =
    base.buildUpon()
        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !enabled)
        .setPreferredTextLanguage(CaptionPolicy.PREFERRED_LANGUAGE)
        .setSelectUndeterminedTextLanguage(true)
        .build()

/** Dispatcharr proxies streams as continuous MPEG-TS, which can start mid-GOP. */
@OptIn(UnstableApi::class)
private fun buildLivePlayer(context: Context, captionsOn: Boolean, sync: CaptionSync): ExoPlayer {
    val extractors = DefaultExtractorsFactory()
        .setTsExtractorFlags(
            DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS,
        )
        .setTsSubtitleFormats(captionFormats())
    return ExoPlayer.Builder(context, CaptionSyncRenderersFactory(context, sync))
        .setLoadControl(CaptionSyncLoadControl(sync))
        .setMediaSourceFactory(DefaultMediaSourceFactory(context, extractors))
        .build()
        .apply {
            trackSelectionParameters = captionParameters(trackSelectionParameters, captionsOn)
            playWhenReady = true
        }
}

private fun CaptionToggleOutcome.labelRes(): Int = when (this) {
    CaptionToggleOutcome.ON -> R.string.captions_on
    CaptionToggleOutcome.OFF -> R.string.captions_off
    CaptionToggleOutcome.NO_CAPTIONS_ON_CHANNEL -> R.string.captions_unavailable
}

private fun Key.digitValue(): Int? = when (this) {
    Key.Zero, Key.NumPad0 -> 0
    Key.One, Key.NumPad1 -> 1
    Key.Two, Key.NumPad2 -> 2
    Key.Three, Key.NumPad3 -> 3
    Key.Four, Key.NumPad4 -> 4
    Key.Five, Key.NumPad5 -> 5
    Key.Six, Key.NumPad6 -> 6
    Key.Seven, Key.NumPad7 -> 7
    Key.Eight, Key.NumPad8 -> 8
    Key.Nine, Key.NumPad9 -> 9
    else -> null
}

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(
    lineup: Lineup,
    channels: List<Channel>,
    startIndex: Int,
    onExit: () -> Unit,
    onChannelChanged: (Channel) -> Unit,
    captionsEnabled: Boolean = false,
    onCaptionsChanged: (Boolean) -> Unit = {},
    captionAdvanceFor: (Channel) -> Int = { AppSettings.DEFAULT_CAPTION_ADVANCE_SECONDS },
    onCaptionAdvanceChanged: (Channel, Int) -> Unit = { _, _ -> },
) {
    val context = LocalContext.current
    val currentChannels by rememberUpdatedState(channels)
    val currentOnChannelChanged by rememberUpdatedState(onChannelChanged)
    val currentOnCaptionsChanged by rememberUpdatedState(onCaptionsChanged)
    val currentOnCaptionAdvanceChanged by rememberUpdatedState(onCaptionAdvanceChanged)
    val captionBottomFraction = CaptionPolicy.bottomPaddingFraction(LocalConfiguration.current.screenHeightDp)
    var index by remember { mutableIntStateOf(startIndex.coerceIn(0, (channels.size - 1).coerceAtLeast(0))) }
    var previousIndex by remember { mutableStateOf<Int?>(null) }
    var entry by remember { mutableStateOf(ChannelNumberEntry()) }
    var notice by remember { mutableStateOf<Notice?>(null) }
    var overlayVisible by remember { mutableStateOf(true) }
    var overlayRequest by remember { mutableIntStateOf(0) }
    var playing by remember { mutableStateOf(false) }
    var buffering by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<Failure?>(null) }
    var captionsOn by remember { mutableStateOf(captionsEnabled) }
    var captionToast by remember { mutableStateOf<CaptionToast?>(null) }

    val captionSync = remember { CaptionSync() }
    // Each channel's captioner runs a different distance behind; adjustments show before the store round-trips.
    var advanceOverrides by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    val currentChannel = currentChannels.getOrNull(index)
    val channelAdvance = currentChannel?.let { advanceOverrides[it.guideId] ?: captionAdvanceFor(it) } ?: 0
    captionSync.advanceUs = if (captionsOn) channelAdvance * 1_000_000L else 0L

    // One player for the whole session; zapping swaps the media item instead of rebuilding the pipeline.
    val player = remember { buildLivePlayer(context, captionsEnabled, captionSync) }
    DisposableEffect(player) {
        val session = MediaSession.Builder(context, player).build()
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing = isPlaying
                if (isPlaying) failure = null
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                buffering = playbackState == Player.STATE_BUFFERING
                if (playbackState == Player.STATE_READY) failure = null
            }

            override fun onPlayerError(error: PlaybackException) {
                if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                    player.seekToDefaultPosition()
                    player.prepare()
                    return
                }
                failure = Failure(error.errorCodeName, retriesUsed = failure?.retriesUsed ?: 0)
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            session.release()
            player.release()
        }
    }

    fun load(channel: Channel) {
        // Reset the renderers so the next stream's decoder rescales instead of reusing the old surface size.
        player.stop()
        // Playback can't start until the full advance is buffered, so a fresh tune starts at the full lead.
        captionSync.effectiveUs = captionSync.advanceUs
        player.setMediaItem(
            MediaItem.Builder()
                .setUri(channel.streamUrl)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(channel.name)
                        .setArtworkUri(channel.logo?.let(Uri::parse))
                        .setIsPlayable(true)
                        .build(),
                )
                .build(),
        )
        player.prepare()
    }

    fun zapTo(target: Int) {
        entry = ChannelNumberEntry()
        failure = null
        if (target != index) {
            previousIndex = index
            index = target
        }
        overlayVisible = true
        overlayRequest++
    }

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

    LaunchedEffect(index) {
        val channel = currentChannels.getOrNull(index) ?: return@LaunchedEffect
        load(channel)
        currentOnChannelChanged(channel)
    }

    // Keeps the caption look-ahead inside what's downloaded; see CaptionSync.nextEffectiveUs.
    LaunchedEffect(player) {
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
            if (++tick % 8 == 0 && captionSync.advanceUs > 0) {
                Log.d(TAG, "captions: buffered=${player.totalBufferedDuration}ms lead=${captionSync.effectiveUs / 1000}ms of ${captionSync.advanceUs / 1000}ms")
            }
        }
    }

    // Auto-retry once after 3 s; a second failure leaves the message up.
    LaunchedEffect(failure) {
        val current = failure ?: return@LaunchedEffect
        if (current.retriesUsed > 0) return@LaunchedEffect
        delay(RETRY_MS)
        val channel = currentChannels.getOrNull(index) ?: return@LaunchedEffect
        failure = current.copy(retriesUsed = 1)
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
    LaunchedEffect(Unit) { focus.requestFocus() }
    BackHandler(onBack = onExit)

    fun toggleCaptions() {
        val enabled = !captionsOn
        captionsOn = enabled
        player.trackSelectionParameters = captionParameters(player.trackSelectionParameters, enabled)
        val channelHasText = CaptionPolicy.hasTextTrack(player.currentTracks.groups.map { it.type })
        captionToast = CaptionToast(CaptionPolicy.toggleOutcome(enabled, channelHasText))
        currentOnCaptionsChanged(enabled)
    }

    fun handleKey(key: Key): Boolean {
        if (currentChannels.isEmpty()) return false
        key.digitValue()?.let { digit ->
            entry = entry.append(digit)
            return true
        }
        val size = currentChannels.size
        return when (key) {
            Key.Captions, Key.C -> { toggleCaptions(); true }
            Key.DirectionUp, Key.ChannelUp -> { zapTo((index + 1) % size); true }
            Key.DirectionDown, Key.ChannelDown -> { zapTo((index - 1 + size) % size); true }
            Key.MediaPrevious, Key.LastChannel -> { previousIndex?.let { zapTo(it) }; true }
            Key.DirectionCenter, Key.Enter -> {
                if (entry.digits.isNotEmpty()) commitEntry() else overlayVisible = !overlayVisible
                true
            }
            Key.Info -> { if (entry.digits.isEmpty()) overlayVisible = !overlayVisible; true }
            Key.DirectionLeft, Key.DirectionRight -> {
                val channel = currentChannels.getOrNull(index)
                if (captionsOn && overlayVisible && channel != null) {
                    // Right shows captions earlier, Left later; the overlay stays up while adjusting.
                    val step = if (key == Key.DirectionRight) 1 else -1
                    val next = (channelAdvance + step).coerceIn(AppSettings.CAPTION_ADVANCE_RANGE)
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

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focus)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                handleKey(event.key)
            },
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = {
                PlayerView(it).apply {
                    useController = false
                    this.player = player
                    subtitleView?.apply {
                        setUserDefaultStyle()
                        setUserDefaultTextSize()
                        setBottomPaddingFraction(captionBottomFraction)
                    }
                }
            },
            update = { view -> view.keepScreenOn = playing },
        )

        val channel = currentChannels.getOrNull(index)
        if (channel == null) {
            Text(stringResource(R.string.no_channels), style = MaterialTheme.typography.titleLarge, color = Color.White, modifier = Modifier.align(Alignment.Center))
        } else {
            if (buffering && failure == null) {
                Text(
                    stringResource(R.string.tuning),
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    modifier = Modifier.align(Alignment.TopStart).padding(horizontal = 48.dp, vertical = 27.dp),
                )
            }
            failure?.let { f ->
                Column(
                    Modifier
                        .align(Alignment.Center)
                        .background(Color(0xCC000000))
                        .padding(24.dp),
                ) {
                    Text(stringResource(R.string.cant_play), style = MaterialTheme.typography.headlineSmall, color = Color.White)
                    Text(f.code, style = MaterialTheme.typography.bodyMedium, color = Color.LightGray)
                }
            }
            val pendingDigits = entry.digits
            val noticeText = notice?.let { stringResource(R.string.no_channel_number, it.digits) }
            if (pendingDigits.isNotEmpty() || noticeText != null) {
                Text(
                    pendingDigits.ifEmpty { noticeText.orEmpty() },
                    style = MaterialTheme.typography.displayMedium,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(horizontal = 48.dp, vertical = 27.dp)
                        .background(Color(0xCC000000))
                        .padding(horizontal = 16.dp),
                )
            }
            captionToast?.let { toast ->
                Text(
                    stringResource(toast.outcome.labelRes()),
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(horizontal = 48.dp, vertical = 27.dp)
                        .background(Color(0xCC000000))
                        .padding(horizontal = 16.dp),
                )
            }
            if (overlayVisible) {
                InfoOverlay(lineup = lineup, channel = channel, now = Instant.now(), captionsOn = captionsOn, captionAdvance = channelAdvance)
            }
        }
    }
}

@Composable
private fun BoxScope.InfoOverlay(lineup: Lineup, channel: Channel, now: Instant, captionsOn: Boolean, captionAdvance: Int) {
    val (current, next) = lineup.nowAndNext(channel, now)
    val timeFormat = remember { DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault()).withZone(ZoneId.systemDefault()) }
    Column(
        Modifier
            .align(Alignment.BottomStart)
            .padding(horizontal = 48.dp, vertical = 27.dp)
            .width(720.dp)
            .background(Color(0xCC000000))
            .padding(24.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${channel.number.formatNumber()}  ${channel.name}", style = MaterialTheme.typography.headlineSmall, color = Color.White)
            if (captionsOn) {
                Spacer(Modifier.width(16.dp))
                Text(
                    stringResource(R.string.captions_indicator_timing, captionAdvance),
                    style = MaterialTheme.typography.labelLarge,
                    color = Color.White,
                    modifier = Modifier.background(Color(0x66FFFFFF)).padding(horizontal = 8.dp),
                )
            }
        }
        if (current == null) {
            Text(stringResource(R.string.no_listing), style = MaterialTheme.typography.titleMedium, color = Color.White)
        } else {
            Text(current.title, style = MaterialTheme.typography.titleMedium, color = Color.White)
            Text(
                "${timeFormat.format(current.start)} - ${timeFormat.format(current.stop)}",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.LightGray,
            )
            Spacer(Modifier.height(8.dp))
            ProgressBar(programmeProgress(current.start, current.stop, now))
        }
        next?.let { Text(stringResource(R.string.up_next, it.title), style = MaterialTheme.typography.bodyMedium, color = Color.LightGray) }
    }
}

@Composable
private fun ProgressBar(fraction: Float) {
    Box(Modifier.fillMaxWidth().height(4.dp).background(Color(0x66FFFFFF))) {
        Box(Modifier.fillMaxWidth(fraction).height(4.dp).background(Color.White))
    }
}
