@file:OptIn(ExperimentalTvMaterial3Api::class)

package dev.jamesrich.sofaking.ui.guide

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import dev.jamesrich.sofaking.R
import dev.jamesrich.sofaking.data.Channel
import dev.jamesrich.sofaking.data.Programme
import dev.jamesrich.sofaking.ui.label
import dev.jamesrich.sofaking.ui.range
import dev.jamesrich.sofaking.ui.requestFocusWhenAttached
import java.time.Instant
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

@Composable
internal fun ChannelRow(
    channel: Channel,
    programmes: List<Programme>,
    isFavorite: Boolean,
    favoriteLabel: String,
    windowStart: Instant,
    windowEnd: Instant,
    now: Instant,
    nowMinute: Long?,
    minutePx: Float,
    timeFormat: DateTimeFormatter,
    scrollState: ScrollState,
    initialFocus: FocusRequester?,
    jump: GuideJump?,
    onJumpHandled: () -> Unit,
    onCellFocused: (Channel, GuideCell) -> Unit,
    onPlay: (Channel) -> Unit,
    onToggleFavorite: (Channel) -> Unit,
) {
    val cells = remember(programmes, windowStart, windowEnd) {
        GuideTimeline.cellsFor(programmes, windowStart, windowEnd)
    }
    val initialIndex = if (initialFocus == null) -1 else {
        cells.indexOfFirst { cell -> cell.programme?.let { isAiring(it, now) } == true }.coerceAtLeast(0)
    }
    val cellFocus = remember(cells) { List(cells.size) { FocusRequester() } }

    LaunchedEffect(jump) {
        if (jump == null) return@LaunchedEffect
        val index = GuideTimeline.cellIndexAt(cells, jump.minute)
        if (index >= 0) requestFocusWhenAttached(cellFocus[index])
        onJumpHandled()
    }

    Row(Modifier.fillMaxWidth().height(ROW_HEIGHT)) {
        Row(
            Modifier.width(PIN_WIDTH).fillMaxHeight().padding(end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                channel.label(),
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (isFavorite) {
                Text("★", modifier = Modifier.semantics { contentDescription = favoriteLabel })
            }
        }
        TimelineStrip(scrollState = scrollState, nowMinute = nowMinute, modifier = Modifier.weight(1f)) {
            Row(Modifier.fillMaxHeight()) {
                cells.forEachIndexed { index, cell ->
                    GuideCellItem(
                        channel = channel,
                        cell = cell,
                        airing = cell.programme?.let { isAiring(it, now) } == true,
                        timeFormat = timeFormat,
                        minutePx = minutePx,
                        scrollState = scrollState,
                        focusRequester = if (initialFocus != null && index == initialIndex) initialFocus else cellFocus[index],
                        onFocused = onCellFocused,
                        onPlay = onPlay,
                        onToggleFavorite = onToggleFavorite,
                        modifier = Modifier.width(MINUTE_WIDTH * cell.minutes.toFloat()).fillMaxHeight(),
                    )
                }
            }
        }
    }
}

@Composable
private fun GuideCellItem(
    channel: Channel,
    cell: GuideCell,
    airing: Boolean,
    timeFormat: DateTimeFormatter,
    minutePx: Float,
    scrollState: ScrollState,
    focusRequester: FocusRequester,
    onFocused: (Channel, GuideCell) -> Unit,
    onPlay: (Channel) -> Unit,
    onToggleFavorite: (Channel) -> Unit,
    modifier: Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val programme = cell.programme
    val colors = MaterialTheme.colorScheme
    val minTextPx = with(LocalDensity.current) { MIN_STICKY_TEXT.toPx() }
    val cellStartPx = cell.startMinute * minutePx
    val noListing = stringResource(R.string.no_listing)
    val toggleLabel = stringResource(R.string.guide_toggle_favorite)
    val description = when {
        programme == null -> stringResource(R.string.guide_cell_none, channel.name, noListing)
        airing -> stringResource(
            R.string.guide_cell_airing,
            channel.name,
            programme.title,
            timeFormat.format(programme.start),
            timeFormat.format(programme.stop),
        )
        else -> stringResource(
            R.string.guide_cell_later,
            channel.name,
            programme.title,
            timeFormat.format(programme.start),
            timeFormat.format(programme.stop),
        )
    }
    Surface(
        onClick = { onPlay(channel) },
        onLongClick = { onToggleFavorite(channel) },
        modifier = modifier
            .padding(horizontal = 1.dp, vertical = 2.dp)
            .focusRequester(focusRequester)
            .onFocusChanged { if (it.hasFocus) onFocused(channel, cell) }
            .semantics(mergeDescendants = true) {
                contentDescription = description
                customActions = listOf(
                    CustomAccessibilityAction(toggleLabel) {
                        onToggleFavorite(channel)
                        true
                    },
                )
            },
        interactionSource = interactionSource,
        // No focus scale: a cell that starts before the visible edge would grow past the pinned column and clip its text.
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (airing) colors.primaryContainer else colors.surfaceVariant,
            contentColor = colors.onSurface,
            focusedContainerColor = colors.primary,
            focusedContentColor = colors.onPrimary,
        ),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .stickyStart(scrollState, cellStartPx, minTextPx)
                .padding(horizontal = 8.dp, vertical = 2.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                programme?.title ?: noListing,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
            )
            if (focused && programme != null) {
                Text(
                    timeFormat.range(programme.start, programme.stop),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/**
 * Starts the content at the visible left edge when the cell begins before the scroll position, so a
 * focused title is not clipped. The content gives up the same width, so it never leaves the cell.
 */
private fun Modifier.stickyStart(scrollState: ScrollState, cellStartPx: Float, minVisiblePx: Float): Modifier =
    layout { measurable, constraints ->
        val cellWidth = constraints.maxWidth
        val shift = (scrollState.value - cellStartPx).roundToInt()
            .coerceIn(0, (cellWidth - minVisiblePx.roundToInt()).coerceAtLeast(0))
        val placeable = measurable.measure(constraints.copy(minWidth = 0, maxWidth = cellWidth - shift))
        layout(cellWidth, placeable.height) { placeable.place(shift, 0) }
    }

internal fun isAiring(programme: Programme, now: Instant): Boolean =
    !programme.start.isAfter(now) && programme.stop.isAfter(now)
