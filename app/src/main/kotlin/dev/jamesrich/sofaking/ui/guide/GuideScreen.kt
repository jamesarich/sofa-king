@file:OptIn(ExperimentalTvMaterial3Api::class)

package dev.jamesrich.sofaking.ui.guide

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.KeyboardActionHandler
import androidx.compose.foundation.text.input.TextFieldDecorator
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.derivedStateOf
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
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
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
import dev.jamesrich.sofaking.ui.preview.PREVIEW_BAND_HEIGHT
import dev.jamesrich.sofaking.ui.preview.PREVIEW_ENABLED
import dev.jamesrich.sofaking.ui.preview.PreviewDetails
import dev.jamesrich.sofaking.ui.preview.PreviewVideo
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

private val PIN_WIDTH = 240.dp
private val MINUTE_WIDTH = 6.dp
// 1080p TV budget (540dp tall): app header ~90 + band 144 + 8 + header 32 leaves four 56dp rows above the overscan.
private val ROW_HEIGHT = 56.dp
private val HEADER_HEIGHT = 32.dp
private val MIN_STICKY_TEXT = 48.dp
private const val FOCUS_MARGIN_MINUTES = 30L
private const val NOW_REFRESH_MS = 60_000L

internal sealed interface GuideFilter {
    data object All : GuideFilter
    data object Favorites : GuideFilter
    data class Group(val name: String) : GuideFilter
}

/** Asks the row for [guideId] to focus the cell covering window [minute]. [nonce] keeps each request distinct. */
internal data class GuideJump(val guideId: String, val minute: Long, val nonce: Int)

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
        if (cursor !in cell.startMinute until cell.endMinute) cursor = cell.startMinute
        revealMinute(cursor)
    }
    val requestJump: (String, Long) -> Unit = { guideId, minute ->
        cursor = minute
        jumpCount += 1
        jump = GuideJump(guideId, minute, jumpCount)
        revealMinute(minute)
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
                modifier = Modifier.weight(1f).focusRestorer(),
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

@Composable
private fun ChannelRow(
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
                "${channel.number.formatNumber()}  ${channel.name}",
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
                    "${timeFormat.format(programme.start)} - ${timeFormat.format(programme.stop)}",
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

/** The cell most recently focused in the grid, shown by the preview pane. */
private data class FocusedCell(val channel: Channel, val cell: GuideCell)

/** Visible time range above the channel names, for example "Today 1:00 PM - 2:30 PM". Reads scroll in its own scope. */
@Composable
private fun VisibleRange(
    scrollState: ScrollState,
    viewport: MutableIntState,
    minutePx: Float,
    windowStart: Instant,
    now: Instant,
    timeFormat: DateTimeFormatter,
    dayFormat: DateTimeFormatter,
    zone: ZoneId,
    todayLabel: String,
    rangeFormat: String,
) {
    val label by remember(windowStart, now, todayLabel, rangeFormat) {
        derivedStateOf {
            if (viewport.intValue == 0) {
                ""
            } else {
                val (startMinute, endMinute) = GuideTimeline.visibleSpan(
                    scrollPx = scrollState.value.toFloat(),
                    viewportPx = viewport.intValue.toFloat(),
                    pxPerMinute = minutePx,
                )
                val start = windowStart.plusSeconds(startMinute * 60)
                val end = windowStart.plusSeconds(endMinute * 60)
                val sameDay = start.atZone(zone).toLocalDate() == now.atZone(zone).toLocalDate()
                val day = if (sameDay) todayLabel else dayFormat.format(start)
                String.format(Locale.getDefault(), rangeFormat, day, timeFormat.format(start), timeFormat.format(end))
            }
        }
    }
    Text(
        label,
        style = MaterialTheme.typography.bodySmall,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun FilterChipRow(
    filters: List<GuideFilter>,
    activeFilter: GuideFilter,
    searchOpen: Boolean,
    selectedChip: FocusRequester,
    searchChip: FocusRequester,
    onNow: () -> Unit,
    onSearch: () -> Unit,
    onSelect: (GuideFilter) -> Unit,
    modifier: Modifier,
) {
    LazyRow(
        modifier = modifier.focusRestorer(selectedChip),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "now") {
            FilterChip(selected = false, onClick = onNow) {
                Text(stringResource(R.string.guide_now))
            }
        }
        item(key = "search") {
            FilterChip(
                selected = searchOpen,
                onClick = onSearch,
                modifier = Modifier.focusRequester(searchChip),
            ) {
                Text(stringResource(R.string.guide_search))
            }
        }
        items(filters, key = { it.toString() }) { option ->
            val selected = activeFilter == option
            FilterChip(
                selected = selected,
                onClick = { onSelect(option) },
                modifier = if (selected) Modifier.focusRequester(selectedChip) else Modifier,
            ) {
                Text(option.label())
            }
        }
    }
}

@Composable
private fun SearchField(
    state: TextFieldState,
    focusRequester: FocusRequester,
    hint: String,
    onDone: () -> Unit,
    modifier: Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val borderColor = if (focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
    val borderWidth = if (focused) 4.dp else 1.dp
    Surface(
        modifier = modifier.onFocusChanged { focused = it.hasFocus },
        shape = RoundedCornerShape(8.dp),
        border = Border(border = BorderStroke(borderWidth, borderColor)),
    ) {
        Box(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            BasicTextField(
                state = state,
                lineLimits = TextFieldLineLimits.SingleLine,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                onKeyboardAction = KeyboardActionHandler { onDone() },
                decorator = TextFieldDecorator { inner ->
                    Box(Modifier.fillMaxWidth()) {
                        if (state.text.isEmpty()) {
                            Text(
                                hint,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        inner()
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
            )
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

/** Requests focus once the node is attached; a requester throws until its modifier has been placed. */
private suspend fun requestFocusWhenAttached(request: FocusRequester) {
    repeat(10) {
        withFrameNanos { }
        if (runCatching { request.requestFocus() }.isSuccess) return
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
