package dev.jamesrich.sofaking.ui.guide

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.jamesrich.sofaking.data.Channel
import dev.jamesrich.sofaking.data.Programme
import dev.jamesrich.sofaking.resources.*
import dev.jamesrich.sofaking.ui.BackHandler
import dev.jamesrich.sofaking.ui.DETAIL_SEPARATOR
import dev.jamesrich.sofaking.ui.LocalLayoutProfile
import dev.jamesrich.sofaking.ui.ProgrammeBadges
import dev.jamesrich.sofaking.ui.episodeLine
import dev.jamesrich.sofaking.ui.label
import dev.jamesrich.sofaking.ui.noListingText
import dev.jamesrich.sofaking.ui.rememberTimeFormatter
import dev.jamesrich.sofaking.ui.requestFocusWhenAttached
import dev.jamesrich.sofaking.ui.theme.SofaButton
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Instant

/** What the details sheet shows: a channel and the programme focused in its row, which may be a gap. */
@Immutable
internal data class ProgrammeDetails(val channel: Channel, val programme: Programme?)

/** The side panel's width on a TV; the text column keeps readable lines at 10 feet. */
private val PANEL_WIDTH = 440.dp

/** The bottom sheet's text scrolls past this, so the buttons stay in reach on a phone. */
private val SHEET_TEXT_MAX_HEIGHT = 360.dp

/**
 * The programme's details with Watch and Favorite: a side panel over the guide on a TV, where a window of its own
 * would not take D-pad focus, and a Material bottom sheet elsewhere. Back and Close dismiss it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ProgrammeSheet(
    details: ProgrammeDetails,
    now: Instant,
    isFavorite: Boolean,
    guideLoading: Boolean,
    onWatch: (Channel) -> Unit,
    onToggleFavorite: (Channel) -> Unit,
    onDismiss: () -> Unit,
) {
    val watchFocus = remember { FocusRequester() }
    val tv = LocalLayoutProfile.current.tv
    val content: @Composable () -> Unit = {
        SheetContent(details, now, isFavorite, guideLoading, watchFocus, onWatch, onToggleFavorite, onDismiss, bounded = tv)
    }
    if (tv) {
        BackHandler(onBack = onDismiss)
        LaunchedEffect(details) { requestFocusWhenAttached(watchFocus) }
        Box(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.6f))
                // The scrim takes a tap or click, so touch on a TV closes it as Back does; no focus cue, it is not a control.
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
        ) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(PANEL_WIDTH).padding(vertical = 27.dp, horizontal = 24.dp)
                    // Focus stays in the panel: a D-pad move past its buttons must not land on the grid under it.
                    .focusProperties { onExit = { cancelFocusChange() } }
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {}),
            ) {
                Box(Modifier.padding(24.dp)) { content() }
            }
        }
    } else {
        ModalBottomSheet(onDismissRequest = onDismiss) {
            Box(Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp)) { content() }
        }
    }
}

@Composable
private fun SheetContent(
    details: ProgrammeDetails,
    now: Instant,
    isFavorite: Boolean,
    guideLoading: Boolean,
    watchFocus: FocusRequester,
    onWatch: (Channel) -> Unit,
    onToggleFavorite: (Channel) -> Unit,
    onDismiss: () -> Unit,
    /** True in the TV panel, whose height is fixed; the bottom sheet grows with its text instead. */
    bounded: Boolean,
) {
    val timeFormat = rememberTimeFormatter()
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
    val channel = details.channel
    val programme = details.programme
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val textModifier = if (bounded) Modifier.weight(1f, fill = false) else Modifier.heightIn(max = SHEET_TEXT_MAX_HEIGHT)
        Column(textModifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            programme?.icon?.let {
                AsyncImage(
                    model = it,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().height(180.dp).clip(MaterialTheme.shapes.medium),
                )
            }
            if (programme == null) {
                Text(channel.noListingText(guideLoading), style = MaterialTheme.typography.titleLarge)
            } else {
                Text(programme.title, style = MaterialTheme.typography.titleLarge, maxLines = 3, overflow = TextOverflow.Ellipsis)
                programme.episodeLine()?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = secondary) }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        listOfNotNull(timeFormat.range(programme.start, programme.stop), programmeTimingText(programme, now))
                            .joinToString(DETAIL_SEPARATOR),
                        style = MaterialTheme.typography.bodyMedium,
                        color = secondary,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    ProgrammeBadges(
                        programme = programme,
                        live = isAiring(programme, now),
                        fill = MaterialTheme.colorScheme.primary,
                        onFill = MaterialTheme.colorScheme.onPrimary,
                        outline = secondary,
                    )
                }
                programme.description?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            }
            Text(channel.label(), style = MaterialTheme.typography.bodyMedium, color = secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SofaButton(onClick = { onWatch(channel) }, modifier = Modifier.focusRequester(watchFocus)) {
                Text(stringResource(Res.string.guide_details_watch))
            }
            SofaButton(onClick = { onToggleFavorite(channel) }) {
                Text(stringResource(if (isFavorite) Res.string.guide_details_remove_favorite else Res.string.guide_details_add_favorite))
            }
            SofaButton(onClick = onDismiss) { Text(stringResource(Res.string.guide_details_close)) }
        }
    }
}

/** "42 min left", "Starts in 1 h 20 min" or "Ended", from [programmeTiming]. */
@Composable
private fun programmeTimingText(programme: Programme, now: Instant): String = when (val timing = programmeTiming(programme, now)) {
    is ProgrammeTiming.Airing -> stringResource(Res.string.guide_time_left, timing.left.hoursAndMinutes())
    is ProgrammeTiming.Upcoming -> stringResource(Res.string.guide_starts_in, timing.until.hoursAndMinutes())
    ProgrammeTiming.Ended -> stringResource(Res.string.guide_ended)
}
