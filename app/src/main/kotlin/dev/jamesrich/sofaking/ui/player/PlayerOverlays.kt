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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import dev.jamesrich.sofaking.R
import dev.jamesrich.sofaking.data.Channel
import dev.jamesrich.sofaking.data.Lineup
import dev.jamesrich.sofaking.ui.label
import dev.jamesrich.sofaking.ui.range
import dev.jamesrich.sofaking.ui.rememberTimeFormatter
import dev.jamesrich.sofaking.ui.theme.SofaAmber
import dev.jamesrich.sofaking.ui.theme.SofaDusk
import dev.jamesrich.sofaking.ui.theme.SofaMauve
import dev.jamesrich.sofaking.ui.theme.SofaOverlayScrim
import dev.jamesrich.sofaking.ui.theme.SofaOverlayTrack
import dev.jamesrich.sofaking.ui.theme.SofaPlum
import java.time.Instant

private val OverlayShape = RoundedCornerShape(8.dp)

internal fun CaptionToggleOutcome.labelRes(): Int = when (this) {
    CaptionToggleOutcome.ON -> R.string.captions_on
    CaptionToggleOutcome.OFF -> R.string.captions_off
    CaptionToggleOutcome.NO_CAPTIONS_ON_CHANNEL -> R.string.captions_unavailable
}

/** A short status over the video, announced to screen readers; stack several in a Column. */
@Composable
internal fun StatusPill(text: String, style: TextStyle = MaterialTheme.typography.titleMedium) {
    Text(
        text,
        style = style,
        color = SofaDusk,
        modifier = Modifier
            .background(SofaOverlayScrim, OverlayShape)
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
    )
}

/** Typed channel digits, or the miss notice for them, top right. */
@Composable
internal fun BoxScope.DigitsBadge(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.displayMedium,
        color = SofaDusk,
        modifier = Modifier
            .align(Alignment.TopEnd)
            .padding(horizontal = 48.dp, vertical = 27.dp)
            .background(SofaOverlayScrim, OverlayShape)
            .padding(horizontal = 16.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
    )
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
        Text(stringResource(R.string.cant_play), style = MaterialTheme.typography.headlineSmall, color = SofaDusk)
        Text(stringResource(R.string.cant_play_retry), style = MaterialTheme.typography.bodyMedium, color = SofaDusk)
        Text(code, style = MaterialTheme.typography.bodySmall, color = SofaMauve)
    }
}

/** Thin amber-on-dusk bar; [fraction] is 0..1. [color] is the filled part, for rows whose background is already amber. */
@Composable
internal fun ProgressBar(fraction: Float, color: Color = SofaAmber) {
    Box(Modifier.fillMaxWidth().height(4.dp).background(SofaOverlayTrack)) {
        Box(Modifier.fillMaxWidth(fraction).height(4.dp).background(color))
    }
}

@Composable
internal fun BoxScope.InfoOverlay(lineup: Lineup, channel: Channel, now: Instant, captionsOn: Boolean, captionAdvanceMs: Int) {
    val (current, next) = lineup.nowAndNext(channel, now)
    val timeFormat = rememberTimeFormatter()
    Column(
        Modifier
            .align(Alignment.BottomStart)
            .padding(horizontal = 48.dp, vertical = 27.dp)
            .width(720.dp)
            .background(SofaOverlayScrim, OverlayShape)
            .padding(24.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(channel.label(), style = MaterialTheme.typography.headlineSmall, color = SofaDusk)
            if (captionsOn) {
                Spacer(Modifier.width(16.dp))
                Text(
                    stringResource(R.string.captions_indicator_timing, CaptionPolicy.advanceLabel(captionAdvanceMs)),
                    style = MaterialTheme.typography.labelLarge,
                    color = SofaPlum,
                    modifier = Modifier.background(SofaAmber, OverlayShape).padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
        }
        if (current == null) {
            Text(stringResource(R.string.no_listing), style = MaterialTheme.typography.titleMedium, color = SofaDusk)
        } else {
            Text(current.title, style = MaterialTheme.typography.titleMedium, color = SofaDusk)
            Text(
                timeFormat.range(current.start, current.stop),
                style = MaterialTheme.typography.bodyMedium,
                color = SofaMauve,
            )
            Spacer(Modifier.height(8.dp))
            ProgressBar(programmeProgress(current.start, current.stop, now))
        }
        next?.let { Text(stringResource(R.string.up_next, it.title), style = MaterialTheme.typography.bodyMedium, color = SofaMauve) }
    }
}
