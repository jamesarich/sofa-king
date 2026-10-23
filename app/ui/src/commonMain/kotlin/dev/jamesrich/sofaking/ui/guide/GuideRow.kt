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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
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
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import dev.jamesrich.sofaking.ui.theme.tvFocus
import androidx.compose.material3.Text
import dev.jamesrich.sofaking.data.Channel
import dev.jamesrich.sofaking.data.GuideDensity
import dev.jamesrich.sofaking.data.Program
import dev.jamesrich.sofaking.ui.ChannelLogo
import dev.jamesrich.sofaking.ui.NoListing
import dev.jamesrich.sofaking.ui.noListing
import dev.jamesrich.sofaking.ui.FIRST_FOCUS_FRAMES
import dev.jamesrich.sofaking.ui.TimeFormatter
import dev.jamesrich.sofaking.ui.formatNumber
import dev.jamesrich.sofaking.ui.formatTemplate
import kotlin.math.roundToInt
import kotlin.time.Instant
import dev.jamesrich.sofaking.ui.LocalLayoutProfile

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
    val listingLoading: String,
    /** [Lineup.guideLoading]: a stretch with no program may yet get one. */
    val guideLoading: Boolean,
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
    /** A held OK, or the Info key, opens the program's details. */
    val onOpenDetails: (Channel, GuideCell) -> Unit,
    val detailsLabel: String,
    val laterLabel: String,
    /** A Later line's program was chosen: the grid jumps to its cell. */
    val onJumpTo: (Channel, Program) -> Unit,
) {
    /** The text of a stretch with no program, as [noListing] decides. */
    fun noListingFor(channel: Channel): String = when (channel.noListing(guideLoading)) {
        NoListing.NO_GUIDE -> noGuide
        NoListing.OUT_OF_DATE -> guideOutOfDate
        NoListing.LOADING -> listingLoading
        NoListing.NONE -> noListing
    }
}

@Composable
internal fun ChannelRow(
    channel: Channel,
    programs: List<Program>,
    isFavorite: Boolean,
    windowStart: Instant,
    windowEnd: Instant,
    nowMinute: Long?,
    env: GuideRowEnv,
    initialFocus: FocusRequester?,
    jump: GuideJump?,
    onJumpHandled: (landed: Boolean) -> Unit,
) {
    val cells = remember(programs, windowStart, windowEnd) {
        GuideTimeline.cellsFor(programs, windowStart, windowEnd)
    }
    val initialIndex = if (initialFocus == null) -1 else {
        cells.indexOfFirst { it.program != null && it.covers(nowMinute) }.coerceAtLeast(0)
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

    val profile = LocalLayoutProfile.current
    val density = LocalGuideDensity.current
    val compact = density == GuideDensity.COMPACT
    Row(Modifier.fillMaxWidth().height(rowHeight(density))) {
        Row(
            Modifier.width(pinWidth(profile)).fillMaxHeight().padding(end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ChannelLogo(channel, LOGO_WIDTH, LOGO_HEIGHT, Modifier.padding(end = 12.dp))
            // The number above the name, so a long name gets the column's width rather than sharing a line.
            Column(Modifier.weight(1f)) {
                Text(
                    channel.number.formatNumber(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                if (!profile.compactWidth) {
                    Text(
                        channel.name,
                        style = if (compact) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (isFavorite) {
                Icon(Icons.Filled.Star, env.favoriteLabel, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
            }
        }
        TimelineStrip(scrollState = env.scrollState, nowMinute = nowMinute, modifier = Modifier.weight(1f)) {
            Row(Modifier.fillMaxHeight()) {
                cells.forEachIndexed { index, cell ->
                    GuideCellItem(
                        channel = channel,
                        cell = cell,
                        airing = cell.program != null && cell.covers(nowMinute),
                        env = env,
                        compact = compact,
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
    compact: Boolean,
    focusRequester: FocusRequester,
    modifier: Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val program = cell.program
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
                contentDescription = cellDescription(env, channel, program, airing)
                // Favorite stays a direct action for screen readers; sighted use goes through the details.
                customActions = listOf(
                    CustomAccessibilityAction(env.toggleFavoriteLabel) {
                        env.onToggleFavorite(channel)
                        true
                    },
                    CustomAccessibilityAction(env.detailsLabel) {
                        env.onOpenDetails(channel, cell)
                        true
                    },
                )
            }
            // OK plays and a held OK opens the program's details, from the D-pad, a keyboard, touch or a mouse.
            .combinedClickable(
                interactionSource = interactions,
                indication = null,
                onLongClickLabel = env.detailsLabel,
                onLongClick = { env.onOpenDetails(channel, cell) },
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
                program?.title ?: env.noListingFor(channel),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = if (compact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
            )
            if (focused && program != null) {
                Text(
                    env.timeFormat.range(program.start, program.stop),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall.copy(
                        lineHeight = if (compact) 16.sp else 22.sp,
                        lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both),
                    ),
                )
            }
        }
    }
}

private fun cellDescription(env: GuideRowEnv, channel: Channel, program: Program?, airing: Boolean): String = when {
    program == null -> formatTemplate(env.cellNone, channel.name, env.noListingFor(channel))
    else -> formatTemplate(
        if (airing) env.cellAiring else env.cellLater,
        channel.name,
        program.title,
        env.timeFormat.format(program.start),
        env.timeFormat.format(program.stop),
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

internal fun isAiring(program: Program, now: Instant): Boolean =
    program.start <= now && program.stop > now

/**
 * The search's "Later today" line under a channel: one chip per upcoming program that matches, "8:00 PM Title",
 * which OK jumps the grid to. [firstFocus] lets the grid's Up and Down land on the first chip.
 */
@Composable
internal fun LaterRow(
    item: GuideListItem.Later,
    env: GuideRowEnv,
    firstFocus: FocusRequester,
    onFocused: () -> Unit,
) {
    val profile = LocalLayoutProfile.current
    Row(
        Modifier.fillMaxWidth().height(LATER_ROW_HEIGHT).onFocusChanged { if (it.hasFocus) onFocused() },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(pinWidth(profile)).padding(end = 12.dp), contentAlignment = Alignment.CenterEnd) {
            Text(
                env.laterLabel,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item.programs.forEachIndexed { index, program ->
                AssistChip(
                    onClick = { env.onJumpTo(item.channel, program) },
                    label = {
                        Text(
                            "${env.timeFormat.format(program.start)}  ${program.title}",
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    modifier = Modifier
                        .then(if (index == 0) Modifier.focusRequester(firstFocus) else Modifier)
                        .tvFocus(AssistChipDefaults.shape),
                )
            }
        }
    }
}
