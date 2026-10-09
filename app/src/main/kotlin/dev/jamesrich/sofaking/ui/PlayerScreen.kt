package dev.jamesrich.sofaking.ui

import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
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
import dev.jamesrich.sofaking.data.Channel
import dev.jamesrich.sofaking.data.Lineup
import dev.jamesrich.sofaking.ui.player.ChannelNumberEntry
import dev.jamesrich.sofaking.ui.player.programmeProgress
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private const val ZAP_OVERLAY_MS = 5_000L
private const val DIGIT_COMMIT_MS = 1_500L
private const val NOT_FOUND_MS = 2_000L
private const val RETRY_MS = 3_000L

/** Digit-entry miss; compared by identity so a repeated miss restarts its timer. */
private class Notice(val digits: String)

/** A playback error that is not behind-live-window; [retriesUsed] is 0 until the one automatic retry. */
private data class Failure(val code: String, val retriesUsed: Int)

/** Dispatcharr proxies streams as continuous MPEG-TS, which can start mid-GOP. */
@OptIn(UnstableApi::class)
private fun buildLivePlayer(context: Context): ExoPlayer {
    val extractors = DefaultExtractorsFactory().setTsExtractorFlags(
        DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS,
    )
    return ExoPlayer.Builder(context)
        .setMediaSourceFactory(DefaultMediaSourceFactory(context, extractors))
        .build()
        .apply { playWhenReady = true }
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
) {
    val context = LocalContext.current
    val currentChannels by rememberUpdatedState(channels)
    val currentOnChannelChanged by rememberUpdatedState(onChannelChanged)
    var index by remember { mutableIntStateOf(startIndex.coerceIn(0, (channels.size - 1).coerceAtLeast(0))) }
    var previousIndex by remember { mutableStateOf<Int?>(null) }
    var entry by remember { mutableStateOf(ChannelNumberEntry()) }
    var notice by remember { mutableStateOf<Notice?>(null) }
    var overlayVisible by remember { mutableStateOf(true) }
    var overlayRequest by remember { mutableIntStateOf(0) }
    var playing by remember { mutableStateOf(false) }
    var buffering by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<Failure?>(null) }

    // One player for the whole session; zapping swaps the media item instead of rebuilding the pipeline.
    val player = remember { buildLivePlayer(context) }
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

    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    BackHandler(onBack = onExit)

    fun handleKey(key: Key): Boolean {
        if (currentChannels.isEmpty()) return false
        key.digitValue()?.let { digit ->
            entry = entry.append(digit)
            return true
        }
        val size = currentChannels.size
        return when (key) {
            Key.DirectionUp, Key.ChannelUp -> { zapTo((index + 1) % size); true }
            Key.DirectionDown, Key.ChannelDown -> { zapTo((index - 1 + size) % size); true }
            Key.MediaPrevious, Key.LastChannel -> { previousIndex?.let { zapTo(it) }; true }
            Key.DirectionCenter, Key.Enter -> {
                if (entry.digits.isNotEmpty()) commitEntry() else overlayVisible = !overlayVisible
                true
            }
            Key.Info -> { if (entry.digits.isEmpty()) overlayVisible = !overlayVisible; true }
            Key.DirectionLeft, Key.DirectionRight -> { overlayVisible = !overlayVisible; true }
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
            if (overlayVisible) {
                InfoOverlay(lineup = lineup, channel = channel, now = Instant.now())
            }
        }
    }
}

@Composable
private fun BoxScope.InfoOverlay(lineup: Lineup, channel: Channel, now: Instant) {
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
        Text("${channel.number.formatNumber()}  ${channel.name}", style = MaterialTheme.typography.headlineSmall, color = Color.White)
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
