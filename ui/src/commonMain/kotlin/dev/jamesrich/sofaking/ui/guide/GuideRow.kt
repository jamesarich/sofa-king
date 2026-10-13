
package dev.jamesrich.sofaking.ui.guide

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import dev.jamesrich.sofaking.data.Channel
import dev.jamesrich.sofaking.data.GuideStatus
import dev.jamesrich.sofaking.data.Programme
import dev.jamesrich.sofaking.ui.ChannelLogo
import dev.jamesrich.sofaking.ui.FIRST_FOCUS_FRAMES
import dev.jamesrich.sofaking.ui.TimeFormatter
import dev.jamesrich.sofaking.ui.formatTemplate
import dev.jamesrich.sofaking.ui.label
import kotlin.math.roundToInt
import kotlin.time.Instant

/**
 * Everything a row's cells need that is the same for every cell on screen; one instance per guide, so cells skip.
 * The `cell*` strings are resource templates, filled only when the semantics tree is read.
 */
@Immutable
internal class GuideRowEnv(
    val cellAiring: String,
    val cellLater: String,
    val cellNone: String,
    val timeFormat: TimeFormatter,
    val noListing: String,
    val noGuide: String,
    val guideOutOfDate: String,
    val favoriteLabel: String,
    val toggleFavoriteLabel: String,
    val minutePx: Float,
    val minStickyTextPx: Float,
    val scrollState: ScrollState,
    val onCellFocused: (Channel, GuideCell) -> Unit,
    val onPlay: (Channel) -> Unit,
    val onToggleFavorite: (Channel) -> Unit,
) {
    /** The text of a stretch with no programme, which says why when the server does. */
    fun noListingFor(channel: Channel): String = when (channel.guideStatus) {
        GuideStatus.UNASSIGNED -> noGuide
        GuideStatus.STALE -> guideOutOfDate
        GuideStatus.EMPTY, GuideStatus.AVAILABLE -> noListing
    }
}

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
    val ownFocus = remember(cells) { List(cells.size) { FocusRequester() } }
    // The first row's airing cell answers to the guide's initial-focus requester; a jump must request that one,
    // since the cell's own requester is never attached.
    val cellFocus = remember(ownFocus, initialFocus, initialIndex) {
        ownFocus.mapIndexed { index, own -> if (initialFocus != null && index == initialIndex) initialFocus else own }
    }
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
            ChannelLogo(channel, Modifier.padding(end = 8.dp).size(width = LOGO_WIDTH, height = LOGO_HEIGHT))
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
                        focusRequester = cellFocus[index],
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
    val interactions = remember { MutableInteractionSource() }
    // No focus scale: a cell that starts before the visible edge would grow past the pinned column and clip its text.
    Surface(
        shape = MaterialTheme.shapes.small,
        color = when {
            focused -> colors.primary
            airing -> colors.primaryContainer
            else -> colors.surfaceVariant
        },
        contentColor = if (focused) colors.onPrimary else colors.onSurface,
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
            }
            // OK plays and a held OK toggles the favorite, from the D-pad, a keyboard, touch or a mouse.
            .combinedClickable(
                interactionSource = interactions,
                indication = null,
                onLongClick = { env.onToggleFavorite(channel) },
                onClick = { env.onPlay(channel) },
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
                programme?.title ?: env.noListingFor(channel),
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
    programme == null -> formatTemplate(env.cellNone, channel.name, env.noListingFor(channel))
    else -> formatTemplate(
        if (airing) env.cellAiring else env.cellLater,
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
    programme.start <= now && programme.stop > now
