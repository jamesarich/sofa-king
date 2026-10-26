package dev.jamesrich.sofaking.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.jamesrich.sofaking.resources.Res
import dev.jamesrich.sofaking.resources.caption_timing_value
import dev.jamesrich.sofaking.resources.caption_timing_value_later
import dev.jamesrich.sofaking.resources.caption_timing_value_none
import dev.jamesrich.sofaking.resources.captions_off
import dev.jamesrich.sofaking.resources.captions_on
import dev.jamesrich.sofaking.resources.captions_timing_adjust
import dev.jamesrich.sofaking.resources.captions_timing_adjust_description
import dev.jamesrich.sofaking.resources.captions_timing_as_broadcast
import dev.jamesrich.sofaking.resources.captions_timing_earlier
import dev.jamesrich.sofaking.resources.captions_timing_later
import dev.jamesrich.sofaking.resources.captions_unavailable
import dev.jamesrich.sofaking.resources.settings
import dev.jamesrich.sofaking.ui.theme.SofaOverlayScrim
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

internal fun CaptionToggleOutcome.labelRes(): StringResource = when (this) {
    CaptionToggleOutcome.ON -> Res.string.captions_on
    CaptionToggleOutcome.OFF -> Res.string.captions_off
    CaptionToggleOutcome.NO_CAPTIONS_ON_CHANNEL -> Res.string.captions_unavailable
}

/** "2.0 s early", "1.5 s late" or "as broadcast", for the CC toast and the settings row. */
@Composable
internal fun captionTimingShort(ms: Int): String = when (CaptionPolicy.timingDirection(ms)) {
    CaptionTiming.EARLIER -> stringResource(Res.string.captions_timing_earlier, CaptionPolicy.advanceLabel(ms))
    CaptionTiming.LATER -> stringResource(Res.string.captions_timing_later, CaptionPolicy.advanceLabel(ms))
    CaptionTiming.AS_BROADCAST -> stringResource(Res.string.captions_timing_as_broadcast)
}

/** The settings row's value: "2.0 s earlier", "1.5 s later" or "As broadcast". */
@Composable
internal fun captionTimingText(ms: Int): String = when (CaptionPolicy.timingDirection(ms)) {
    CaptionTiming.EARLIER -> stringResource(Res.string.caption_timing_value, CaptionPolicy.advanceLabel(ms))
    CaptionTiming.LATER -> stringResource(Res.string.caption_timing_value_later, CaptionPolicy.advanceLabel(ms))
    CaptionTiming.AS_BROADCAST -> stringResource(Res.string.caption_timing_value_none)
}

/**
 * The CC toast: what the toggle did and, while Left and Right adjust this channel's timing, the timing they are
 * at. [timingMs] is null where the engine applies no lead.
 */
@Composable
internal fun CaptionToastPill(outcome: CaptionToggleOutcome, timingMs: Int?) {
    Column(
        Modifier
            .background(SofaOverlayScrim, MaterialTheme.shapes.small)
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            stringResource(outcome.labelRes()),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (timingMs != null) {
            val timing = captionTimingShort(timingMs)
            val description = stringResource(Res.string.captions_timing_adjust_description, timing)
            Text(
                stringResource(Res.string.captions_timing_adjust, timing),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.semantics { contentDescription = description },
            )
        }
    }
}
