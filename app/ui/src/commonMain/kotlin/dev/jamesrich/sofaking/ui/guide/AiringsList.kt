package dev.jamesrich.sofaking.ui.guide

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.jamesrich.sofaking.data.Airing
import dev.jamesrich.sofaking.resources.*
import dev.jamesrich.sofaking.ui.ProgrammeBadges
import dev.jamesrich.sofaking.ui.TimeFormatter
import dev.jamesrich.sofaking.ui.label
import dev.jamesrich.sofaking.ui.rememberTimeFormatter
import dev.jamesrich.sofaking.ui.theme.SofaButton
import dev.jamesrich.sofaking.ui.theme.tvFocus
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Instant

/**
 * The other airings of the programme [title], soonest first: when, on which channel, and its badges. OK on one
 * airing now watches its channel and on a later one opens its details, through [onPick]; Back, or the Back button,
 * returns to the details through [onBack]. [firstFocus] goes to the first airing.
 */
@Composable
internal fun AiringsList(
    title: String,
    airings: List<Airing>,
    now: Instant,
    firstFocus: FocusRequester,
    onPick: (Airing) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val timeFormat = rememberTimeFormatter()
    val today = stringResource(Res.string.guide_today)
    val tomorrow = stringResource(Res.string.guide_tomorrow)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(Res.string.guide_airings_title), style = MaterialTheme.typography.titleLarge)
        Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            airings.forEachIndexed { index, airing ->
                AiringRow(
                    airing = airing,
                    now = now,
                    whenText = airingWhen(airing, now, timeFormat, today, tomorrow),
                    onClick = { onPick(airing) },
                    modifier = if (index == 0) Modifier.focusRequester(firstFocus) else Modifier,
                )
            }
        }
        SofaButton(onClick = onBack) { Text(stringResource(Res.string.guide_airings_back), maxLines = 1) }
    }
}

/** "Today 8:00 PM", "Tomorrow 9:30 AM" or "Sat 7:00 PM". */
private fun airingWhen(airing: Airing, now: Instant, timeFormat: TimeFormatter, today: String, tomorrow: String): String {
    val start = airing.programme.start
    val day = when (timeFormat.daysBetween(now, start)) {
        0 -> today
        1 -> tomorrow
        else -> timeFormat.day(start)
    }
    return "$day ${timeFormat.format(start)}"
}

@Composable
private fun AiringRow(airing: Airing, now: Instant, whenText: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    val shape = MaterialTheme.shapes.medium
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
    ListItem(
        modifier = modifier
            .fillMaxWidth()
            .tvFocus(shape, focusedScale = 1f)
            .clip(shape)
            .clickable(interactionSource = interactions, indication = ripple(), onClick = onClick),
        supportingContent = { Text(airing.channel.label(), color = secondary, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        trailingContent = {
            ProgrammeBadges(
                programme = airing.programme,
                live = isAiring(airing.programme, now),
                fill = MaterialTheme.colorScheme.primary,
                onFill = MaterialTheme.colorScheme.onPrimary,
                outline = secondary,
            )
        },
        colors = ListItemDefaults.colors(
            containerColor = if (focused) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface,
        ),
    ) { Text(whenText, style = MaterialTheme.typography.titleSmall, maxLines = 1) }
}
