package dev.jamesrich.sofaking.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.ContentScale
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import dev.jamesrich.sofaking.resources.*
import dev.jamesrich.sofaking.data.Channel
import dev.jamesrich.sofaking.data.Lineup
import dev.jamesrich.sofaking.ui.ChannelLogo
import dev.jamesrich.sofaking.ui.ProgrammeBadges
import dev.jamesrich.sofaking.ui.RemoteImage
import dev.jamesrich.sofaking.ui.SofaLoader
import dev.jamesrich.sofaking.ui.episodeLine
import dev.jamesrich.sofaking.ui.label
import dev.jamesrich.sofaking.ui.noListingText
import dev.jamesrich.sofaking.ui.rememberTimeFormatter
import dev.jamesrich.sofaking.ui.theme.SofaAmber
import dev.jamesrich.sofaking.ui.theme.SofaDusk
import dev.jamesrich.sofaking.ui.theme.SofaMauve
import dev.jamesrich.sofaking.ui.theme.SofaOverlayScrim
import dev.jamesrich.sofaking.ui.theme.SofaOverlayTrack
import dev.jamesrich.sofaking.ui.theme.SofaPlum
import kotlin.time.Instant
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.semantics.stateDescription
import dev.jamesrich.sofaking.ui.screenPadding
import dev.jamesrich.sofaking.ui.theme.SofaButton

private val OverlayShape = RoundedCornerShape(8.dp)

internal fun CaptionToggleOutcome.labelRes(): StringResource = when (this) {
    CaptionToggleOutcome.ON -> Res.string.captions_on
    CaptionToggleOutcome.OFF -> Res.string.captions_off
    CaptionToggleOutcome.NO_CAPTIONS_ON_CHANNEL -> Res.string.captions_unavailable
}

/** A short status over the video, announced to screen readers; stack several in a Column. [busy] leads with the loader. */
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
        Text(text, style = style, color = SofaDusk)
    }
}

/** Typed channel digits, or the miss notice for them; the caller places it top right. */
@Composable
internal fun DigitsBadge(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.displayMedium,
        color = SofaDusk,
        modifier = Modifier
            .screenPadding()
            .background(SofaOverlayScrim, OverlayShape)
            .padding(horizontal = 16.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
    )
}

/** Plain-English headline for a playback error; [code] is the ExoPlayer error name or a session code. */
internal fun failureReasonRes(code: String): StringResource = when {
    "NETWORK_CONNECTION" in code -> Res.string.cant_reach_stream
    "BAD_HTTP_STATUS" in code -> Res.string.stream_refused
    "PARSING" in code || "UNSUPPORTED" in code -> Res.string.stream_unsupported
    else -> Res.string.cant_play
}

@Composable
internal fun BoxScope.FailureCard(code: String) {
    Column(
        Modifier
            .align(Alignment.Center)
            .background(SofaOverlayScrim, OverlayShape)
            .padding(24.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(stringResource(failureReasonRes(code)), style = MaterialTheme.typography.headlineSmall, color = SofaDusk)
        Text(stringResource(Res.string.cant_play_retry), style = MaterialTheme.typography.bodyMedium, color = SofaDusk)
        Text(code, style = MaterialTheme.typography.bodySmall, color = SofaMauve)
    }
}

/** Thin amber-on-dusk bar; [fraction] is 0..1. [color] is the filled part, for rows whose background is already amber. */
@Composable
internal fun ProgressBar(fraction: Float, color: Color = SofaAmber) {
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

@Composable
internal fun BoxScope.InfoOverlay(
    lineup: Lineup,
    channel: Channel,
    now: Instant,
    captionsOn: Boolean,
    captionAdvanceMs: Int,
    scope: String? = null,
    showKeyHint: Boolean = true,
) {
    val (current, next) = lineup.nowAndNext(channel, now)
    val timeFormat = rememberTimeFormatter()
    Column(
        Modifier
            .align(Alignment.BottomStart)
            .screenPadding()
            .widthIn(max = 720.dp)
            .fillMaxWidth()
            .background(SofaOverlayScrim, OverlayShape)
            .padding(24.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        // The scope and captions badge wrap under a long name, and a name wider than the bar ends in an ellipsis.
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (channel.logo != null) ChannelLogo(channel, 64.dp, 40.dp, Modifier.padding(end = 12.dp))
                Text(
                    channel.label(),
                    style = MaterialTheme.typography.headlineSmall,
                    color = SofaDusk,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (scope != null) Text(scope, style = MaterialTheme.typography.labelLarge, color = SofaMauve, maxLines = 1)
            if (captionsOn) {
                val advance = CaptionPolicy.advanceLabel(captionAdvanceMs)
                val advanceDescription = stringResource(Res.string.captions_indicator_timing_description, advance)
                Text(
                    stringResource(Res.string.captions_indicator_timing, advance),
                    style = MaterialTheme.typography.labelLarge,
                    color = SofaPlum,
                    maxLines = 1,
                    modifier = Modifier
                        .background(SofaAmber, OverlayShape)
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                        .semantics { contentDescription = advanceDescription },
                )
            }
        }
        if (current == null) {
            Text(channel.noListingText(lineup.guideLoading), style = MaterialTheme.typography.titleMedium, color = SofaDusk)
        } else {
            Row {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            current.title,
                            style = MaterialTheme.typography.titleMedium,
                            color = SofaDusk,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        ProgrammeBadges(
                            programme = current,
                            live = false,
                            fill = SofaAmber,
                            onFill = SofaPlum,
                            outline = SofaMauve,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                    Text(
                        listOfNotNull(timeFormat.range(current.start, current.stop), current.episodeLine()).joinToString("  ·  "),
                        style = MaterialTheme.typography.bodyMedium,
                        color = SofaMauve,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    current.description?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = SofaMauve, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                current.icon?.let {
                    RemoteImage(
                        url = it,
                        width = 160.dp,
                        height = 90.dp,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.padding(start = 16.dp).size(width = 160.dp, height = 90.dp).clip(OverlayShape),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            ProgressBar(programmeProgress(current.start, current.stop, now))
        }
        next?.let { Text(stringResource(Res.string.up_next, it.title), style = MaterialTheme.typography.bodyMedium, color = SofaMauve) }
        if (showKeyHint) {
            Spacer(Modifier.height(8.dp))
            Text(stringResource(Res.string.channel_list_hint), style = MaterialTheme.typography.bodySmall, color = SofaMauve)
        }
    }
}

/**
 * What the remote's keys do, as buttons for a finger or a mouse; shown with the info bar after a tap or click.
 * Each action is the same one its key takes. [failed] adds Retry, which OK does on the remote.
 */
@Composable
internal fun BoxScope.TouchControls(
    captionsOn: Boolean,
    paused: Boolean,
    failed: Boolean,
    onGuide: () -> Unit,
    onChannelUp: () -> Unit,
    onChannelDown: () -> Unit,
    onChannelList: () -> Unit,
    onCaptions: () -> Unit,
    onPlayPause: () -> Unit,
    onRetry: () -> Unit,
) {
    val captionsState = stringResource(if (captionsOn) Res.string.captions_on else Res.string.captions_off)
    FlowRow(
        Modifier.align(Alignment.TopEnd).screenPadding(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (failed) SofaButton(onClick = onRetry) { Text(stringResource(Res.string.retry)) }
        SofaButton(onClick = onGuide) { Text(stringResource(Res.string.player_control_guide)) }
        SofaButton(onClick = onChannelDown) { Text(stringResource(Res.string.player_control_channel_down)) }
        SofaButton(onClick = onChannelUp) { Text(stringResource(Res.string.player_control_channel_up)) }
        SofaButton(onClick = onChannelList) { Text(stringResource(Res.string.player_control_channels)) }
        SofaButton(onClick = onCaptions, modifier = Modifier.semantics { stateDescription = captionsState }) {
            Text(stringResource(Res.string.player_control_captions))
        }
        SofaButton(onClick = onPlayPause) {
            Text(stringResource(if (paused) Res.string.player_control_play else Res.string.player_control_pause))
        }
    }
}
