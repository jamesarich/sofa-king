package dev.jamesrich.sofaking.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import dev.jamesrich.sofaking.data.Channel
import dev.jamesrich.sofaking.data.GuideStatus
import dev.jamesrich.sofaking.data.Programme
import dev.jamesrich.sofaking.resources.*
import org.jetbrains.compose.resources.stringResource

private val BadgeShape = RoundedCornerShape(4.dp)

/** The episode label and title, for example "S2 E5  The Pilot", or null when the guide has neither. */
internal fun Programme.episodeLine(): String? = listOfNotNull(episode, subTitle).joinToString("  ").ifEmpty { null }

/** What to show in place of a programme: why the channel has none, as far as the server says. */
@Composable
internal fun Channel.noListingText(): String = stringResource(
    when (guideStatus) {
        GuideStatus.UNASSIGNED -> Res.string.no_guide
        GuideStatus.STALE -> Res.string.guide_out_of_date
        GuideStatus.EMPTY, GuideStatus.AVAILABLE -> Res.string.no_listing
    },
)

/** The channel's logo, fitted to [modifier]'s size; the slot stays empty without one, so names line up. Decorative: the name is beside it. */
@Composable
internal fun ChannelLogo(channel: Channel, modifier: Modifier) {
    val logo = channel.logo
    if (logo == null) {
        Box(modifier)
    } else {
        RemoteImage(logo, ContentScale.Fit, modifier)
    }
}

/** Live, New and the rating, in that order; [fill] and [onFill] color the filled badges, [outline] the rating. */
@Composable
internal fun ProgrammeBadges(programme: Programme, live: Boolean, fill: Color, onFill: Color, outline: Color, modifier: Modifier = Modifier) {
    if (!live && !programme.isNew && programme.rating == null) return
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (live) FilledBadge(stringResource(Res.string.preview_live), fill, onFill)
        if (programme.isNew) FilledBadge(stringResource(Res.string.programme_new), fill, onFill)
        programme.rating?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = outline,
                maxLines = 1,
                modifier = Modifier.border(1.dp, outline, BadgeShape).padding(horizontal = 6.dp),
            )
        }
    }
}

@Composable
private fun FilledBadge(text: String, fill: Color, onFill: Color) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = onFill,
        maxLines = 1,
        modifier = Modifier.background(fill, BadgeShape).padding(horizontal = 6.dp, vertical = 1.dp),
    )
}
