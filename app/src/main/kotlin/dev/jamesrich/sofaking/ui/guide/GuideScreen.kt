package dev.jamesrich.sofaking.ui.guide

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import dev.jamesrich.sofaking.R
import dev.jamesrich.sofaking.data.Channel
import dev.jamesrich.sofaking.data.Lineup
import dev.jamesrich.sofaking.ui.preview.PREVIEW_BAND_HEIGHT
import dev.jamesrich.sofaking.ui.preview.PREVIEW_ENABLED
import dev.jamesrich.sofaking.ui.preview.PreviewDetails
import dev.jamesrich.sofaking.ui.preview.PreviewVideo
import dev.jamesrich.sofaking.ui.requestFocusWhenAttached
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

internal sealed interface GuideFilter {
    data object All : GuideFilter
    data object Favorites : GuideFilter
    data class Group(val name: String) : GuideFilter
}

/** Asks the row for [guideId] to focus the cell covering window [minute]. [nonce] keeps each request distinct. */
internal data class GuideJump(val guideId: String, val minute: Long, val nonce: Int)

/** The cell most recently focused in the grid, shown by the preview pane. */
private data class FocusedCell(val channel: Channel, val cell: GuideCell)

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
    val nowClamped = GuideTimeline.offsetMinutes(windowStart, now)
    val zone = remember { ZoneId.systemDefault() }
    val timeFormat = remember {
        DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault()).withZone(zone)
    }
    val dayFormat = remember {
        DateTimeFormatter.ofPattern("EEE", Locale.getDefault()).withZone(zone)
    }
    val todayLabel = stringResource(R.string.guide_today)
    val rangeFormat = stringResource(R.string.guide_range)
    val favoriteLabel = stringResource(R.string.guide_favorite)

    val filters = remember(lineup) {
        listOf<GuideFilter>(GuideFilter.All, GuideFilter.Favorites) +
            lineup.channels.map { it.group }.distinct().map { GuideFilter.Group(it) }
    }
    var filter by remember { mutableStateOf<GuideFilter>(GuideFilter.All) }
    val activeFilter = filter.takeIf { it in filters } ?: GuideFilter.All

    val searchState = rememberTextFieldState()
    var searchOpen by remember { mutableStateOf(false) }
    val query = if (searchOpen) searchState.text.toString() else ""
    val searchNow = now.takeIf { query.isNotBlank() }
    val visible = remember(lineup, favorites, activeFilter, query, searchNow) {
        val byFilter = when (val f = activeFilter) {
            GuideFilter.All -> lineup.channels
            GuideFilter.Favorites -> lineup.channels.filter { it.guideId in favorites }
            is GuideFilter.Group -> lineup.channels.filter { it.group == f.name }
        }
        if (searchNow == null) {
            byFilter
        } else {
            byFilter.filter { channel ->
                GuideSearch.matches(
                    query = query,
                    number = channel.number,
                    name = channel.name,
                    programmeTitle = lineup.nowAndNext(channel, searchNow).first?.title,
                )
            }
        }
    }

    val scrollState = rememberScrollState()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val density = LocalDensity.current
    val minutePx = with(density) { MINUTE_WIDTH.toPx() }
    val marginPx = minutePx * FOCUS_MARGIN_MINUTES
    val viewport = remember { mutableIntStateOf(0) }
    var focused by remember { mutableStateOf<FocusedCell?>(null) }
    // The time the user is looking at; Now moves it, focus keeps it while the cell still covers it.
    var cursor by remember { mutableLongStateOf(nowClamped) }
    var jump by remember { mutableStateOf<GuideJump?>(null) }
    var jumpCount by remember { mutableIntStateOf(0) }
    var pendingRow by remember { mutableStateOf<Int?>(null) }

    val selectedChip = remember { FocusRequester() }
    val searchChip = remember { FocusRequester() }
    val searchField = remember { FocusRequester() }
    val initialFocus = remember { FocusRequester() }
    var initialFocusDone by remember { mutableStateOf(false) }
    var returnToSearchChip by remember { mutableStateOf(false) }
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

    // Scrolls the timeline so [minute] is inside the visible strip, leaving a half hour of room on the left.
    // Kept after focus leaves so the preview holds the last programme while focus is on the chips.
    val revealMinute: (Long) -> Unit = { minute ->
        val px = minute * minutePx
        val viewStart = scrollState.value.toFloat()
        if (px < viewStart + marginPx || px > viewStart + viewport.intValue - marginPx) {
            scope.launch { scrollState.animateScrollTo((px - marginPx).roundToInt().coerceAtLeast(0)) }
        }
    }
    val onCellFocused: (Channel, GuideCell) -> Unit = { channel, cell ->
        focused = FocusedCell(channel, cell)
        pendingRow = null
        if (cursor !in cell.startMinute until cell.endMinute) cursor = cell.startMinute
        revealMinute(cursor)
    }
    val requestJump: (String, Long) -> Unit = { guideId, minute ->
        cursor = minute
        jumpCount += 1
        jump = GuideJump(guideId, minute, jumpCount)
        revealMinute(minute)
    }

    // Up and Down land on the cell under the time cursor. Default focus search treats any cell overlapping a
    // long focused cell (a full-row "No listing") as a candidate and can pick one hours ahead.
    val onGridKey: (KeyEvent) -> Boolean = handler@{ event ->
        val step = when (event.key) {
            Key.DirectionDown -> 1
            Key.DirectionUp -> -1
            else -> return@handler false
        }
        if (event.type != KeyEventType.KeyDown) return@handler true
        // Rapid presses arrive before focus lands, so count from the row already requested.
        val current = pendingRow
            ?: focused?.channel?.let { c -> visible.indexOfFirst { it.guideId == c.guideId && it.number == c.number } }
            ?: -1
        val target = current + step
        // Up from the first row leaves the grid for the chips.
        if (current < 0 || target !in visible.indices) {
            pendingRow = null
            return@handler false
        }
        pendingRow = target
        scope.launch {
            val shown = listState.layoutInfo.visibleItemsInfo
            val first = shown.firstOrNull()?.index ?: 0
            val last = shown.lastOrNull()?.index ?: 0
            when {
                target <= first -> listState.scrollToItem(target)
                target >= last -> listState.scrollToItem((target - (last - first) + 1).coerceAtLeast(0))
            }
            requestJump(visible[target].guideId, cursor)
        }
        true
    }

    val onNow: () -> Unit = {
        val guideId = visible.getOrNull(listState.firstVisibleItemIndex)?.guideId
        if (guideId != null) requestJump(guideId, nowMinute ?: 0L)
    }
    val closeSearch: () -> Unit = {
        searchOpen = false
        searchState.setTextAndPlaceCursorAtEnd("")
        returnToSearchChip = true
    }
    val onSearchToggle: () -> Unit = {
        if (searchOpen) closeSearch() else searchOpen = true
    }
    BackHandler(enabled = searchOpen) { closeSearch() }
    LaunchedEffect(searchOpen) {
        if (searchOpen) requestFocusWhenAttached(searchField)
    }
    LaunchedEffect(returnToSearchChip) {
        if (returnToSearchChip) {
            requestFocusWhenAttached(searchChip)
            returnToSearchChip = false
        }
    }

    val chips: @Composable (Modifier) -> Unit = { chipModifier ->
        FilterChipRow(
            filters = filters,
            activeFilter = activeFilter,
            searchOpen = searchOpen,
            selectedChip = selectedChip,
            searchChip = searchChip,
            onNow = onNow,
            onSearch = onSearchToggle,
            onSelect = { filter = it },
            modifier = chipModifier,
        )
    }
    val searchBar: @Composable (Modifier) -> Unit = { barModifier ->
        SearchField(
            state = searchState,
            focusRequester = searchField,
            hint = stringResource(R.string.guide_search_hint),
            onDone = { focusManager.moveFocus(FocusDirection.Down) },
            modifier = barModifier,
        )
    }

    Column(modifier = modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 27.dp)) {
        if (PREVIEW_ENABLED) {
            // Chips and search or details sit beside the preview in one band, so the grid keeps four rows on a 1080p TV.
            Row(Modifier.fillMaxWidth().height(PREVIEW_BAND_HEIGHT), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    chips(Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    if (searchOpen) {
                        searchBar(Modifier.fillMaxWidth())
                    } else {
                        PreviewDetails(
                            channel = focused?.channel,
                            programme = focused?.cell?.programme,
                            live = focused?.cell?.programme?.let { isAiring(it, now) } == true,
                            now = now,
                            modifier = Modifier.fillMaxWidth().weight(1f).clipToBounds(),
                        )
                    }
                }
                PreviewVideo(channel = focused?.channel)
            }
        } else {
            chips(Modifier.fillMaxWidth())
            if (searchOpen) {
                Spacer(Modifier.height(8.dp))
                searchBar(Modifier.fillMaxWidth())
            }
        }
        Spacer(Modifier.height(8.dp))

        Row(Modifier.fillMaxWidth().height(HEADER_HEIGHT)) {
            Box(Modifier.width(PIN_WIDTH).fillMaxHeight(), contentAlignment = Alignment.CenterStart) {
                VisibleRange(
                    scrollState = scrollState,
                    viewport = viewport,
                    minutePx = minutePx,
                    windowStart = windowStart,
                    now = now,
                    timeFormat = timeFormat,
                    dayFormat = dayFormat,
                    zone = zone,
                    todayLabel = todayLabel,
                    rangeFormat = rangeFormat,
                )
            }
            TimelineStrip(
                scrollState = scrollState,
                nowMinute = nowMinute,
                modifier = Modifier.weight(1f).onSizeChanged { viewport.intValue = it.width },
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

        if (visible.isEmpty() && query.isNotBlank()) {
            Text(
                stringResource(R.string.guide_search_none),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(top = 16.dp),
            )
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).focusRestorer().onPreviewKeyEvent(onGridKey),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                itemsIndexed(visible, key = { _, channel -> "${channel.guideId}#${channel.number}" }) { index, channel ->
                    ChannelRow(
                        channel = channel,
                        programmes = lineup.guide[channel.guideId].orEmpty(),
                        isFavorite = channel.guideId in favorites,
                        favoriteLabel = favoriteLabel,
                        windowStart = windowStart,
                        windowEnd = windowEnd,
                        now = now,
                        nowMinute = nowMinute,
                        minutePx = minutePx,
                        timeFormat = timeFormat,
                        scrollState = scrollState,
                        initialFocus = initialFocus.takeIf { index == 0 },
                        jump = jump?.takeIf { it.guideId == channel.guideId },
                        onJumpHandled = { jump = null },
                        onCellFocused = onCellFocused,
                        onPlay = onPlay,
                        onToggleFavorite = onToggleFavorite,
                    )
                }
            }
        }
    }
}
