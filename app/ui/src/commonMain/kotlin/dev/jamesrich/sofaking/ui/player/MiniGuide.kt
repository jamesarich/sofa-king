package dev.jamesrich.sofaking.ui.player

import dev.jamesrich.sofaking.ui.theme.SofaChip
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import dev.jamesrich.sofaking.resources.*
import dev.jamesrich.sofaking.data.Channel
import dev.jamesrich.sofaking.data.Lineup
import dev.jamesrich.sofaking.data.Program
import dev.jamesrich.sofaking.ui.ChannelLogo
import dev.jamesrich.sofaking.ui.formatNumber
import dev.jamesrich.sofaking.ui.noListingText
import dev.jamesrich.sofaking.ui.rememberTimeFormatter
import dev.jamesrich.sofaking.ui.requestFocusWhenAttached
import dev.jamesrich.sofaking.ui.theme.SofaOverlayScrimDense
import kotlin.time.Instant
import androidx.compose.foundation.layout.WindowInsetsSides
import dev.jamesrich.sofaking.ui.panelEdgeMargin
import dev.jamesrich.sofaking.ui.screenPadding
import dev.jamesrich.sofaking.ui.theme.tvFocus
import androidx.compose.material3.ripple
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.MutableInteractionSource

/**
 * Bottom channel list over the video: a chip row (Recent, Favorites, the guide filter the player was opened from,
 * All) above the rows. OK tunes and closes it through [onTune];
 * Back and the idle timer are handled by the owner. Focus starts on the current channel's row; Up from the first
 * row reaches the chips. [onChipsFocused] tells the owner when the chips have focus, so Left and Right move along
 * them instead of being swallowed.
 */
@Composable
internal fun BoxScope.MiniGuide(
    lineup: Lineup,
    scope: ZapScope?,
    favorites: Set<String>,
    recent: List<String>,
    currentGuideId: String?,
    favoritesOrder: List<String> = emptyList(),
    previousGuideId: String?,
    now: Instant,
    onTune: (Channel) -> Unit,
    onChipsFocused: (Boolean) -> Unit = {},
) {
    val scoped = scope != null
    val filters = remember(recent.isNotEmpty(), scoped) { miniGuideFilters(hasRecent = recent.isNotEmpty(), scoped = scoped) }
    var filter by remember(scoped) { mutableStateOf(miniGuideDefaultFilter(scoped)) }
    val rows = remember(filter, lineup.channels, scope, favorites, favoritesOrder, recent, previousGuideId) {
        miniGuideRows(filter, lineup.channels, scope?.guideIds, favorites, recent, previousGuideId, favoritesOrder)
    }
    val focusRow = miniGuideFocusRow(rows, currentGuideId)
    // The focused row starts at the top: the panel holds little more than one row on a 1080p TV, and a row
    // scrolled out of the list cannot take focus, which would leave the D-pad nothing to move.
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = focusRow.coerceAtLeast(0))
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { requestFocusWhenAttached(focusRequester) }
    val timeFormat = rememberTimeFormatter()

    Box(
        Modifier
            .align(Alignment.BottomStart)
            .fillMaxWidth()
            .fillMaxHeight(0.45f)
            .background(SofaOverlayScrimDense),
    ) {
        Column(Modifier.fillMaxWidth().fillMaxHeight().screenPadding(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom).padding(top = panelEdgeMargin())) {
            // One focus group, so the owner sees the top row as focused whichever chip holds it.
            Row(
                Modifier.fillMaxWidth().focusGroup().onFocusChanged { onChipsFocused(it.hasFocus) },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    stringResource(Res.string.mini_guide_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                LazyRow(
                    modifier = Modifier.weight(1f).focusGroup(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(filters.size, key = { filters[it].name }) { position ->
                        val option = filters[position]
                        SofaChip(
                            selected = filter == option,
                            onClick = { filter = option },
                            label = when (option) {
                                MiniGuideFilter.RECENT -> stringResource(Res.string.mini_guide_recent)
                                MiniGuideFilter.FAVORITES -> stringResource(Res.string.filter_favorites)
                                MiniGuideFilter.SCOPE -> scope?.label.orEmpty()
                                MiniGuideFilter.ALL -> stringResource(Res.string.filter_all)
                            },
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            if (rows.isEmpty()) {
                Text(
                    stringResource(Res.string.mini_guide_empty_favorites),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
            CompositionLocalProvider(LocalBringIntoViewSpec provides FocusedRowOnTop) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth().weight(1f).focusRestorer(),
            ) {
                items(rows.size, key = { rows[it].key }) { position ->
                    val row = rows[position]
                    val (program, next) = lineup.nowAndNext(row.channel, now)
                    MiniGuideRowItem(
                        row = row,
                        program = program,
                        nextLine = next?.let { stringResource(Res.string.mini_guide_next, it.title, timeFormat.format(it.start)) },
                        guideLoading = lineup.guideLoading,
                        now = now,
                        current = row.channel.guideId == currentGuideId,
                        onClick = { onTune(row.channel) },
                        modifier = if (position == focusRow) Modifier.focusRequester(focusRequester) else Modifier,
                    )
                }
            }
            }
        }
    }
}

/**
 * The focused row sits at the top of the list with the next one peeking below it. A TV's default pivots it a third
 * of the way down, which in a panel little taller than one row leaves a sliver of the row above.
 */
private val FocusedRowOnTop = object : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = offset
}

private val RowShape = RoundedCornerShape(8.dp)

@Composable
private fun MiniGuideRowItem(
    row: MiniGuideRow,
    program: Program?,
    nextLine: String?,
    guideLoading: Boolean,
    now: Instant,
    current: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    val channel = row.channel
    // The focused row is primary with on-primary text, the same focus the guide grid uses.
    val colors = MaterialTheme.colorScheme
    val primary = if (focused) colors.onPrimary else colors.onSurface
    val secondary = if (focused) colors.onPrimary else colors.onSurfaceVariant
    Row(
        modifier
            .fillMaxWidth()
            .tvFocus(RowShape, focusedScale = 1f)
            .background(if (focused) colors.primary else Color.Transparent, RowShape)
            // The row playing now reads as selected; OK on any row tunes it.
            .selectable(selected = current, interactionSource = interactions, indication = ripple(), role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            channel.number.formatNumber(),
            style = MaterialTheme.typography.titleMedium,
            color = primary,
            modifier = Modifier.width(96.dp),
        )
        ChannelLogo(channel, 64.dp, 40.dp, Modifier.padding(end = 16.dp))
        Column(Modifier.weight(1f)) {
            if (row.pinnedLast) {
                Text(
                    stringResource(Res.string.mini_guide_last_channel),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (focused) colors.onPrimary else colors.primary,
                )
            }
            Text(
                channel.name,
                style = MaterialTheme.typography.titleMedium,
                color = primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                program?.title ?: channel.noListingText(guideLoading),
                style = MaterialTheme.typography.bodyMedium,
                color = secondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (program != null) {
                Spacer(Modifier.height(4.dp))
                ProgressBar(programProgress(program.start, program.stop, now), color = primary)
            }
            if (nextLine != null) {
                Text(
                    nextLine,
                    style = MaterialTheme.typography.bodySmall,
                    color = secondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
