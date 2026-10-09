@file:OptIn(ExperimentalTvMaterial3Api::class)

package dev.jamesrich.sofaking.ui.guide

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.FilterChip
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import dev.jamesrich.sofaking.R
import dev.jamesrich.sofaking.data.Channel
import dev.jamesrich.sofaking.data.Lineup
import dev.jamesrich.sofaking.data.Programme
import dev.jamesrich.sofaking.ui.formatNumber
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

private val PIN_WIDTH = 220.dp
private val MINUTE_WIDTH = 6.dp
private val ROW_HEIGHT = 56.dp
private val HEADER_HEIGHT = 40.dp
private const val FOCUS_MARGIN_MINUTES = 30L
private const val NOW_REFRESH_MS = 60_000L

internal sealed interface GuideFilter {
    data object All : GuideFilter
    data object Favorites : GuideFilter
    data class Group(val name: String) : GuideFilter
}

@Composable
fun GuideScreen(
    lineup: Lineup,
    favorites: Set<String>,
    onPlay: (Channel) -> Unit,
    onToggleFavorite: (Channel) -> Unit,
    modifier: Modifier = Modifier,
) {
    // The window is fixed for the life of the screen so the grid does not jump every half hour.
    val windowStart = remember { GuideTimeline.windowStart(Instant.now()) }
    val windowEnd = remember(windowStart) { GuideTimeline.windowEnd(windowStart) }
    val now by produceState(Instant.now()) {
        while (true) {
            value = Instant.now()
            delay(NOW_REFRESH_MS)
        }
    }
    val nowMinute = GuideTimeline.offsetMinutes(windowStart, now).takeIf { now >= windowStart && now < windowEnd }
    val timeFormat = remember {
        DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault()).withZone(ZoneId.systemDefault())
    }

    val filters = remember(lineup) {
        listOf<GuideFilter>(GuideFilter.All, GuideFilter.Favorites) +
            lineup.channels.map { it.group }.distinct().map { GuideFilter.Group(it) }
    }
    var filter by remember { mutableStateOf<GuideFilter>(GuideFilter.All) }
    val activeFilter = filter.takeIf { it in filters } ?: GuideFilter.All
    val visible = remember(lineup, favorites, activeFilter) {
        when (val f = activeFilter) {
            GuideFilter.All -> lineup.channels
            GuideFilter.Favorites -> lineup.channels.filter { it.guideId in favorites }
            is GuideFilter.Group -> lineup.channels.filter { it.group == f.name }
        }
    }

    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val minutePx = with(density) { MINUTE_WIDTH.toPx() }
    val marginPx = minutePx * FOCUS_MARGIN_MINUTES
    var viewportPx by remember { mutableIntStateOf(0) }

    val selectedChip = remember { FocusRequester() }
    val initialFocus = remember { FocusRequester() }
    var initialFocusDone by remember { mutableStateOf(false) }
    LaunchedEffect(visible.isNotEmpty()) {
        if (!initialFocusDone && visible.isNotEmpty()) {
            // The first row may need a few frames to attach; requestFocus throws until it has.
            repeat(10) {
                withFrameNanos { }
                if (runCatching { initialFocus.requestFocus() }.isSuccess) {
                    initialFocusDone = true
                    return@LaunchedEffect
                }
            }
        }
    }

    // Brings a newly focused cell into view, leaving one half-hour of room on the left.
    val onCellFocused: (GuideCell) -> Unit = { cell ->
        val startPx = cell.startMinute * minutePx
        val viewStart = scrollState.value.toFloat()
        if (startPx < viewStart + marginPx || startPx > viewStart + viewportPx - marginPx) {
            scope.launch { scrollState.animateScrollTo((startPx - marginPx).roundToInt().coerceAtLeast(0)) }
        }
    }

    Column(modifier = modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 27.dp)) {
        LazyRow(
            modifier = Modifier.focusRestorer(selectedChip),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(filters, key = { it.toString() }) { option ->
                val selected = activeFilter == option
                FilterChip(
                    selected = selected,
                    onClick = { filter = option },
                    modifier = if (selected) Modifier.focusRequester(selectedChip) else Modifier,
                ) {
                    Text(option.label())
                }
            }
        }
        Spacer(Modifier.height(16.dp))

        Row(Modifier.fillMaxWidth().height(HEADER_HEIGHT)) {
            Box(Modifier.width(PIN_WIDTH))
            TimelineStrip(
                scrollState = scrollState,
                nowMinute = nowMinute,
                modifier = Modifier.weight(1f).onSizeChanged { viewportPx = it.width },
            ) {
                Row(Modifier.fillMaxHeight()) {
                    repeat((GuideTimeline.WINDOW_MINUTES / GuideTimeline.SLOT_MINUTES).toInt()) { slot ->
                        val label = timeFormat.format(windowStart.plusSeconds(slot * GuideTimeline.SLOT_MINUTES * 60))
                        Box(
                            Modifier.width(MINUTE_WIDTH * GuideTimeline.SLOT_MINUTES.toFloat()).fillMaxHeight().padding(start = 8.dp),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
                        }
                    }
                }
            }
        }

        LazyColumn(
            modifier = Modifier.weight(1f).focusRestorer(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            itemsIndexed(visible, key = { _, channel -> "${channel.guideId}#${channel.number}" }) { index, channel ->
                ChannelRow(
                    channel = channel,
                    programmes = lineup.guide[channel.guideId].orEmpty(),
                    isFavorite = channel.guideId in favorites,
                    windowStart = windowStart,
                    windowEnd = windowEnd,
                    now = now,
                    nowMinute = nowMinute,
                    timeFormat = timeFormat,
                    scrollState = scrollState,
                    initialFocus = initialFocus.takeIf { index == 0 },
                    onCellFocused = onCellFocused,
                    onPlay = onPlay,
                    onToggleFavorite = onToggleFavorite,
                )
            }
        }
    }
}

@Composable
private fun ChannelRow(
    channel: Channel,
    programmes: List<Programme>,
    isFavorite: Boolean,
    windowStart: Instant,
    windowEnd: Instant,
    now: Instant,
    nowMinute: Long?,
    timeFormat: DateTimeFormatter,
    scrollState: ScrollState,
    initialFocus: FocusRequester?,
    onCellFocused: (GuideCell) -> Unit,
    onPlay: (Channel) -> Unit,
    onToggleFavorite: (Channel) -> Unit,
) {
    val cells = remember(programmes, windowStart, windowEnd) {
        GuideTimeline.cellsFor(programmes, windowStart, windowEnd)
    }
    val initialIndex = if (initialFocus == null) -1 else {
        cells.indexOfFirst { cell -> cell.programme?.let { isAiring(it, now) } == true }.coerceAtLeast(0)
    }

    Row(Modifier.fillMaxWidth().height(ROW_HEIGHT)) {
        Row(
            Modifier.width(PIN_WIDTH).fillMaxHeight().padding(end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "${channel.number.formatNumber()}  ${channel.name}",
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (isFavorite) Text("★")
        }
        TimelineStrip(scrollState = scrollState, nowMinute = nowMinute, modifier = Modifier.weight(1f)) {
            Row(Modifier.fillMaxHeight()) {
                cells.forEachIndexed { index, cell ->
                    GuideCellItem(
                        channel = channel,
                        cell = cell,
                        airing = cell.programme?.let { isAiring(it, now) } == true,
                        timeFormat = timeFormat,
                        focusRequester = initialFocus.takeIf { index == initialIndex },
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
    focusRequester: FocusRequester?,
    onFocused: (GuideCell) -> Unit,
    onPlay: (Channel) -> Unit,
    onToggleFavorite: (Channel) -> Unit,
    modifier: Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val programme = cell.programme
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick = { onPlay(channel) },
        onLongClick = { onToggleFavorite(channel) },
        modifier = modifier
            .padding(horizontal = 1.dp, vertical = 2.dp)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { if (it.hasFocus) onFocused(cell) },
        interactionSource = interactionSource,
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (airing) colors.primaryContainer else colors.surfaceVariant,
            contentColor = colors.onSurface,
            focusedContainerColor = colors.primary,
            focusedContentColor = colors.onPrimary,
        ),
    ) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                programme?.title ?: stringResource(R.string.no_listing),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
            )
            if (focused && programme != null) {
                Text(
                    "${timeFormat.format(programme.start)} - ${timeFormat.format(programme.stop)}",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

/** One horizontally scrolled strip; the header and every row share [scrollState] so columns stay aligned. */
@Composable
private fun TimelineStrip(
    scrollState: ScrollState,
    nowMinute: Long?,
    modifier: Modifier,
    content: @Composable () -> Unit,
) {
    Box(modifier.clipToBounds().horizontalScroll(scrollState)) {
        content()
        if (nowMinute != null) {
            Box(
                Modifier
                    .offset(x = nowOffset(nowMinute))
                    .fillMaxHeight()
                    .width(2.dp)
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
    }
}

private fun nowOffset(minute: Long): Dp = MINUTE_WIDTH * minute.toFloat()

private fun isAiring(programme: Programme, now: Instant): Boolean =
    !programme.start.isAfter(now) && programme.stop.isAfter(now)

@Composable
private fun GuideFilter.label(): String = when (this) {
    GuideFilter.All -> stringResource(R.string.filter_all)
    GuideFilter.Favorites -> stringResource(R.string.filter_favorites)
    is GuideFilter.Group -> name
}
