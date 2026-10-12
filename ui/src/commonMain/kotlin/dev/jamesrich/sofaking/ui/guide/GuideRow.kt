@file:OptIn(ExperimentalTvMaterial3Api::class)

package dev.jamesrich.sofaking.ui.guide

import android.content.res.Resources
import androidx.compose.foundation.ScrollState
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
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import dev.jamesrich.sofaking.R
import dev.jamesrich.sofaking.data.Channel
import dev.jamesrich.sofaking.data.Programme
import dev.jamesrich.sofaking.ui.label
import dev.jamesrich.sofaking.ui.FIRST_FOCUS_FRAMES
import dev.jamesrich.sofaking.ui.range
import java.time.Instant
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/** Everything a row's cells need that is the same for every cell on screen; one instance per guide, so cells skip. */
@Immutable
internal class GuideRowEnv(
    val resources: Resources,
    val timeFormat: DateTimeFormatter,
    val noListing: String,
    val favoriteLabel: String,
    val toggleFavoriteLabel: String,
    val minutePx: Float,
    val minStickyTextPx: Float,
    val scrollState: ScrollState,
    val onCellFocused: (Channel, GuideCell) -> Unit,
    val onPlay: (Channel) -> Unit,
    val onToggleFavorite: (Channel) -> Unit,
)

@Composable
internal fun ChannelRow(
    channel: Channel,
    programmes: List<Programme>,
    isFavorite: Boolean,
    windowStart: Instant,
    windowEnd: Instant,
    nowMinute: Long?,
    env: GuideRowEnv,
    initialFocus: FocusRequester?,
    jump: GuideJump?,
    onJumpHandled: (landed: Boolean) -> Unit,
) {
    val cells = remember(programmes, windowStart, windowEnd) {
        GuideTimeline.cellsFor(programmes, windowStart, windowEnd)
    }
    val initialIndex = if (initialFocus == null) -1 else {
        cells.indexOfFirst { it.programme != null && it.covers(nowMinute) }.coerceAtLeast(0)
    }
    val cellFocus = remember(cells) { List(cells.size) { FocusRequester() } }
    val currentCells by rememberUpdatedState(cells)
    val currentCellFocus by rememberUpdatedState(cellFocus)

    LaunchedEffect(jump) {
        if (jump == null) return@LaunchedEffect
        // The guide can fill in while the request waits, replacing the cells and their requesters, so the
        // cell under the minute is looked up again on every frame.
        var landed = false
        for (frame in 0 until FIRST_FOCUS_FRAMES) {
            withFrameNanos { }
            val index = GuideTimeline.cellIndexAt(currentCells, jump.minute)
            if (index >= 0 && currentCellFocus[index].requestFocus(FocusDirection.Enter)) {
                landed = true
                break
            }
        }
        onJumpHandled(landed)
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
                Text(
                    "★",
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.semantics { contentDescription = env.favoriteLabel },
                )
            }
        }
        TimelineStrip(scrollState = env.scrollState, nowMinute = nowMinute, modifier = Modifier.weight(1f)) {
            Row(Modifier.fillMaxHeight()) {
                cells.forEachIndexed { index, cell ->
                    GuideCellItem(
                        channel = channel,
                        cell = cell,
                        airing = cell.programme != null && cell.covers(nowMinute),
                        env = env,
                        focusRequester = if (initialFocus != null && index == initialIndex) initialFocus else cellFocus[index],
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
    env: GuideRowEnv,
    focusRequester: FocusRequester,
    modifier: Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val programme = cell.programme
    val colors = MaterialTheme.colorScheme
    val cellStartPx = cell.startMinute * env.minutePx
    Surface(
        onClick = { env.onPlay(channel) },
        onLongClick = { env.onToggleFavorite(channel) },
        modifier = modifier
            .padding(horizontal = 1.dp, vertical = 2.dp)
            .focusRequester(focusRequester)
            .onFocusChanged {
                focused = it.isFocused
                if (it.hasFocus) env.onCellFocused(channel, cell)
            }
            .semantics(mergeDescendants = true) {
                // Built only when the semantics tree is read, so a grid of hundreds of cells formats nothing for sighted use.
                contentDescription = cellDescription(env, channel, programme, airing)
                customActions = listOf(
                    CustomAccessibilityAction(env.toggleFavoriteLabel) {
                        env.onToggleFavorite(channel)
                        true
                    },
                )
            },
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
                .stickyStart(env.scrollState, cellStartPx, env.minStickyTextPx)
                .padding(horizontal = 8.dp, vertical = 2.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                programme?.title ?: env.noListing,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
            )
            if (focused && programme != null) {
                Text(
                    env.timeFormat.range(programme.start, programme.stop),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall.copy(
                        lineHeight = 22.sp,
                        lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both),
                    ),
                )
            }
        }
    }
}

private fun cellDescription(env: GuideRowEnv, channel: Channel, programme: Programme?, airing: Boolean): String = when {
    programme == null -> env.resources.getString(R.string.guide_cell_none, channel.name, env.noListing)
    else -> env.resources.getString(
        if (airing) R.string.guide_cell_airing else R.string.guide_cell_later,
        channel.name,
        programme.title,
        env.timeFormat.format(programme.start),
        env.timeFormat.format(programme.stop),
    )
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
