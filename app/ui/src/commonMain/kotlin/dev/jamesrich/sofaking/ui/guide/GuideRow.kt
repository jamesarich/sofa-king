package dev.jamesrich.sofaking.ui.guide

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.jamesrich.sofaking.data.Channel
import dev.jamesrich.sofaking.data.GuideDensity
import dev.jamesrich.sofaking.data.Program
import dev.jamesrich.sofaking.ui.ChannelLogo
import dev.jamesrich.sofaking.ui.FIRST_FOCUS_FRAMES
import dev.jamesrich.sofaking.ui.LocalLayoutProfile
import dev.jamesrich.sofaking.ui.formatNumber
import dev.jamesrich.sofaking.ui.formatTemplate
import dev.jamesrich.sofaking.ui.theme.TvFocusControl
import kotlin.math.roundToInt
import kotlin.time.Instant

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
    onJumpFinish: (landed: Boolean) -> Unit,
) {
    val cells = remember(programs, windowStart, windowEnd) {
        GuideTimeline.cellsFor(programs, windowStart, windowEnd)
    }
    val initialIndex = if (initialFocus == null) {
        -1
    } else {
        cells.indexOfFirst { it.program != null && it.covers(nowMinute) }.coerceAtLeast(0)
    }
    val ownFocus = remember(cells) { List(cells.size) { FocusRequester() } }
    // The first row's airing cell answers to the guide's initial-focus requester; a jump must request that one,
    // since the cell's own requester is never attached.
    val cellFocus = remember(ownFocus, initialFocus, initialIndex) {
        ownFocus.mapIndexed { index, own -> if (initialFocus != null && index == initialIndex) initialFocus else own }
    }
    JumpToMinute(jump, cells, cellFocus, onJumpFinish)

    val density = LocalGuideDensity.current
    val compact = density == GuideDensity.COMPACT
    val height = rowHeight(density, LocalLayoutProfile.current, LocalDensity.current.fontScale)
    Row(Modifier.fillMaxWidth().height(height)) {
        ChannelPin(channel, isFavorite, compact, env)
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

/**
 * Moves focus to the cell under [jump]'s minute, then reports whether it landed. The guide can fill in while the
 * request waits, replacing the cells and their requesters, so the cell is looked up again on every frame.
 */
@Composable
private fun JumpToMinute(
    jump: GuideJump?,
    cells: List<GuideCell>,
    cellFocus: List<FocusRequester>,
    onJumpFinish: (landed: Boolean) -> Unit,
) {
    val currentCells by rememberUpdatedState(cells)
    val currentCellFocus by rememberUpdatedState(cellFocus)
    val currentOnJumpFinish by rememberUpdatedState(onJumpFinish)
    LaunchedEffect(jump) {
        if (jump == null) return@LaunchedEffect
        var landed = false
        for (frame in 0 until FIRST_FOCUS_FRAMES) {
            withFrameNanos { }
            val index = GuideTimeline.cellIndexAt(currentCells, jump.minute)
            if (index >= 0 && currentCellFocus[index].requestFocus(FocusDirection.Enter)) {
                landed = true
                break
            }
        }
        currentOnJumpFinish(landed)
    }
}

/** The pinned column: the channel's logo, number and name, and a star when it is a favorite. */
@Composable
private fun ChannelPin(channel: Channel, isFavorite: Boolean, compact: Boolean, env: GuideRowEnv) {
    val profile = LocalLayoutProfile.current
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
            Icon(
                Icons.Filled.Star,
                env.favoriteLabel,
                Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
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
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val program = cell.program
    val colors = MaterialTheme.colorScheme
    val cellStartPx = cell.startMinute * env.minutePx
    val cellWidthPx = cell.minutes * env.minutePx
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
                if (it.hasFocus) env.onCellFocus(channel, cell)
            }
            .cellActions(env, channel, cell, airing, interactions),
    ) {
        // Only a cell under the strip's left edge moves its text, so the others are not measured again on every
        // frame of a scroll.
        val stickyShift = remember(env.scrollState, cellStartPx, cellWidthPx) {
            derivedStateOf { (env.scrollState.value - cellStartPx).coerceIn(0f, cellWidthPx).roundToInt() }
        }
        Box(Modifier.fillMaxSize()) {
            CellText(
                program?.title ?: env.noListingFor(channel),
                program?.takeIf { focused }?.let { env.timeFormat.range(it.start, it.stop) },
                compact,
                Modifier.fillMaxSize().stickyStart(stickyShift, env.minStickyTextPx),
            )
            // Airing shows in more than the container's tint; a focused cell is solid primary already.
            if (airing && !focused) Box(Modifier.width(3.dp).fillMaxHeight().background(colors.primary))
        }
    }
}

/** What a cell says to a screen reader and does on OK, a held OK, a tap or a click. */
private fun Modifier.cellActions(
    env: GuideRowEnv,
    channel: Channel,
    cell: GuideCell,
    airing: Boolean,
    interactions: MutableInteractionSource,
): Modifier = semantics(mergeDescendants = true) {
    // Built only when the semantics tree is read, so a grid of hundreds of cells
    // formats nothing for sighted use.
    contentDescription = cellDescription(env, channel, cell.program, airing)
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
        role = Role.Button,
        onClickLabel = env.watchLabel,
        onLongClickLabel = env.detailsLabel,
        onLongClick = {
            env.haptics.longPress()
            env.onOpenDetails(channel, cell)
        },
        onClick = { env.onPlay(channel) },
    )

/** A cell's title, and under it the program's times ([times], null while the cell is not focused). */
@Composable
private fun CellText(title: String, times: String?, compact: Boolean, modifier: Modifier = Modifier) {
    Column(
        modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            title,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = if (compact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
        )
        if (times != null) {
            Text(
                times,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall.copy(
                    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both),
                ),
            )
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
 * Starts the content at the visible left edge when the cell begins before the scroll position ([shift], how far
 * the edge is into the cell), so a focused title is not clipped. The content gives up the same width, so it never
 * leaves the cell.
 */
private fun Modifier.stickyStart(shift: State<Int>, minVisiblePx: Float): Modifier = layout { measurable, constraints ->
    val cellWidth = constraints.maxWidth
    val shift = shift.value.coerceIn(0, (cellWidth - minVisiblePx.roundToInt()).coerceAtLeast(0))
    val placeable = measurable.measure(constraints.copy(minWidth = 0, maxWidth = cellWidth - shift))
    layout(cellWidth, placeable.height) { placeable.place(shift, 0) }
}

internal fun isAiring(program: Program, now: Instant): Boolean = program.start <= now && program.stop > now

/**
 * The search's "Later today" line under a channel: one chip per upcoming program that matches, "8:00 PM Title",
 * which OK jumps the grid to. [firstFocus] lets the grid's Up and Down land on the first chip.
 */
@Composable
internal fun LaterRow(item: GuideListItem.Later, env: GuideRowEnv, firstFocus: FocusRequester, onFocus: () -> Unit) {
    val profile = LocalLayoutProfile.current
    val height = scaledForText(LATER_ROW_HEIGHT, profile, LocalDensity.current.fontScale)
    Row(
        Modifier.fillMaxWidth().height(height).onFocusChanged { if (it.hasFocus) onFocus() },
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
                TvFocusControl(
                    AssistChipDefaults.shape,
                    if (index == 0) Modifier.focusRequester(firstFocus) else Modifier,
                ) { ringed ->
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
                        modifier = ringed,
                    )
                }
            }
        }
    }
}
