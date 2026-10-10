@file:kotlin.OptIn(ExperimentalTvMaterial3Api::class)

package dev.jamesrich.sofaking.ui.preview

import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import dev.jamesrich.sofaking.R
import dev.jamesrich.sofaking.data.Channel
import dev.jamesrich.sofaking.data.Programme
import dev.jamesrich.sofaking.ui.PopIn
import dev.jamesrich.sofaking.ui.PopOut
import dev.jamesrich.sofaking.ui.SofaLoader
import dev.jamesrich.sofaking.ui.label
import dev.jamesrich.sofaking.ui.rememberSettled
import dev.jamesrich.sofaking.ui.player.LiveVideo
import dev.jamesrich.sofaking.ui.player.currentStreamUrl
import dev.jamesrich.sofaking.ui.player.recoverIfBehindLive
import dev.jamesrich.sofaking.ui.range
import dev.jamesrich.sofaking.ui.rememberTimeFormatter
import dev.jamesrich.sofaking.ui.theme.SofaDusk
import dev.jamesrich.sofaking.ui.theme.SofaOverlayScrim
import dev.jamesrich.sofaking.ui.theme.SofaScrim
import kotlinx.coroutines.delay
import java.time.Instant

/** Kill switch: false removes the pane and the preview player entirely. */
const val PREVIEW_ENABLED = true

internal val PREVIEW_WIDTH = 256.dp
/** 16:9 for [PREVIEW_WIDTH]. */
internal val PREVIEW_HEIGHT = 144.dp
/** The whole top band, exactly the preview's height; see the row budget in GuideScreen. */
internal val PREVIEW_BAND_HEIGHT = 144.dp
// Long enough to skip rows passed under a held D-pad (repeats every 50-150 ms); each tune cold-starts a Dispatcharr channel.
private const val PREVIEW_DEBOUNCE_MS = 350L

/** Programme details for the focused cell. [live] is whether the programme is airing at [now]. */
@Composable
internal fun PreviewDetails(
    channel: Channel?,
    programme: Programme?,
    live: Boolean,
    now: Instant,
    modifier: Modifier = Modifier,
) {
    val timeFormat = rememberTimeFormatter()
    Column(modifier) {
        if (channel == null) return@Column
        Text(
            channel.label(),
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
            timeFormat.range(programme.start, programme.stop),
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
 * Muted live preview of [channel] on the app's shared [player]. Tunes [PREVIEW_DEBOUNCE_MS] after focus
 * rests on a different stream; a stream the player already holds (the channel just watched) keeps playing.
 * The player belongs to the app, which stops it in the background and releases it.
 */
@OptIn(UnstableApi::class)
@Composable
internal fun PreviewVideo(player: ExoPlayer, channel: Channel?, modifier: Modifier = Modifier) {
    var buffering by remember { mutableStateOf(player.playbackState == Player.STATE_BUFFERING) }
    var failed by remember { mutableStateOf(false) }

    DisposableEffect(player) {
        player.volume = 0f
        // Back from a paused full screen: rejoin the live edge rather than play from where it paused.
        if (!player.playWhenReady) {
            player.playWhenReady = true
            player.stop()
            player.prepare()
        }
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                buffering = playbackState == Player.STATE_BUFFERING
                if (playbackState == Player.STATE_READY) failed = false
            }

            override fun onPlayerError(error: PlaybackException) {
                if (!player.recoverIfBehindLive(error)) failed = true
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    val streamUrl = channel?.streamUrl
    LaunchedEffect(streamUrl) {
        if (streamUrl == null) return@LaunchedEffect
        if (player.currentStreamUrl == streamUrl) {
            if (player.playbackState == Player.STATE_IDLE) player.prepare()
            return@LaunchedEffect
        }
        delay(PREVIEW_DEBOUNCE_MS)
        failed = false
        // Reset the renderers before a new item, as the full-screen player does.
        player.stop()
        player.setMediaItem(MediaItem.fromUri(streamUrl))
        player.prepare()
    }

    Box(modifier.width(PREVIEW_WIDTH).height(PREVIEW_HEIGHT).background(SofaScrim)) {
        // One surface for the life of the preview; the card covers it from a reset until the next first frame.
        LiveVideo(player, Modifier.fillMaxSize()) { PreviewCard(channel, failed) }
        if (failed) PreviewCard(channel, failed = true)
        AnimatedVisibility(
            rememberSettled(buffering && !failed),
            modifier = Modifier.align(Alignment.BottomStart).padding(8.dp),
            enter = PopIn,
            exit = PopOut,
        ) {
            Row(
                Modifier
                    .background(SofaOverlayScrim, RoundedCornerShape(8.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                SofaLoader(16.dp)
                Text(stringResource(R.string.tuning), style = MaterialTheme.typography.labelLarge, color = SofaDusk)
            }
        }
    }
}

/** A raised plum card in place of the picture, so the band never shows a bare black box. */
@Composable
private fun PreviewCard(channel: Channel?, failed: Boolean) {
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant).padding(12.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        if (channel == null) {
            Text(
                stringResource(R.string.preview_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                channel.label(),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (failed) {
                Text(
                    stringResource(R.string.preview_unavailable),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
