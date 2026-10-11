package dev.jamesrich.sofaking.ui.guide

import android.os.SystemClock
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
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.exoplayer.ExoPlayer
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import dev.jamesrich.sofaking.R
import dev.jamesrich.sofaking.data.CaptionStyle
import dev.jamesrich.sofaking.data.Channel
import dev.jamesrich.sofaking.data.Lineup
import dev.jamesrich.sofaking.data.PlaybackSessions
import dev.jamesrich.sofaking.ui.preview.PREVIEW_BAND_HEIGHT
import dev.jamesrich.sofaking.ui.preview.PREVIEW_ENABLED
import dev.jamesrich.sofaking.ui.preview.PreviewDetails
import dev.jamesrich.sofaking.ui.preview.PreviewDwell
import dev.jamesrich.sofaking.ui.preview.PreviewVideo
import dev.jamesrich.sofaking.ui.FIRST_FOCUS_FRAMES
import dev.jamesrich.sofaking.ui.rememberMinuteNow
import dev.jamesrich.sofaking.ui.rememberTimeFormatter
import dev.jamesrich.sofaking.ui.requestFocusWhenAttached
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
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

private val GuideFilterSaver = Saver<GuideFilter, String>(
    save = {
        when (it) {
            GuideFilter.All -> "all"
            GuideFilter.Favorites -> "favorites"
            is GuideFilter.Group -> "group:${it.name}"
        }
    },
    restore = {
        when {
            it == "favorites" -> GuideFilter.Favorites
            it.startsWith("group:") -> GuideFilter.Group(it.removePrefix("group:"))
            else -> GuideFilter.All
        }
    },
)

/** How often the initial focus is retried after the row refused it, before the header keeps focus. */
private const val INITIAL_FOCUS_ATTEMPTS = 3

/** Asks the row for [guideId] to focus the cell covering window [minute]. [nonce] keeps each request distinct. */
internal data class GuideJump(val guideId: String, val minute: Long, val nonce: Int)

/**
 * The row a fresh guide starts on: back from the player, or on a cold start, the channel last focused
 * ([lastFocused]) or watched ([lastWatched]); -1 when neither is in [visible], so the first row takes focus.
 */
internal fun initialGuideRow(visible: List<Channel>, lastFocused: String?, lastWatched: String?): Int {
    val wanted = lastFocused ?: lastWatched ?: return -1
    return visible.indexOfFirst { it.guideId == wanted }
}

/** What the preview's rest timers watch: the channel under focus, the last key press and whether the preview failed. */
private data class DwellInput(val channelId: String?, val nonce: Int, val failed: Boolean)

/** The cell most recently focused in the grid, shown by the preview pane. */
private data class FocusedCell(val channel: Channel, val cell: GuideCell)

/**
 * The guide grid. Filter, search, time cursor, scroll and the focused channel are saveable, so a trip to the
 * player and back lands where the user left; [initialChannelId] (the last channel watched) is where a fresh
 * guide starts, [startAtNow] puts the cursor at the current time instead of where it was, and [startSearch]
 * opens search by voice on arrival. [returnedFrom] is the channel the full screen was just left on, which the
 * preview's rest timer must not reopen until focus has moved.
 */
@Composable
fun GuideScreen(
    lineup: Lineup,
    favorites: Set<String>,
    initialChannelId: String?,
    startAtNow: Boolean,
    startSearch: Boolean,
    returnedFrom: String?,
    onPlay: (Channel) -> Unit,
    onToggleFavorite: (Channel) -> Unit,
    previewPlayer: ExoPlayer,
    sessions: PlaybackSessions,
    captionsEnabled: Boolean,
    captionStyle: CaptionStyle,
    modifier: Modifier = Modifier,
) {
    val now by rememberMinuteNow()
    // The window holds while now is inside it, so the grid does not jump every half hour; it rolls over
    // once the clock leaves it (a TV left on the guide for a day), along with the time cursor.
    var windowStartSecond by rememberSaveable { mutableLongStateOf(GuideTimeline.windowStart(now).epochSecond) }
    val windowStart = remember(windowStartSecond) { Instant.ofEpochSecond(windowStartSecond) }
    val windowEnd = remember(windowStart) { GuideTimeline.windowEnd(windowStart) }
    val nowMinute = GuideTimeline.offsetMinutes(windowStart, now).takeIf { now >= windowStart && now < windowEnd }
    val nowClamped = GuideTimeline.offsetMinutes(windowStart, now)
    var cursor by rememberSaveable { mutableLongStateOf(nowClamped) }
    LaunchedEffect(now) {
        val next = GuideTimeline.windowStartFor(windowStart, now)
        if (next != windowStart) {
            windowStartSecond = next.epochSecond
            cursor = GuideTimeline.offsetMinutes(next, now)
        }
    }
    val zone = remember { ZoneId.systemDefault() }
    val timeFormat = rememberTimeFormatter()
    val dayFormat = remember {
        DateTimeFormatter.ofPattern("EEE", Locale.getDefault()).withZone(zone)
    }
    val todayLabel = stringResource(R.string.guide_today)
    val rangeFormat = stringResource(R.string.guide_range)

    val filters = remember(lineup) {
        listOf<GuideFilter>(GuideFilter.All, GuideFilter.Favorites) +
            lineup.channels.map { it.group }.distinct().map { GuideFilter.Group(it) }
    }
    var filter by rememberSaveable(stateSaver = GuideFilterSaver) { mutableStateOf<GuideFilter>(GuideFilter.All) }
    val activeFilter = filter.takeIf { it in filters } ?: GuideFilter.All

    val searchState = rememberTextFieldState()
    var searchOpen by rememberSaveable { mutableStateOf(false) }
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
    val chipsState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val density = LocalDensity.current
    val minutePx = with(density) { MINUTE_WIDTH.toPx() }
    val minStickyTextPx = with(density) { MIN_STICKY_TEXT.toPx() }
    val marginPx = minutePx * FOCUS_MARGIN_MINUTES
    val viewport = remember { mutableIntStateOf(0) }
    // Read only by the preview band, so a focus move recomposes the band and not the whole grid.
    val focusedState = remember { mutableStateOf<FocusedCell?>(null) }
    var focused by focusedState
    var gridFocused by remember { mutableStateOf(false) }
    // The preview's rest timers: muted on tune, sound after five seconds, full screen after ten, restarted by
    // any key. Both run only while a cell holds focus with search closed, so typing never opens a channel.
    val dwell = remember { PreviewDwell(suppressedChannelId = returnedFrom) }
    var dwellNonce by remember { mutableIntStateOf(0) }
    var previewMuted by remember { mutableStateOf(true) }
    var previewFailed by remember { mutableStateOf(false) }
    var lastFocusedGuideId by rememberSaveable { mutableStateOf<String?>(null) }
    var jump by remember { mutableStateOf<GuideJump?>(null) }
    var jumpCount by remember { mutableIntStateOf(0) }
    var pendingRow by remember { mutableStateOf<Int?>(null) }

    val selectedChip = remember { FocusRequester() }
    val searchChip = remember { FocusRequester() }
    val searchField = remember { FocusRequester() }
    val initialFocus = remember { FocusRequester() }
    // True once a cell has taken focus; until then the header's buttons hold it, so the request is retried.
    // A guide opened for search gives focus to the search instead.
    var initialFocusDone by remember { mutableStateOf(startSearch) }
    var initialFocusAttempt by remember { mutableIntStateOf(0) }
    var returnToSearchChip by remember { mutableStateOf(false) }

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
        initialFocusDone = true
        lastFocusedGuideId = channel.guideId
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

    LaunchedEffect(visible.isNotEmpty(), initialFocusAttempt) {
        if (initialFocusDone || visible.isEmpty() || initialFocusAttempt >= INITIAL_FOCUS_ATTEMPTS) return@LaunchedEffect
        if (startAtNow) cursor = nowMinute ?: nowClamped
        val row = initialGuideRow(visible, lastFocusedGuideId, initialChannelId)
        if (row >= 0) {
            // Before the first measure the layout info is empty; a restored scroll position must not be thrown away.
            withFrameNanos { }
            if (listState.layoutInfo.visibleItemsInfo.none { it.index == row }) listState.scrollToItem(row)
            // The row reports through onJumpHandled whether its cell took focus.
            requestJump(visible[row].guideId, cursor)
        } else if (!requestFocusWhenAttached(initialFocus, FIRST_FOCUS_FRAMES)) {
            initialFocusAttempt++
        }
    }
    val onJumpHandled: (Boolean) -> Unit = { landed ->
        jump = null
        if (landed) initialFocusDone = true else if (!initialFocusDone) initialFocusAttempt++
    }

    // The guide fills in after the channels, so the focused cell's programmes change under it without a focus
    // event: the band is refreshed from the new row, and focus is put back on the cell at the cursor.
    LaunchedEffect(lineup.guide) {
        val current = focused ?: return@LaunchedEffect
        val cells = GuideTimeline.cellsFor(lineup.guide[current.channel.guideId].orEmpty(), windowStart, windowEnd)
        val cell = GuideTimeline.cellAfterUpdate(cells, current.cell, cursor) ?: return@LaunchedEffect
        if (cell != current.cell) focused = FocusedCell(current.channel, cell)
        // A row move already requested keeps its jump.
        if (gridFocused && pendingRow == null) requestJump(current.channel.guideId, cursor)
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
            // The focused row holds the second slot and the channels scroll under it, as a cable guide does;
            // the list clamps at either end, so the first and last rows still take focus in place.
            listState.scrollToItem((target - FOCUS_ROW_SLOT).coerceAtLeast(0))
            requestJump(visible[target].guideId, cursor)
        }
        true
    }

    // Now goes to the focused channel's row, which the chips keep, else the first row on screen.
    val onNow: () -> Unit = {
        val guideId = focused?.channel?.guideId ?: visible.getOrNull(listState.firstVisibleItemIndex)?.guideId
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
    // Voice search: the TV's recognizer takes the query and the first matching row takes focus; without a
    // recognizer, or when it is dismissed, the text field takes focus as the Search chip's does.
    var searchFocusRequest by remember { mutableIntStateOf(0) }
    var focusResults by remember { mutableStateOf(false) }
    val startVoiceSearch = rememberVoiceSearch { spoken ->
        searchOpen = true
        if (spoken != null) {
            searchState.setTextAndPlaceCursorAtEnd(spoken)
            focusResults = true
        }
        searchFocusRequest++
    }
    val openVoiceSearch: () -> Unit = {
        if (!startVoiceSearch()) {
            searchOpen = true
            searchFocusRequest++
        }
    }
    LaunchedEffect(Unit) { if (startSearch) openVoiceSearch() }
    // From the grid, Back climbs to the selected chip (scrolled into the row first, since only a composed chip
    // can take focus); from the chips or the header it leaves the activity to finish.
    val backAction = guideBackAction(searchOpen, gridFocused)
    BackHandler(enabled = backAction != GuideBack.EXIT) {
        when (backAction) {
            GuideBack.CLOSE_SEARCH -> closeSearch()
            GuideBack.FOCUS_CHIPS -> scope.launch {
                chipsState.scrollToItem(selectedChipIndex(filters, activeFilter))
                requestFocusWhenAttached(selectedChip)
            }
            GuideBack.EXIT -> Unit
        }
    }
    LaunchedEffect(searchOpen, searchFocusRequest) {
        if (!searchOpen) return@LaunchedEffect
        if (focusResults) {
            focusResults = false
            // No row matched: the field takes focus so the query can be changed.
            if (requestFocusWhenAttached(initialFocus, FIRST_FOCUS_FRAMES)) return@LaunchedEffect
        }
        requestFocusWhenAttached(searchField)
    }
    LaunchedEffect(returnToSearchChip) {
        if (returnToSearchChip) {
            requestFocusWhenAttached(searchChip)
            returnToSearchChip = false
        }
    }

    val resources = LocalResources.current
    val noListing = stringResource(R.string.no_listing)
    val favoriteLabel = stringResource(R.string.guide_favorite)
    val toggleFavoriteLabel = stringResource(R.string.guide_toggle_favorite)
    val env = remember(resources, timeFormat, noListing, favoriteLabel, toggleFavoriteLabel, minutePx, minStickyTextPx, scrollState, onCellFocused, onPlay, onToggleFavorite) {
        GuideRowEnv(
            resources = resources,
            timeFormat = timeFormat,
            noListing = noListing,
            favoriteLabel = favoriteLabel,
            toggleFavoriteLabel = toggleFavoriteLabel,
            minutePx = minutePx,
            minStickyTextPx = minStickyTextPx,
            scrollState = scrollState,
            onCellFocused = onCellFocused,
            onPlay = onPlay,
            onToggleFavorite = onToggleFavorite,
        )
    }

    val chips: @Composable (Modifier) -> Unit = { chipModifier ->
        FilterChipRow(
            filters = filters,
            activeFilter = activeFilter,
            searchOpen = searchOpen,
            selectedChip = selectedChip,
            searchChip = searchChip,
            listState = chipsState,
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

    // Observed from the snapshot, not read in composition, so a focus move still recomposes only the band.
    // Nothing opens while the app is stopped (Home, the speech recognizer, screen off); coming back starts
    // the rest over, muted, so a channel is never found open on return.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LifecycleEventEffect(Lifecycle.Event.ON_START) {
        dwell.onKey(SystemClock.uptimeMillis())
        dwellNonce++
    }
    LaunchedEffect(Unit) {
        snapshotFlow { DwellInput(focused?.channel?.guideId?.takeIf { gridFocused && !searchOpen }, dwellNonce, previewFailed) }
            .collectLatest { input ->
                if (dwell.channelId != input.channelId) dwell.onFocus(input.channelId, SystemClock.uptimeMillis())
                while (true) {
                    val now = SystemClock.uptimeMillis()
                    previewMuted = dwell.isMuted(now)
                    if (dwell.opensFullScreen(now)) {
                        // The same handoff as OK: the preview session is promoted, nothing reloads. A preview
                        // without a picture is not opened; it would only carry its failure to the full screen.
                        val channel = focused?.channel?.takeIf { it.guideId == dwell.channelId }
                        if (channel != null && !input.failed && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) onPlay(channel)
                        return@collectLatest
                    }
                    delay(dwell.nextTransitionMs(now) ?: return@collectLatest)
                }
            }
    }

    // Remote shortcuts answered wherever focus is; everything else goes on to the focused node. Every key
    // press restarts the preview's rest timers.
    val onGuideKey: (KeyEvent) -> Boolean = handler@{ event ->
        if (event.type != KeyEventType.KeyDown) return@handler false
        dwell.onKey(SystemClock.uptimeMillis())
        dwellNonce++
        when (event.key.guideShortcut()) {
            GuideShortcut.NOW -> onNow()
            GuideShortcut.SEARCH -> openVoiceSearch()
            null -> return@handler false
        }
        true
    }

    // The app header above already carries the top overscan margin; 16dp here is spacing, not overscan.
    Column(modifier = modifier.fillMaxSize().padding(start = 48.dp, end = 48.dp, top = 16.dp, bottom = 27.dp).onPreviewKeyEvent(onGuideKey)) {
        if (PREVIEW_ENABLED) {
            PreviewBand(
                focused = focusedState,
                now = now,
                searchOpen = searchOpen,
                chips = chips,
                searchBar = searchBar,
                player = previewPlayer,
                sessions = sessions,
                captionsEnabled = captionsEnabled,
                captionStyle = captionStyle,
                muted = previewMuted,
                onFailedChanged = { previewFailed = it },
            )
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
                modifier = Modifier.weight(1f).focusRestorer().onFocusChanged { gridFocused = it.hasFocus }.onPreviewKeyEvent(onGridKey),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                itemsIndexed(visible, key = { _, channel -> "${channel.guideId}#${channel.number}" }) { index, channel ->
                    ChannelRow(
                        channel = channel,
                        programmes = lineup.guide[channel.guideId].orEmpty(),
                        isFavorite = channel.guideId in favorites,
                        windowStart = windowStart,
                        windowEnd = windowEnd,
                        nowMinute = nowMinute,
                        env = env,
                        initialFocus = initialFocus.takeIf { index == 0 },
                        jump = jump?.takeIf { it.guideId == channel.guideId },
                        onJumpHandled = onJumpHandled,
                    )
                }
            }
        }
    }
}

/** Chips and search or details beside the preview in one band, so the grid keeps four rows on a 1080p TV. */
@Composable
private fun PreviewBand(
    focused: State<FocusedCell?>,
    now: Instant,
    searchOpen: Boolean,
    chips: @Composable (Modifier) -> Unit,
    searchBar: @Composable (Modifier) -> Unit,
    player: ExoPlayer,
    sessions: PlaybackSessions,
    captionsEnabled: Boolean,
    captionStyle: CaptionStyle,
    muted: Boolean,
    onFailedChanged: (Boolean) -> Unit,
) {
    val cell = focused.value
    Row(Modifier.fillMaxWidth().height(PREVIEW_BAND_HEIGHT), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        Column(Modifier.weight(1f).fillMaxHeight()) {
            chips(Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            if (searchOpen) {
                searchBar(Modifier.fillMaxWidth())
            } else {
                PreviewDetails(
                    channel = cell?.channel,
                    programme = cell?.cell?.programme,
                    live = cell?.cell?.programme?.let { isAiring(it, now) } == true,
                    now = now,
                    modifier = Modifier.fillMaxWidth().weight(1f).clipToBounds(),
                )
            }
        }
        PreviewVideo(
            player = player,
            sessions = sessions,
            channel = cell?.channel,
            captionsEnabled = captionsEnabled,
            captionStyle = captionStyle,
            muted = muted,
            onFailedChanged = onFailedChanged,
        )
    }
}
