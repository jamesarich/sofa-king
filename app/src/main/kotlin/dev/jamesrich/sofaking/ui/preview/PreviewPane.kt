@file:kotlin.OptIn(ExperimentalTvMaterial3Api::class)

package dev.jamesrich.sofaking.ui.preview

import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import dev.jamesrich.sofaking.R
import dev.jamesrich.sofaking.data.Channel
import dev.jamesrich.sofaking.data.Programme
import dev.jamesrich.sofaking.ui.formatNumber
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Kill switch: false removes the pane and the preview player entirely. */
const val PREVIEW_ENABLED = true

internal val PREVIEW_WIDTH = 256.dp
/** 16:9 for [PREVIEW_WIDTH]. */
internal val PREVIEW_HEIGHT = 144.dp
/** The whole top band, exactly the preview's height; see the row budget in GuideScreen. */
internal val PREVIEW_BAND_HEIGHT = 144.dp
private const val PREVIEW_DEBOUNCE_MS = 800L

private val TIME_FORMAT_PATTERN = "h:mm a"

/** Programme details for the focused cell. [live] is whether the programme is airing at [now]. */
@Composable
internal fun PreviewDetails(
    channel: Channel?,
    programme: Programme?,
    live: Boolean,
    now: Instant,
    modifier: Modifier = Modifier,
) {
    val timeFormat = remember {
        DateTimeFormatter.ofPattern(TIME_FORMAT_PATTERN, Locale.getDefault()).withZone(ZoneId.systemDefault())
    }
    Column(modifier) {
        if (channel == null) return@Column
        Text(
            "${channel.number.formatNumber()}  ${channel.name}",
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (programme == null) {
            Text(
                stringResource(R.string.no_listing),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            return@Column
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                programme.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (live) {
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.preview_live),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimary,
                    maxLines = 1,
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 1.dp),
                )
            }
        }
        Text(
            "${timeFormat.format(programme.start)} - ${timeFormat.format(programme.stop)}",
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
        )
        programme.subtitle?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        programme.description?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Muted live preview of [channel]. Tunes [PREVIEW_DEBOUNCE_MS] after focus rests on a stream, and
 * stops on ON_STOP and resumes on ON_START. The one preview player is released when this leaves composition.
 */
@OptIn(UnstableApi::class)
@Composable
internal fun PreviewVideo(channel: Channel?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val player = remember { buildPreviewPlayer(context) }
    val tuner = remember { PreviewTuner() }
    var playing by remember { mutableStateOf(false) }
    var buffering by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing = isPlaying
                if (isPlaying) failed = false
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                buffering = playbackState == Player.STATE_BUFFERING
            }

            override fun onPlayerError(error: PlaybackException) {
                if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                    player.seekToDefaultPosition()
                    player.prepare()
                    return
                }
                failed = true
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, player) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                player.stop()
            } else if (event == Lifecycle.Event.ON_START && player.mediaItemCount > 0) {
                // Re-prepares the item already set; a live stream resumes at the live edge.
                player.prepare()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val streamUrl = channel?.streamUrl
    LaunchedEffect(streamUrl) {
        if (streamUrl == null) return@LaunchedEffect
        delay(PREVIEW_DEBOUNCE_MS)
        if (!tuner.needsTune(streamUrl)) return@LaunchedEffect
        failed = false
        // Reset the renderers before a new item, as the full-screen player does.
        player.stop()
        player.setMediaItem(MediaItem.fromUri(streamUrl))
        player.prepare()
        tuner.markTuned(streamUrl)
    }

    Box(modifier.width(PREVIEW_WIDTH).height(PREVIEW_HEIGHT).background(Color.Black)) {
        if (playing) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = {
                    PlayerView(it).apply {
                        useController = false
                        this.player = player
                    }
                },
            )
        } else {
            Column(Modifier.align(Alignment.Center).padding(12.dp)) {
                if (channel == null) {
                    Text(
                        stringResource(R.string.preview_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White,
                    )
                } else {
                    Text(
                        "${channel.number.formatNumber()}  ${channel.name}",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (failed) {
                        Text(
                            stringResource(R.string.preview_unavailable),
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.LightGray,
                        )
                    }
                }
            }
        }
        if (buffering) {
            Text(
                stringResource(R.string.tuning),
                style = MaterialTheme.typography.labelLarge,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .background(Color(0xCC000000))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}
