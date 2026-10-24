package dev.jamesrich.sofaking.ui.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import dev.jamesrich.sofaking.data.ScrubTiles
import dev.jamesrich.sofaking.resources.Res
import dev.jamesrich.sofaking.resources.key_legend
import dev.jamesrich.sofaking.resources.no_channel_number
import dev.jamesrich.sofaking.resources.player_control_play
import dev.jamesrich.sofaking.resources.player_paused
import dev.jamesrich.sofaking.resources.player_paused_resumes_live
import dev.jamesrich.sofaking.resources.player_paused_resumes_live_after
import dev.jamesrich.sofaking.resources.vod_seek_badge
import dev.jamesrich.sofaking.resources.vod_seek_seconds
import dev.jamesrich.sofaking.ui.PopIn
import dev.jamesrich.sofaking.ui.PopOut
import dev.jamesrich.sofaking.ui.SofaLoader
import dev.jamesrich.sofaking.ui.formatPlayTime
import dev.jamesrich.sofaking.ui.label
import dev.jamesrich.sofaking.ui.screenPadding
import dev.jamesrich.sofaking.ui.theme.SofaIcons
import dev.jamesrich.sofaking.ui.theme.SofaOverlayScrim
import dev.jamesrich.sofaking.ui.theme.SofaOverlayTrack
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource
import kotlin.math.abs

internal val OverlayShape = RoundedCornerShape(8.dp)

/**
 * A short status over the video, announced to screen readers; stack several in a Column. [busy] leads with the loader.
 */
@Composable
internal fun StatusPill(text: String, style: TextStyle = MaterialTheme.typography.titleMedium, busy: Boolean = false) {
    Row(
        Modifier
            .background(SofaOverlayScrim, OverlayShape)
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (busy) SofaLoader(size = 22.dp)
        Text(text, style = style, color = MaterialTheme.colorScheme.onSurface)
    }
}

/**
 * The digits being typed, or the miss notice for them, top right; keyed on presence so each digit updates it in place.
 */
@Composable
internal fun BoxScope.ChannelNumberBadge(numbers: ChannelNumberInput) {
    val miss = numbers.miss
    val noticeText = if (miss != null) stringResource(Res.string.no_channel_number, miss) else null
    val popIn = PopIn
    val popOut = PopOut
    AnimatedContent(
        targetState = numbers.entry.digits.ifEmpty { noticeText.orEmpty() },
        modifier = Modifier.align(Alignment.TopEnd),
        transitionSpec = { popIn togetherWith popOut },
        contentKey = { it.isNotEmpty() },
        label = "digits",
    ) { text ->
        if (text.isNotEmpty()) DigitsBadge(text)
    }
}

/** Typed channel digits, or the miss notice for them; the caller places it top right. */
@Composable
internal fun DigitsBadge(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.displayMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .screenPadding()
            .background(SofaOverlayScrim, OverlayShape)
            .padding(horizontal = 16.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
    )
}

/** The pause pill: how far behind live the picture is, and that resuming after [LIVE_RESUME_AFTER_MS] rejoins live. */
@Composable
internal fun PausePill(status: PauseStatus?) {
    val text = when {
        status == null -> stringResource(Res.string.player_paused)

        status.resumesLive -> stringResource(Res.string.player_paused_resumes_live, status.behindSeconds)

        else -> stringResource(
            Res.string.player_paused_resumes_live_after,
            status.behindSeconds,
            (LIVE_RESUME_AFTER_MS / 1_000L).toInt(),
        )
    }
    StatusPill(text)
}

/**
 * Thin primary-colored bar; [fraction] is 0..1. [color] is the filled part, for rows whose
 * background is already primary.
 */
@Composable
internal fun ProgressBar(fraction: Float, color: Color = MaterialTheme.colorScheme.primary) {
    LinearProgressIndicator(
        progress = { fraction },
        modifier = Modifier.fillMaxWidth().height(4.dp),
        color = color,
        trackColor = SofaOverlayTrack,
        strokeCap = StrokeCap.Butt,
        gapSize = 0.dp,
        drawStopIndicator = {},
    )
}

/** The one-line key legend: shown on the first few info bars after install and on the stream card. */
@Composable
internal fun KeyLegend(modifier: Modifier = Modifier) {
    Text(
        stringResource(Res.string.key_legend),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

/**
 * The center of the picture confirming a play or pause the bar did not come up for: a disc with the icon, shown for
 * a moment on each [pulse]. Icon only, so it never covers subtitles at the top or bottom of the frame.
 */
@Composable
internal fun BoxScope.TransportDisc(hint: TransportHint?, pulse: Int) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(pulse) {
        if (pulse == 0 || hint == null) return@LaunchedEffect
        shown = true
        delay(TRANSPORT_DISC_MS)
        shown = false
    }
    AnimatedVisibility(shown, Modifier.align(Alignment.Center), enter = PopIn, exit = PopOut) {
        Box(
            Modifier.size(72.dp).background(SofaOverlayScrim, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (hint == TransportHint.PAUSE) SofaIcons.Pause else Icons.Filled.PlayArrow,
                contentDescription = stringResource(
                    if (hint == TransportHint.PAUSE) {
                        Res.string.player_paused
                    } else {
                        Res.string.player_control_play
                    },
                ),
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(40.dp),
            )
        }
    }
}

/**
 * A scrub the remote builds with the bar down: how far it moves, and the time it lands on, low in the middle of the
 * picture, so the bar does not cover it.
 */
@Composable
internal fun BoxScope.SeekBadge(targetMs: Long?, positionMs: () -> Long, scrub: ScrubTiles? = null) {
    AnimatedVisibility(
        targetMs != null,
        Modifier.align(Alignment.BottomCenter).screenPadding(),
        enter = PopIn,
        exit = PopOut,
    ) {
        val target = targetMs ?: return@AnimatedVisibility
        val delta = target - positionMs()
        val amount = if (abs(delta) < 60_000L) {
            stringResource(Res.string.vod_seek_seconds, abs(delta) / 1000)
        } else {
            formatPlayTime(abs(delta))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            scrub?.let { ScrubThumbnail(it, target) }
            StatusPill(
                stringResource(
                    Res.string.vod_seek_badge,
                    if (delta < 0) "−" else "+",
                    amount,
                    formatPlayTime(target),
                ),
            )
        }
    }
}

private const val TRANSPORT_DISC_MS = 650L
