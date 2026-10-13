
package dev.jamesrich.sofaking.ui.preview

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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.withStateAtLeast
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import dev.jamesrich.sofaking.resources.*
import dev.jamesrich.sofaking.data.CaptionStyle
import dev.jamesrich.sofaking.data.Channel
import dev.jamesrich.sofaking.data.PlaybackIntent
import dev.jamesrich.sofaking.data.PlaybackSessions
import dev.jamesrich.sofaking.data.Programme
import dev.jamesrich.sofaking.ui.PopIn
import dev.jamesrich.sofaking.ui.PopOut
import dev.jamesrich.sofaking.ui.SofaLoader
import dev.jamesrich.sofaking.ui.label
import dev.jamesrich.sofaking.ui.player.Captions
import dev.jamesrich.sofaking.ui.player.LivePlayer
import dev.jamesrich.sofaking.ui.player.PlaybackState
import dev.jamesrich.sofaking.ui.rememberSettled
import dev.jamesrich.sofaking.ui.rememberTimeFormatter
import dev.jamesrich.sofaking.ui.theme.SofaDusk
import dev.jamesrich.sofaking.ui.theme.SofaOverlayScrim
import dev.jamesrich.sofaking.ui.theme.SofaScrim
import kotlin.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** Kill switch: false removes the pane and the preview player entirely. */
const val PREVIEW_ENABLED = true

internal val PREVIEW_WIDTH = 256.dp
/** 16:9 for [PREVIEW_WIDTH]. */
internal val PREVIEW_HEIGHT = 144.dp
/** The whole top band, exactly the preview's height; see the row budget in GuideScreen. */
internal val PREVIEW_BAND_HEIGHT = 144.dp
// Long enough to skip rows passed under a held D-pad (repeats every 50-150 ms); each tune opens a session and cold-starts a relay.
private const val PREVIEW_DEBOUNCE_MS = 350L
// How often a 410 from the relay reopens the same channel before the preview gives up on it.
private const val MAX_STREAM_GONE_RETUNES = 2

/** Captions are drawn over the preview only while the viewer has them on and the picture is there to caption. */
internal fun showPreviewCaptions(captionsEnabled: Boolean, hasChannel: Boolean, failed: Boolean): Boolean =
    captionsEnabled && hasChannel && !failed

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
                stringResource(Res.string.no_listing),
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
                    stringResource(Res.string.preview_live),
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
 * Muted live preview of [channel] on the app's shared [player]. Opens a preview session through [sessions]
 * [PREVIEW_DEBOUNCE_MS] after focus rests on a different channel; a stream the player already holds (the
 * channel just watched) keeps playing and its session goes back to preview intent. The player belongs to
 * the app, which stops it in the background and releases it. Captions are the full screen's, scaled to the box.
 * [muted] follows the guide's rest timers; [onFailedChanged] tells the guide when the preview has no picture.
 */
@Composable
internal fun PreviewVideo(
    player: LivePlayer,
    sessions: PlaybackSessions,
    channel: Channel?,
    captionsEnabled: Boolean,
    captionStyle: CaptionStyle,
    muted: Boolean,
    onFailedChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val status by player.status.collectAsStateWithLifecycle()
    val buffering = status.state == PlaybackState.BUFFERING
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(failed) { onFailedChanged(failed) }
    LaunchedEffect(muted) { player.volume = if (muted) 0f else 1f }
    // Bumped when the relay answers 410, so the tune effect reopens the channel at once instead of after the debounce.
    var retune by remember { mutableIntStateOf(0) }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(player) {
        player.volume = 0f
        // Back from a paused full screen: rejoin the live edge rather than play from where it paused.
        if (!player.playWhenReady) {
            player.playWhenReady = true
            player.stop()
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) player.prepare()
        }
        onDispose {}
    }
    LaunchedEffect(player) {
        player.status.collect { if (it.state == PlaybackState.READY) failed = false }
    }
    LaunchedEffect(player) {
        player.errors.collect { error ->
            if (error.streamGone && retune < MAX_STREAM_GONE_RETUNES) retune++ else failed = true
        }
    }

    val guideId = channel?.guideId
    LaunchedEffect(guideId) { retune = 0 }
    LaunchedEffect(guideId, retune, lifecycle) {
        if (guideId == null) return@LaunchedEffect
        val held = player.currentChannelId == guideId
        if (held && retune == 0 && player.status.value.state != PlaybackState.IDLE) {
            // Back from the full screen with its stream still playing: keep it, as a preview again.
            try {
                sessions.setIntent(guideId, PlaybackIntent.PREVIEW)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The lease keeps renewing either way; a watch-intent preview only holds its slot longer.
            }
            return@LaunchedEffect
        }
        if (!held && retune == 0) delay(PREVIEW_DEBOUNCE_MS)
        lifecycle.withStateAtLeast(Lifecycle.State.STARTED) {
            failed = false
            // Stopped before the session changes hands: releasing the old session ends its stream, which would
            // otherwise surface as a player error. This also resets the renderers, as the full screen does.
            player.stop()
        }
        val stream = try {
            sessions.tune(guideId, PlaybackIntent.PREVIEW)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            failed = true
            return@LaunchedEffect
        }
        lifecycle.withStateAtLeast(Lifecycle.State.STARTED) {
            player.setStream(stream)
            player.prepare()
        }
    }

    Box(modifier.width(PREVIEW_WIDTH).height(PREVIEW_HEIGHT).background(SofaScrim)) {
        // One surface for the life of the preview; the card covers it from a reset until the next first frame.
        player.Video(Modifier.fillMaxSize()) { PreviewCard(channel, failed) }
        // No overscan inset: the box sits inside the guide's margins already.
        if (showPreviewCaptions(captionsEnabled, channel != null, failed)) Captions(player, captionStyle, bottomFraction = 0f, Modifier.fillMaxSize())
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
                SofaLoader(size = 16.dp)
                Text(stringResource(Res.string.tuning), style = MaterialTheme.typography.labelLarge, color = SofaDusk)
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
                stringResource(Res.string.preview_hint),
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
                    stringResource(Res.string.preview_unavailable),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
