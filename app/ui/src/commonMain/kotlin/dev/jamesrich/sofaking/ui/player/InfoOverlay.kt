package dev.jamesrich.sofaking.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.jamesrich.sofaking.data.Channel
import dev.jamesrich.sofaking.data.Lineup
import dev.jamesrich.sofaking.data.Program
import dev.jamesrich.sofaking.resources.Res
import dev.jamesrich.sofaking.resources.captions_indicator_timing
import dev.jamesrich.sofaking.resources.captions_indicator_timing_description
import dev.jamesrich.sofaking.resources.captions_indicator_timing_late
import dev.jamesrich.sofaking.resources.captions_indicator_timing_late_description
import dev.jamesrich.sofaking.resources.up_next
import dev.jamesrich.sofaking.ui.ChannelLogo
import dev.jamesrich.sofaking.ui.DETAIL_SEPARATOR
import dev.jamesrich.sofaking.ui.ProgramBadges
import dev.jamesrich.sofaking.ui.RiseIn
import dev.jamesrich.sofaking.ui.SinkOut
import dev.jamesrich.sofaking.ui.episodeLine
import dev.jamesrich.sofaking.ui.label
import dev.jamesrich.sofaking.ui.noListingText
import dev.jamesrich.sofaking.ui.rememberTimeFormatter
import dev.jamesrich.sofaking.ui.screenPadding
import dev.jamesrich.sofaking.ui.theme.SofaOverlayScrim
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Instant

/** The info bar; the caller places it at the bottom. [showKeyLegend] adds the key legend under the program. */
@Composable
internal fun InfoOverlay(
    lineup: Lineup,
    channel: Channel,
    now: Instant,
    captionsOn: Boolean,
    /** The channel's caption lead, or null where the engine applies none and the timing badge is left out. */
    captionAdvanceMs: Int?,
    modifier: Modifier = Modifier,
    scope: String? = null,
    showKeyLegend: Boolean = false,
) {
    val (current, next) = lineup.nowAndNext(channel, now)
    Column(
        modifier
            .widthIn(max = 720.dp)
            .fillMaxWidth()
            .background(SofaOverlayScrim, MaterialTheme.shapes.small)
            .padding(24.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        InfoHeader(channel, scope, captionsOn, captionAdvanceMs)
        if (current == null) {
            Text(
                channel.noListingText(lineup.guideLoading),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        } else {
            NowPlaying(current, now)
        }
        next?.let {
            Text(
                stringResource(Res.string.up_next, it.title),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (showKeyLegend) {
            Spacer(Modifier.height(8.dp))
            KeyLegend()
        }
    }
}

/**
 * The channel's logo and name, the scope the player was opened from, and the caption timing badge. They wrap under a
 * long name, and a name wider than the bar ends in an ellipsis.
 */
@Composable
private fun InfoHeader(channel: Channel, scope: String?, captionsOn: Boolean, captionAdvanceMs: Int?) {
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
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (scope != null) {
            Text(
                scope,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        if (captionsOn && captionAdvanceMs != null && captionAdvanceMs != 0) {
            CaptionTimingBadge(captionAdvanceMs)
        }
    }
}

/** How far the captions are moved, on a badge: earlier or later by the channel's lead. */
@Composable
private fun CaptionTimingBadge(captionAdvanceMs: Int) {
    val advance = CaptionPolicy.advanceLabel(captionAdvanceMs)
    val late = CaptionPolicy.timingDirection(captionAdvanceMs) == CaptionTiming.LATER
    val advanceDescription =
        stringResource(
            if (late) {
                Res.string.captions_indicator_timing_late_description
            } else {
                Res.string.captions_indicator_timing_description
            },
            advance,
        )
    Text(
        stringResource(
            if (late) Res.string.captions_indicator_timing_late else Res.string.captions_indicator_timing,
            advance,
        ),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onPrimary,
        maxLines = 1,
        modifier = Modifier
            .background(MaterialTheme.colorScheme.primary, MaterialTheme.shapes.small)
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .semantics { contentDescription = advanceDescription },
    )
}

/** What is on: title and badges, times and episode, description, the program's picture and its progress. */
@Composable
private fun NowPlaying(current: Program, now: Instant) {
    val timeFormat = rememberTimeFormatter()
    Column {
        Row {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        current.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    ProgramBadges(
                        program = current,
                        live = false,
                        fill = MaterialTheme.colorScheme.primary,
                        onFill = MaterialTheme.colorScheme.onPrimary,
                        outline = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
                Text(
                    listOfNotNull(
                        timeFormat.range(current.start, current.stop),
                        current.episodeLine(),
                    ).joinToString(DETAIL_SEPARATOR),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                current.description?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            current.icon?.let {
                AsyncImage(
                    model = it,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.padding(
                        start = 16.dp,
                    ).size(width = 160.dp, height = 90.dp).clip(MaterialTheme.shapes.small),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        ProgressBar(programProgress(current.start, current.stop, now))
    }
}

/**
 * The info bar's slot along the bottom of the player, sliding up when [visible]. The alignment sits on
 * AnimatedVisibility itself: an align() inside its content would only place the bar within the animation's own wrapper.
 */
@Composable
internal fun BoxScope.InfoBar(visible: Boolean, content: @Composable ColumnScope.() -> Unit) {
    AnimatedVisibility(visible, modifier = Modifier.align(Alignment.BottomStart), enter = RiseIn, exit = SinkOut) {
        Column(
            Modifier.fillMaxWidth().screenPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}
