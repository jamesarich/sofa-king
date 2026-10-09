package dev.jamesrich.sofaking.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import dev.jamesrich.sofaking.R
import dev.jamesrich.sofaking.data.Channel
import dev.jamesrich.sofaking.data.Lineup
import dev.jamesrich.sofaking.ui.formatNumber
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal fun CaptionToggleOutcome.labelRes(): Int = when (this) {
    CaptionToggleOutcome.ON -> R.string.captions_on
    CaptionToggleOutcome.OFF -> R.string.captions_off
    CaptionToggleOutcome.NO_CAPTIONS_ON_CHANNEL -> R.string.captions_unavailable
}

@Composable
internal fun BoxScope.InfoOverlay(lineup: Lineup, channel: Channel, now: Instant, captionsOn: Boolean, captionAdvanceMs: Int) {
    val (current, next) = lineup.nowAndNext(channel, now)
    val timeFormat = remember { DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault()).withZone(ZoneId.systemDefault()) }
    Column(
        Modifier
            .align(Alignment.BottomStart)
            .padding(horizontal = 48.dp, vertical = 27.dp)
            .width(720.dp)
            .background(Color(0xCC000000))
            .padding(24.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${channel.number.formatNumber()}  ${channel.name}", style = MaterialTheme.typography.headlineSmall, color = Color.White)
            if (captionsOn) {
                Spacer(Modifier.width(16.dp))
                Text(
                    stringResource(R.string.captions_indicator_timing, CaptionPolicy.advanceLabel(captionAdvanceMs)),
                    style = MaterialTheme.typography.labelLarge,
                    color = Color.White,
                    modifier = Modifier.background(Color(0x66FFFFFF)).padding(horizontal = 8.dp),
                )
            }
        }
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
