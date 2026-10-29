package dev.jamesrich.sofaking.ui.preview

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.withStateAtLeast
import coil3.compose.AsyncImage
import dev.jamesrich.sofaking.data.CaptionStyle
import dev.jamesrich.sofaking.data.Channel
import dev.jamesrich.sofaking.data.PREVIEW_DEBOUNCE_MS
import dev.jamesrich.sofaking.data.PlaybackIntent
import dev.jamesrich.sofaking.data.PlaybackSessions
import dev.jamesrich.sofaking.data.Program
import dev.jamesrich.sofaking.data.runCatchingCancellable
import dev.jamesrich.sofaking.resources.Res
import dev.jamesrich.sofaking.resources.preview_hint
import dev.jamesrich.sofaking.resources.preview_unavailable
import dev.jamesrich.sofaking.resources.tuning
import dev.jamesrich.sofaking.ui.DETAIL_SEPARATOR
import dev.jamesrich.sofaking.ui.PopIn
import dev.jamesrich.sofaking.ui.PopOut
import dev.jamesrich.sofaking.ui.ProgramBadges
import dev.jamesrich.sofaking.ui.SofaLoader
import dev.jamesrich.sofaking.ui.WholeLinesText
import dev.jamesrich.sofaking.ui.appLifecycleOwner
import dev.jamesrich.sofaking.ui.episodeLine
import dev.jamesrich.sofaking.ui.label
import dev.jamesrich.sofaking.ui.noListingText
import dev.jamesrich.sofaking.ui.player.Captions
import dev.jamesrich.sofaking.ui.player.LivePlayer
import dev.jamesrich.sofaking.ui.player.LocalPlayerVolume
import dev.jamesrich.sofaking.ui.player.PlaybackState
import dev.jamesrich.sofaking.ui.player.PlayerError
import dev.jamesrich.sofaking.ui.player.VideoZoom
import dev.jamesrich.sofaking.ui.rememberSettled
import dev.jamesrich.sofaking.ui.rememberTimeFormatter
import dev.jamesrich.sofaking.ui.theme.SofaOverlayScrim
import dev.jamesrich.sofaking.ui.theme.SofaScrim
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Instant

/** Kill switch: false removes the pane and the preview player entirely. */
const val PREVIEW_ENABLED = true

internal val PREVIEW_WIDTH = 256.dp

/** 16:9 for [PREVIEW_WIDTH]. */
internal val PREVIEW_HEIGHT = 144.dp

/** The whole top band, exactly the preview's height; see the row budget in GuideScreen. */
internal val PREVIEW_BAND_HEIGHT = 144.dp

// How often a 410 from the relay reopens the same channel before the preview gives up on it.
private const val MAX_STREAM_GONE_RETUNES = 2

/** Captions are drawn over the preview only while the viewer has them on and the picture is there to caption. */
internal fun showPreviewCaptions(captionsEnabled: Boolean, hasChannel: Boolean, failed: Boolean): Boolean =
    captionsEnabled && hasChannel && !failed

/** Program details for the focused cell. [live] is whether the program is airing now. */
@Composable
internal fun PreviewDetails(
    channel: Channel?,
    program: Program?,
    live: Boolean,
    guideLoading: Boolean,
    modifier: Modifier = Modifier,
) {
    val timeFormat = rememberTimeFormatter()
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
    // Title, one line of details, then as much description as the band has room for in whole lines; the
    // program's image, when the guide has one, sits beside the text at the band's height.
    Row(modifier) {
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (channel == null) return@Column
            if (program == null) {
                Text(
                    channel.noListingText(guideLoading),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    channel.label(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = secondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                return@Column
            }
            TitleWithBadges(program, live)
            // The channel comes last: the focused row already names it, so it is the part to lose when space runs out.
            Text(
                listOfNotNull(timeFormat.range(program.start, program.stop), program.episodeLine(), channel.label())
                    .joinToString(DETAIL_SEPARATOR),
                style = MaterialTheme.typography.bodyMedium,
                color = secondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            program.description?.let {
                WholeLinesText(it, MaterialTheme.typography.bodySmall, Modifier.fillMaxWidth().weight(1f))
            }
        }
        program?.icon?.let { icon ->
            // The frame holds its place at once; the image loads only once focus has rested on the program, so
            // holding a key down the grid starts no image request per cell.
            val settled = key(icon) { rememberSettled(true, showAfterMs = DETAIL_IMAGE_SETTLE_MS) }
            Box(
                Modifier.padding(start = 16.dp)
                    .size(width = DETAIL_IMAGE_WIDTH, height = DETAIL_IMAGE_HEIGHT)
                    .clip(MaterialTheme.shapes.small),
            ) {
                if (settled) {
                    AsyncImage(
                        model = icon,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}

/** The program's title, with its badges after it. */
@Composable
private fun TitleWithBadges(program: Program, live: Boolean) = Row(verticalAlignment = Alignment.CenterVertically) {
    Text(
        program.title,
        style = MaterialTheme.typography.titleMedium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f, fill = false),
    )
    ProgramBadges(
        program = program,
        live = live,
        fill = MaterialTheme.colorScheme.primary,
        onFill = MaterialTheme.colorScheme.onPrimary,
        outline = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 8.dp),
    )
}

/** The program image beside the details: 16:9, as tall as the text column under the chips. */
private val DETAIL_IMAGE_WIDTH = 160.dp
private val DETAIL_IMAGE_HEIGHT = 90.dp

/** How long focus rests on a program before its image is requested. */
private const val DETAIL_IMAGE_SETTLE_MS = 250L

/**
 * Muted live preview of [channel] on the app's shared [player]. Opens a preview session through [sessions]
 * [PREVIEW_DEBOUNCE_MS] after focus rests on a different channel; a stream the player already holds (the
 * channel just watched) keeps playing and its session goes back to preview intent. The player belongs to
 * the app, which stops it in the background and releases it. Captions are the full screen's, scaled to the box.
 * [muted] follows the guide's rest timers, and sound plays at the players' volume, while [ownsVolume]: the guide
 * stays composed for a frame under the full screen it hands the player to, and its focus leaving the grid mutes the
 * rest timers, which must not silence the full screen. [failed] is the guide's record that the preview has no
 * picture, set through [onFailedChange].
 */
@Composable
internal fun PreviewVideo(
    player: LivePlayer,
    sessions: PlaybackSessions,
    channel: Channel?,
    captionsEnabled: Boolean,
    captionStyle: CaptionStyle,
    muted: Boolean,
    failed: Boolean,
    onFailedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    ownsVolume: Boolean = true,
) {
    val currentOnFailedChange by rememberUpdatedState(onFailedChange)
    val status by player.status.collectAsStateWithLifecycle()
    val buffering = status.state == PlaybackState.BUFFERING
    val gain = LocalPlayerVolume.current.gain
    LaunchedEffect(muted, ownsVolume, gain) { if (ownsVolume) player.volume = if (muted) 0f else gain }
    // Bumped when the relay answers 410, so the tune effect reopens the channel at once instead of after the debounce.
    var retune by remember { mutableIntStateOf(0) }
    // The channel whose stream the player holds; until it is the focused one, the card
    // covers the last channel's picture.
    var showing by remember { mutableStateOf(player.currentChannelId) }

    val lifecycle = appLifecycleOwner.lifecycle
    PreviewPlayerEvents(player, lifecycle) { error ->
        if (error == null) {
            currentOnFailedChange(false)
        } else if (error.streamGone && retune < MAX_STREAM_GONE_RETUNES) {
            retune++
        } else {
            currentOnFailedChange(true)
        }
    }

    val guideId = channel?.guideId
    LaunchedEffect(guideId) { retune = 0 }
    LaunchedEffect(guideId, retune, lifecycle) {
        if (guideId == null) return@LaunchedEffect
        val tuner = PreviewTuner(player, sessions, lifecycle, { currentOnFailedChange(it) }) { showing = it }
        tuner.tune(guideId, retune)
    }

    Box(modifier.width(PREVIEW_WIDTH).height(PREVIEW_HEIGHT).background(SofaScrim)) {
        // One surface for the life of the preview; the card covers it from a reset until the next first frame.
        player.Video(Modifier.fillMaxSize(), VideoZoom.FIT) { PreviewCard(channel, failed) }
        // No overscan inset: the box sits inside the guide's margins already.
        if (showPreviewCaptions(captionsEnabled, channel != null, failed)) {
            Captions(player, captionStyle, VideoZoom.FIT, bottomFraction = 0f, Modifier.fillMaxSize())
        }
        if (failed) {
            PreviewCard(channel, failed = true)
        } else if (channel != null &&
            showing != channel.guideId
        ) {
            PreviewCard(channel, failed = false)
        }
        TuningBadge(rememberSettled(buffering && !failed))
    }
}

/**
 * Starts the shared [player] muted, and back from a paused full screen rejoins the live edge rather than play from
 * where it paused. Reports each first frame to [onEvent] as null and each player error as itself.
 */
@Composable
private fun PreviewPlayerEvents(player: LivePlayer, lifecycle: Lifecycle, onEvent: (PlayerError?) -> Unit) {
    val currentOnEvent by rememberUpdatedState(onEvent)
    LaunchedEffect(player) {
        player.volume = 0f
        if (!player.playWhenReady) {
            player.playWhenReady = true
            player.stop()
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) player.prepare()
        }
    }
    LaunchedEffect(player) {
        player.status.collect { if (it.state == PlaybackState.READY) currentOnEvent(null) }
    }
    LaunchedEffect(player) {
        player.errors.collect { currentOnEvent(it) }
    }
}

/**
 * Opens a channel's preview session on the shared [player], reporting a failure through [onFailedChange] and the
 * channel whose picture the player then holds through [onShowing].
 */
private class PreviewTuner(
    private val player: LivePlayer,
    private val sessions: PlaybackSessions,
    private val lifecycle: Lifecycle,
    private val onFailedChange: (Boolean) -> Unit,
    private val onShowing: (String) -> Unit,
) {
    /** Tunes [guideId], [retune] times reopened after a 410; a first tune waits out the debounce. */
    suspend fun tune(guideId: String, retune: Int) {
        val held = player.currentChannelId == guideId
        if (held && retune == 0 && player.status.value.state != PlaybackState.IDLE) {
            // Back from the full screen with its stream still playing: keep it, as a preview again.
            onShowing(guideId)
            // The lease keeps renewing either way; a watch-intent preview only holds its slot longer.
            runCatchingCancellable { sessions.setIntent(guideId, PlaybackIntent.PREVIEW) }
            return
        }
        if (!held && retune == 0) delay(PREVIEW_DEBOUNCE_MS)
        lifecycle.withStateAtLeast(Lifecycle.State.STARTED) {
            onFailedChange(false)
            // Stopped before the session changes hands: releasing the old session ends its stream, which would
            // otherwise surface as a player error. This also resets the renderers, as the full screen does.
            player.stop()
        }
        val stream = runCatchingCancellable { sessions.tune(guideId, PlaybackIntent.PREVIEW) }.getOrElse {
            onFailedChange(true)
            return
        }
        lifecycle.withStateAtLeast(Lifecycle.State.STARTED) {
            player.setStream(stream)
            player.prepare()
            onShowing(guideId)
        }
    }
}

/** The Tuning badge in the preview's corner while the stream buffers. */
@Composable
private fun BoxScope.TuningBadge(visible: Boolean) {
    AnimatedVisibility(
        visible,
        modifier = Modifier.align(Alignment.BottomStart).padding(8.dp),
        enter = PopIn,
        exit = PopOut,
    ) {
        Row(
            Modifier
                .background(SofaOverlayScrim, MaterialTheme.shapes.small)
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            SofaLoader(size = 16.dp)
            Text(
                stringResource(Res.string.tuning),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
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
