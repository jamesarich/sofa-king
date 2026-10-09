package dev.jamesrich.sofaking.ui

import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.media3.common.MediaItem
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
import dev.jamesrich.sofaking.data.Lineup
import kotlinx.coroutines.delay
import java.time.Instant

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

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(lineup: Lineup, startIndex: Int, onExit: () -> Unit) {
    val context = LocalContext.current
    val channels = lineup.channels
    var index by remember { mutableIntStateOf(startIndex.coerceIn(0, channels.lastIndex)) }
    var overlayVisible by remember { mutableStateOf(true) }
    // One player for the whole session; zapping swaps the media item instead of rebuilding the pipeline.
    val player = remember { buildLivePlayer(context) }
    DisposableEffect(player) {
        val session = MediaSession.Builder(context, player).build()
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                    player.seekToDefaultPosition()
                    player.prepare()
                }
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            session.release()
            player.release()
        }
    }

    LaunchedEffect(index) {
        player.setMediaItem(MediaItem.fromUri(channels[index].streamUrl))
        player.prepare()
        overlayVisible = true
        delay(4_000)
        overlayVisible = false
    }

    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    BackHandler(onBack = onExit)

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focus)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionUp, Key.ChannelUp -> { index = (index + 1) % channels.size; true }
                    Key.DirectionDown, Key.ChannelDown -> { index = (index - 1 + channels.size) % channels.size; true }
                    Key.DirectionCenter, Key.Enter, Key.Info -> { overlayVisible = !overlayVisible; true }
                    else -> false
                }
            },
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = {
                PlayerView(it).apply {
                    useController = false
                    this.player = player
                    // Ambient mode may start only when nothing is actually playing.
                    player.addListener(object : Player.Listener {
                        override fun onIsPlayingChanged(isPlaying: Boolean) {
                            keepScreenOn = isPlaying
                        }
                    })
                }
            },
        )
        if (overlayVisible) {
            val channel = channels[index]
            val (current, next) = lineup.nowAndNext(channel, Instant.now())
            Column(
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(48.dp)
                    .background(Color(0xCC000000))
                    .padding(24.dp),
            ) {
                Text("${channel.number.formatNumber()}  ${channel.name}", style = MaterialTheme.typography.headlineSmall, color = Color.White)
                Text(current?.title ?: stringResource(R.string.no_listing), style = MaterialTheme.typography.titleMedium, color = Color.White)
                next?.let { Text(stringResource(R.string.up_next, it.title), style = MaterialTheme.typography.bodyMedium, color = Color.LightGray) }
            }
        }
    }
}
