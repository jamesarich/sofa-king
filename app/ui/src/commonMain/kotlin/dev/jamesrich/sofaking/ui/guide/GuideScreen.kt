package dev.jamesrich.sofaking.ui.guide

import dev.jamesrich.sofaking.ui.HeldOkGuard
import dev.jamesrich.sofaking.ui.appLifecycleOwner
import dev.jamesrich.sofaking.ui.BackHandler
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSerializable
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalFocusManager
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import dev.jamesrich.sofaking.resources.*
import dev.jamesrich.sofaking.data.AutoOpenDelay
import dev.jamesrich.sofaking.data.CaptionStyle
import dev.jamesrich.sofaking.data.Channel
import dev.jamesrich.sofaking.data.Lineup
import dev.jamesrich.sofaking.data.orderedFavorites
import dev.jamesrich.sofaking.data.ProgramKind
import dev.jamesrich.sofaking.data.Programme
import dev.jamesrich.sofaking.data.PlaybackSessions
import dev.jamesrich.sofaking.data.RecordingMark
import dev.jamesrich.sofaking.data.RecordingMarks
import dev.jamesrich.sofaking.ui.isOk
import dev.jamesrich.sofaking.ui.preview.PREVIEW_BAND_HEIGHT
import dev.jamesrich.sofaking.ui.preview.PREVIEW_ENABLED
import dev.jamesrich.sofaking.ui.preview.PreviewDetails
import dev.jamesrich.sofaking.ui.preview.PreviewDwell
import dev.jamesrich.sofaking.ui.player.ZapScope
import dev.jamesrich.sofaking.ui.player.ChannelNumberBadge
import dev.jamesrich.sofaking.ui.player.rememberChannelNumberInput
import dev.jamesrich.sofaking.ui.player.digitValue
import dev.jamesrich.sofaking.ui.preview.PreviewVideo
import dev.jamesrich.sofaking.ui.FIRST_FOCUS_FRAMES
import dev.jamesrich.sofaking.ui.rememberMinuteNow
import dev.jamesrich.sofaking.ui.rememberTimeFormatter
import dev.jamesrich.sofaking.ui.requestFocusWhenAttached
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlin.time.Instant
import dev.jamesrich.sofaking.ui.player.LivePlayer
import dev.jamesrich.sofaking.ui.rememberScreenReaderActive
import dev.jamesrich.sofaking.ui.rememberVoiceSearch
import dev.jamesrich.sofaking.ui.uptimeMs
import kotlin.time.Duration.Companion.minutes
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import dev.jamesrich.sofaking.ui.InputKind
import dev.jamesrich.sofaking.ui.LocalLastInput
import dev.jamesrich.sofaking.ui.LocalLayoutProfile
import dev.jamesrich.sofaking.ui.screenPadding

@Serializable
internal sealed interface GuideFilter {
    @Serializable
    data object All : GuideFilter
    @Serializable
    data object Favorites : GuideFilter
    /** The channels watched most recently, newest first. */
    @Serializable
    data object Recent : GuideFilter
    @Serializable
    data class Group(val name: String) : GuideFilter
    /** The channels showing a [kind] of program now. */
    @Serializable
    data class OnNow(val kind: ProgramKind) : GuideFilter
}

/** How often the kinds of program on now are read again. */
private const val ON_NOW_REFRESH_SECONDS = 300L

/** How often the initial focus is retried after the row refused it, before the header keeps focus. */
private const val INITIAL_FOCUS_ATTEMPTS = 3

/** Asks the row for [guideId] to focus the cell covering window [minute]. [nonce] keeps each request distinct. */
internal data class GuideJump(val guideId: String, val minute: Long, val nonce: Int)

/**
 * The row a fresh guide starts on: the channel the player was just left on ([returnedFrom]), else the one last
 * focused in the guide ([lastFocused]) or watched ([lastWatched]); -1 when none is in [visible], so the first
 * row takes focus.
 */
internal fun initialGuideRow(visible: List<Channel>, returnedFrom: String?, lastFocused: String?, lastWatched: String?): Int {
    val wanted = returnedFrom ?: lastFocused ?: lastWatched ?: return -1
    return visible.indexOfFirst { it.guideId == wanted }
}

/** True when the channel the player was left on is in the lineup but hidden by the filter or search, which then give way. */
internal fun watchedIsHidden(returnedFrom: String?, visible: List<Channel>, all: List<Channel>): Boolean =
    returnedFrom != null && visible.none { it.guideId == returnedFrom } && all.any { it.guideId == returnedFrom }

/** Back from the player the cursor never points into the past: the listings start at [nowMinute] at the earliest. */
internal fun cursorOnReturn(cursor: Long, nowMinute: Long): Long = maxOf(cursor, nowMinute)

/** What the preview's rest timers watch: the channel under focus, the last key press, whether the preview failed and whether sound is on. */
private data class DwellInput(val channelId: String?, val nonce: Int, val failed: Boolean, val sound: Boolean)

/** The cell most recently focused in the grid, shown by the preview pane. */
private data class FocusedCell(val channel: Channel, val cell: GuideCell)

/** The guide's test tag; Android exposes it as a resource id for the Baseline Profile generator. */
const val GUIDE_TEST_TAG = "guide"

/**
 * The guide grid. Filter, search, time cursor, scroll and the focused channel are saveable across a trip to the
 * player. [returnedFrom] is the channel the full screen was just left on: focus lands on it, a filter hiding it
 * gives way, and the preview's rest timer does not reopen it until focus has moved. [initialChannelId] (the
 * last channel watched) is where a fresh guide starts, [startAtNow] puts the cursor at the current time
 * instead of where it was, and [startSearch] opens search by voice on arrival. [startInHeader] leaves focus on the
 * header's tab, where Back from another tab put it: the guide takes its first focus, and the preview its channel,
 * only once focus comes down into it.
 */
@Composable
fun GuideScreen(
    lineup: Lineup,
    favorites: Set<String>,
    recentChannels: List<String>,
    initialChannelId: String?,
    /** The order Settings gave the favorites; the Favorites filter lists them so, the rest by number. */
    favoritesOrder: List<String> = emptyList(),
    /** The filter saved from the last visit (see [guideFilterFromKey]); a fresh guide opens on it. */
    initialFilterKey: String?,
    startAtNow: Boolean,
    startSearch: Boolean,
    returnedFrom: String?,
    /** Opens [channel] full screen; [unattended] when the preview opened it by itself, with no key pressed. */
    onPlay: (channel: Channel, scope: ZapScope?, unattended: Boolean) -> Unit,
    onToggleFavorite: (Channel) -> Unit,
    /** The viewer chose a filter chip; its key is saved for the next launch. */
    onFilterChanged: (String?) -> Unit,
    /** The grid moved to a day whose programmes may not be loaded; the window starts at the given instant. */
    onLoadGuideWindow: (Instant) -> Unit,
    previewPlayer: LivePlayer,
    sessions: PlaybackSessions,
    captionsEnabled: Boolean,
    captionStyle: CaptionStyle,
    autoOpenDelay: AutoOpenDelay,
    previewSound: Boolean,
    /** How programmes are set to record; null on a server that does not record, which hides the record buttons. */
    recordingMarks: RecordingMarks? = null,
    /** Records the programme or cancels its recording; false when the server did not take it. */
    onRecord: suspend (Programme) -> Boolean = { false },
    /** Records the programme's series or stops it; false when the server did not take it. */
    onRecordSeries: suspend (Programme) -> Boolean = { false },
    /** The header's open tab, which Back from the chips focuses; null when the header has no tabs. */
    headerFocus: FocusRequester? = null,
    startInHeader: Boolean = false,
    /** Whether the guide is the page on top; on its way out under the player it leaves the volume alone. */
    shown: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val now by rememberMinuteNow()
    // The live window holds while now is inside it, so the grid does not jump every half hour; it rolls over
    // once the clock leaves it (a TV left on the guide for a day), along with the time cursor. The grid shows
    // the live window or one a whole number of days after it (Fast Forward), which comes back to the live one when it rolls.
    var liveStartSecond by rememberSaveable { mutableLongStateOf(GuideTimeline.windowStart(now).epochSeconds) }
    var dayOffset by rememberSaveable { mutableIntStateOf(0) }
    val liveStart = remember(liveStartSecond) { Instant.fromEpochSeconds(liveStartSecond) }
    val windowStart = remember(liveStart, dayOffset) { liveStart + (dayOffset * GuideTimeline.WINDOW_MINUTES).minutes }
    val windowEnd = remember(windowStart) { GuideTimeline.windowEnd(windowStart) }
    val nowMinute = GuideTimeline.offsetMinutes(windowStart, now).takeIf { now >= windowStart && now < windowEnd }
    val nowClamped = GuideTimeline.offsetMinutes(windowStart, now)
    var cursor by rememberSaveable { mutableLongStateOf(nowClamped) }
    LaunchedEffect(now) {
        val next = GuideTimeline.windowStartFor(liveStart, now)
        if (next != liveStart) {
            liveStartSecond = next.epochSeconds
            dayOffset = 0
            cursor = GuideTimeline.offsetMinutes(next, now)
        }
    }
    val timeFormat = rememberTimeFormatter()
    val todayLabel = stringResource(Res.string.guide_today)
    val tomorrowLabel = stringResource(Res.string.guide_tomorrow)
    val rangeFormat = stringResource(Res.string.guide_range)

    // What is on now is read every few minutes, not every minute, so the chips and their lists hold still while browsing.
    val onNowSlot = now.epochSeconds / ON_NOW_REFRESH_SECONDS
    val kindsOnNow = remember(lineup.channels, lineup.guide, onNowSlot) { channelsOnNow(lineup, now) }
    val filters = remember(lineup.channels, kindsOnNow.keys) { guideFilters(lineup.channels, kindsOnNow.keys) }
    val filterCounts = remember(lineup.channels, favorites, recentChannels, kindsOnNow) { guideFilterCounts(lineup.channels, favorites, recentChannels, kindsOnNow) }
    var filter by rememberSerializable { mutableStateOf(guideFilterFromKey(initialFilterKey)) }
    val activeFilter = filter.takeIf { it in filters } ?: GuideFilter.All

    val searchState = rememberTextFieldState()
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    val query = if (searchOpen) searchState.text.toString() else ""
    val searchNow = now.takeIf { query.isNotBlank() }
    val byFilter = remember(lineup.channels, favorites, favoritesOrder, recentChannels, activeFilter, kindsOnNow) {
        when (val f = activeFilter) {
            GuideFilter.All -> lineup.channels
            GuideFilter.Favorites -> orderedFavorites(lineup.channels, favorites, favoritesOrder)
            GuideFilter.Recent -> recentChannels(lineup.channels, recentChannels)
            is GuideFilter.Group -> lineup.channels.filter { f.name in it.groups }
            is GuideFilter.OnNow -> kindsOnNow[f.kind].orEmpty()
        }
    }
    // A channel opened from a filter keeps the player's Up and Down inside it; search does not narrow it.
    val activeLabel = activeFilter.label()
    val zapScope = remember(activeFilter, activeLabel, byFilter) {
        if (activeFilter == GuideFilter.All) null else ZapScope(activeLabel, byFilter.mapTo(HashSet()) { it.guideId })
    }
    val currentZapScope by rememberUpdatedState(zapScope)
    val currentOnPlay by rememberUpdatedState(onPlay)
    val play: (Channel) -> Unit = remember { { channel -> currentOnPlay(channel, currentZapScope, false) } }
    // Search also matches programmes later in the window; those channels get a "Later" line under their row.
    val upcoming = remember(byFilter, lineup.guide, query, searchNow, windowEnd) {
        if (searchNow == null) {
            emptyMap()
        } else {
            buildMap {
                for (channel in byFilter) {
                    val later = GuideSearch.upcoming(query, lineup.guide[channel.guideId].orEmpty(), searchNow, windowEnd)
                    if (later.isNotEmpty()) put(channel.guideId, later)
                }
            }
        }
    }
    val visible = remember(byFilter, lineup, query, searchNow, upcoming) {
        if (searchNow == null) {
            byFilter
        } else {
            byFilter.filter { channel ->
                channel.guideId in upcoming || GuideSearch.matches(
                    query = query,
                    number = channel.number,
                    name = channel.name,
                    programmeTitle = lineup.nowAndNext(channel, searchNow).first?.title,
                )
            }
        }
    }
    val items = remember(visible, upcoming) { guideListItems(visible, upcoming) }
    // One requester per Later line, so a row step can land on its first chip.
    val laterFocus = remember(items) { items.filterIsInstance<GuideListItem.Later>().associate { it.key to FocusRequester() } }
    // The Later line holding focus, by key; a cell taking focus clears it.
    var laterFocused by remember { mutableStateOf<String?>(null) }

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
    var chipsFocused by remember { mutableStateOf(false) }
    // The preview's rest timers: muted on tune, sound after five seconds, full screen after the chosen delay,
    // restarted by any key. Both run only while a cell holds focus with search closed, so typing never opens a channel.
    // TalkBack reads the grid aloud as focus moves; a channel opening full screen would cut that off.
    val touchExploration = rememberScreenReaderActive()
    val fullScreenAfterMs = autoOpenDelay.afterMs.takeUnless { touchExploration }
    val currentPreviewSound by rememberUpdatedState(previewSound)
    val lastInput = LocalLastInput.current
    val dwell = remember(fullScreenAfterMs) {
        PreviewDwell(suppressedChannelId = returnedFrom, fullScreenAfterMs = fullScreenAfterMs, pointerDriven = lastInput.kind == InputKind.POINTER)
    }
    var dwellNonce by remember { mutableIntStateOf(0) }
    var previewMuted by remember { mutableStateOf(true) }
    var previewFailed by remember { mutableStateOf(false) }
    var lastFocusedGuideId by rememberSaveable { mutableStateOf<String?>(null) }
    var jump by remember { mutableStateOf<GuideJump?>(null) }
    var jumpCount by remember { mutableIntStateOf(0) }
    /** The list item a row move is heading for, until its cell or chip reports focus. */
    var pendingRow by remember { mutableStateOf<Int?>(null) }
    /** The programme whose details are open, if any. */
    var details by remember { mutableStateOf<ProgrammeDetails?>(null) }

    val selectedChip = remember { FocusRequester() }
    val searchChip = remember { FocusRequester() }
    val searchField = remember { FocusRequester() }
    val initialFocus = remember { FocusRequester() }
    // True once a cell has taken focus; until then the header's buttons hold it, so the request is retried.
    // A guide opened for search gives focus to the search instead.
    var initialFocusDone by remember { mutableStateOf(startSearch) }
    var initialFocusAttempt by remember { mutableIntStateOf(0) }
    // Until focus comes down from the header, where the guide was asked to leave it.
    var awaitingEntry by remember { mutableStateOf(startInHeader) }
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
        laterFocused = null
        if (cursor !in cell.startMinute until cell.endMinute) cursor = cell.startMinute
        revealMinute(cursor)
    }
    val requestJump: (String, Long) -> Unit = { guideId, minute ->
        cursor = minute
        jumpCount += 1
        jump = GuideJump(guideId, minute, jumpCount)
        revealMinute(minute)
    }

    LaunchedEffect(visible.isNotEmpty(), activeFilter, searchOpen, initialFocusAttempt, awaitingEntry) {
        if (awaitingEntry || initialFocusDone || initialFocusAttempt >= INITIAL_FOCUS_ATTEMPTS) return@LaunchedEffect
        if (watchedIsHidden(returnedFrom, visible, lineup.channels)) {
            // Reruns with the full lineup on screen.
            filter = GuideFilter.All
            searchOpen = false
            searchState.setTextAndPlaceCursorAtEnd("")
            return@LaunchedEffect
        }
        if (visible.isEmpty()) return@LaunchedEffect
        val nowAt = nowMinute ?: nowClamped
        if (startAtNow) cursor = nowAt else if (returnedFrom != null) cursor = cursorOnReturn(cursor, nowAt)
        val row = initialGuideRow(visible, returnedFrom, lastFocusedGuideId, initialChannelId)
        if (row >= 0) {
            // Before the first measure the layout info is empty; a restored scroll position must not be thrown away.
            withFrameNanos { }
            val item = rowIndexOf(items, visible[row])
            if (listState.layoutInfo.visibleItemsInfo.none { it.index == item }) listState.scrollToItem(item)
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
    // Only a composed row answers a jump; one whose row left the list (the lineup refreshing under the first
    // focus) would wait forever, so it counts as missed and the first focus scrolls to the row again.
    LaunchedEffect(jump) {
        val waiting = jump ?: return@LaunchedEffect
        repeat(FIRST_FOCUS_FRAMES * 2) { withFrameNanos { } }
        if (jump == waiting) onJumpHandled(false)
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

    // Scrolls the list so item [target] holds the second slot and focuses its cell under the time cursor, or
    // a Later line's first chip. The channels scroll under the focused row, as a cable guide does; the list
    // clamps at either end.
    val focusRow: (Int) -> Unit = { target ->
        pendingRow = target
        scope.launch {
            listState.scrollToItem((target - FOCUS_ROW_SLOT).coerceAtLeast(0))
            when (val item = items[target]) {
                is GuideListItem.Row -> requestJump(item.channel.guideId, cursor)
                is GuideListItem.Later -> {
                    laterFocus[item.key]?.let { requestFocusWhenAttached(it, FIRST_FOCUS_FRAMES) }
                    pendingRow = null
                }
            }
        }
    }

    // A filter change starts the list at the focused channel, or at the top when the filter hides it, rather
    // than at the old filter's scroll index. A number typed for a hidden channel switches to All and moves
    // to its row here once the list has changed. Skipped until the first focus has landed, which places
    // the list itself.
    var listedFilter by remember { mutableStateOf(activeFilter) }
    var numberJump by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(activeFilter, numberJump) {
        if (listedFilter == activeFilter && numberJump == null) return@LaunchedEffect
        listedFilter = activeFilter
        if (!initialFocusDone) return@LaunchedEffect
        val jumpRow = numberJump?.let { id -> visible.indexOfFirst { it.guideId == id } } ?: -1
        numberJump = null
        if (jumpRow >= 0) focusRow(rowIndexOf(items, visible[jumpRow])) else listState.scrollToItem(firstItemAfterFilter(items.map { it.channel }, focused?.channel?.guideId))
    }

    // Typed channel digits, as the player takes them. OK or the entry timeout commits them to the row with
    // that number, switching to All when the filter hides it; no channel with that number shows a notice.
    val numbers = rememberChannelNumberInput()
    SideEffect {
        numbers.onCommit = { pending ->
            if (!searchOpen) {
                when (val target = guideNumberTarget(pending, visible, lineup.channels)) {
                    is GuideNumberTarget.Row -> focusRow(rowIndexOf(items, visible[target.index]))
                    is GuideNumberTarget.Hidden -> {
                        filter = GuideFilter.All
                        numberJump = target.guideId
                    }
                    GuideNumberTarget.Missing -> numbers.missed(pending.digits)
                }
            }
        }
    }

    // Up and Down land on the cell under the time cursor. Default focus search treats any cell overlapping a
    // long focused cell (a full-row "No listing") as a candidate and can pick one hours ahead.
    val onGridKey: (KeyEvent) -> Boolean = handler@{ event ->
        val edgeCell = focused?.cell?.takeIf { laterFocused == null && pendingRow == null }
        if (edgeCell != null && event.key.stopsAtRowEdge(edgeCell, GuideTimeline.WINDOW_MINUTES)) return@handler true
        val (step, paged) = event.key.gridStep() ?: return@handler false
        if (event.type != KeyEventType.KeyDown) return@handler true
        // Rapid presses arrive before focus lands, so count from the row already requested.
        val current = pendingRow
            ?: laterFocused?.let { key -> items.indexOfFirst { it.key == key }.takeIf { it >= 0 } }
            ?: focused?.channel?.let { c -> rowIndexOf(items, c).takeIf { it >= 0 } }
            ?: -1
        if (current < 0 || items.isEmpty()) {
            pendingRow = null
            return@handler false
        }
        // A page stops at the grid's ends and never leaves it for the chips; a row step does.
        val target = (current + step).let { if (paged) it.coerceIn(0, items.lastIndex) else it }
        if (target !in items.indices) {
            pendingRow = null
            return@handler false
        }
        if (target == current) return@handler true
        focusRow(target)
        true
    }

    // Now goes to the focused channel's row, which the chips keep, else the first row on screen; a day ahead comes back to the live window.
    val onNow: () -> Unit = {
        val guideId = focused?.channel?.guideId ?: items.getOrNull(listState.firstVisibleItemIndex)?.channel?.guideId
        dayOffset = 0
        if (guideId != null) requestJump(guideId, GuideTimeline.offsetMinutes(liveStart, now))
    }
    // Fast Forward and Rewind move the grid a whole day, keeping the time of day and the focused channel; a day
    // beyond the live one is fetched on first visit and its cells fill in as it arrives.
    val onDayJump: (GuideShortcut) -> Unit = { shortcut ->
        val next = dayOffsetAfter(dayOffset, shortcut)
        if (next != dayOffset) {
            dayOffset = next
            val start = liveStart + (next * GuideTimeline.WINDOW_MINUTES).minutes
            if (next > 0) onLoadGuideWindow(start)
            focused?.channel?.guideId?.let { requestJump(it, cursor) }
        }
    }
    // The details of the focused programme, on the Info key or a held OK; Watch plays it and Back or Close returns focus to its cell.
    val heldOk = remember { HeldOkGuard() }
    val openDetails: (Channel, GuideCell) -> Unit = { channel, cell ->
        heldOk.arm()
        details = ProgrammeDetails(channel, cell.programme)
    }
    val closeDetails: () -> Unit = {
        details = null
        focused?.channel?.guideId?.let { requestJump(it, cursor) }
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
    val startVoiceSearch = rememberVoiceSearch(stringResource(Res.string.guide_search_voice_prompt)) { spoken ->
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
    // can take focus), from the chips to the header's open tab; a press with nowhere left to climb leaves the
    // activity to finish.
    val backAction = guideBackAction(searchOpen, gridFocused, chipsFocused, hasHeader = headerFocus != null)
    val climbBack: () -> Unit = {
        when (backAction) {
            GuideBack.CLOSE_SEARCH -> closeSearch()
            GuideBack.FOCUS_CHIPS -> scope.launch {
                chipsState.scrollToItem(selectedChipIndex(filters, activeFilter))
                requestFocusWhenAttached(selectedChip)
            }
            GuideBack.FOCUS_HEADER -> headerFocus?.requestFocus()
            GuideBack.EXIT -> Unit
        }
    }
    // A remote's Back key climbs in onGuideKey below; this takes the system Back gesture and Escape.
    BackHandler(enabled = backAction != GuideBack.EXIT, onBack = climbBack)
    // Whether the Back key-down that the guide took is still held, so only its own key-up climbs.
    var backHeld by remember { mutableStateOf(false) }
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

    val cellAiring = stringResource(Res.string.guide_cell_airing)
    val cellLater = stringResource(Res.string.guide_cell_later)
    val cellNone = stringResource(Res.string.guide_cell_none)
    val noListing = stringResource(Res.string.no_listing)
    val listingLoading = stringResource(Res.string.listing_loading)
    val guideLoading = lineup.guideLoading
    val noGuide = stringResource(Res.string.no_guide)
    val guideOutOfDate = stringResource(Res.string.guide_out_of_date)
    val favoriteLabel = stringResource(Res.string.guide_favorite)
    val toggleFavoriteLabel = stringResource(Res.string.guide_toggle_favorite)
    val detailsLabel = stringResource(Res.string.guide_details)
    val laterLabel = stringResource(if (dayOffset == 0) Res.string.guide_later else Res.string.guide_later_day)
    val currentOpenDetails by rememberUpdatedState(openDetails)
    val currentRequestJump by rememberUpdatedState(requestJump)
    val currentWindowStart by rememberUpdatedState(windowStart)
    val onOpenDetails: (Channel, GuideCell) -> Unit = remember { { channel, cell -> currentOpenDetails(channel, cell) } }
    val onJumpTo: (Channel, Programme) -> Unit = remember {
        { channel, programme -> currentRequestJump(channel.guideId, GuideTimeline.offsetMinutes(currentWindowStart, programme.start)) }
    }
    val env = remember(
        cellAiring, cellLater, cellNone, timeFormat, noListing, listingLoading, guideLoading, noGuide, guideOutOfDate, favoriteLabel,
        toggleFavoriteLabel, detailsLabel, laterLabel,
        minutePx, minStickyTextPx, scrollState, onCellFocused, play, onToggleFavorite, onOpenDetails, onJumpTo,
    ) {
        GuideRowEnv(
            cellAiring = cellAiring,
            cellLater = cellLater,
            cellNone = cellNone,
            timeFormat = timeFormat,
            noListing = noListing,
            listingLoading = listingLoading,
            guideLoading = guideLoading,
            noGuide = noGuide,
            guideOutOfDate = guideOutOfDate,
            favoriteLabel = favoriteLabel,
            toggleFavoriteLabel = toggleFavoriteLabel,
            minutePx = minutePx,
            minStickyTextPx = minStickyTextPx,
            scrollState = scrollState,
            onCellFocused = onCellFocused,
            onPlay = play,
            onToggleFavorite = onToggleFavorite,
            onOpenDetails = onOpenDetails,
            detailsLabel = detailsLabel,
            laterLabel = laterLabel,
            onJumpTo = onJumpTo,
        )
    }

    val chips: @Composable (Modifier) -> Unit = { chipModifier ->
        FilterChipRow(
            filters = filters,
            counts = filterCounts,
            activeFilter = activeFilter,
            searchOpen = searchOpen,
            selectedChip = selectedChip,
            searchChip = searchChip,
            listState = chipsState,
            onNow = onNow,
            onSearch = onSearchToggle,
            onSelect = {
                filter = it
                onFilterChanged(it.key())
            },
            modifier = chipModifier.onFocusChanged { chipsFocused = it.hasFocus },
        )
    }
    val searchBar: @Composable (Modifier) -> Unit = { barModifier ->
        SearchField(
            state = searchState,
            focusRequester = searchField,
            hint = stringResource(Res.string.guide_search_hint),
            onDone = { focusManager.moveFocus(FocusDirection.Down) },
            modifier = barModifier,
        )
    }

    // Observed from the snapshot, not read in composition, so a focus move still recomposes only the band.
    // Nothing opens while the app is stopped (Home, the speech recognizer, screen off); coming back starts
    // the rest over, muted, so a channel is never found open on return.
    val lifecycle = appLifecycleOwner.lifecycle
    LifecycleEventEffect(Lifecycle.Event.ON_START, appLifecycleOwner) {
        dwell.restart(uptimeMs())
        dwellNonce++
    }
    LaunchedEffect(dwell) {
        snapshotFlow {
            DwellInput(focused?.channel?.guideId?.takeIf { gridFocused && !searchOpen }, dwellNonce, previewFailed, currentPreviewSound)
        }
            .collectLatest { input ->
                if (dwell.channelId != input.channelId) dwell.onFocus(input.channelId, uptimeMs())
                while (true) {
                    val now = uptimeMs()
                    previewMuted = !input.sound || dwell.isMuted(now)
                    if (dwell.opensFullScreen(now)) {
                        // The same handoff as OK: the preview session is promoted, nothing reloads. A preview
                        // without a picture is not opened; it would only carry its failure to the full screen.
                        val channel = focused?.channel?.takeIf { it.guideId == dwell.channelId }
                        if (channel != null && !input.failed && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) currentOnPlay(channel, currentZapScope, true)
                        return@collectLatest
                    }
                    delay(dwell.nextTransitionMs(now) ?: return@collectLatest)
                }
            }
    }

    // Remote shortcuts answered wherever focus is; everything else goes on to the focused node. Every key
    // press restarts the preview's rest timers.
    val onGuideKey: (KeyEvent) -> Boolean = handler@{ event ->
        // Back climbs here rather than in BackHandler: Compose turns an unhandled Back key-down into a focus exit,
        // and where focus sits on a node with a focusable parent that exit succeeds and eats the press. The open
        // details take their own Back.
        if (event.key == Key.Back && details == null) {
            if (event.type == KeyEventType.KeyDown && backAction != GuideBack.EXIT) {
                backHeld = true
                return@handler true
            }
            if (event.type == KeyEventType.KeyUp && backHeld) {
                backHeld = false
                climbBack()
                return@handler true
            }
        }
        // OK with digits typed commits them on key-up, as the player does; the cell does not play on this press.
        val okKey = event.key.isOk
        // The rest of an OK held to open the details goes nowhere, so Watch, focused under it, does not play.
        if (okKey && event.type == KeyEventType.KeyDown && heldOk.onKeyDown()) return@handler true
        if (okKey && event.type == KeyEventType.KeyUp && heldOk.onKeyUp()) return@handler true
        if (okKey && numbers.hasDigits && !searchOpen) {
            if (event.type == KeyEventType.KeyUp) numbers.commit()
            return@handler true
        }
        if (event.type != KeyEventType.KeyDown) return@handler false
        dwell.onKey(uptimeMs())
        dwellNonce++
        when (val shortcut = event.key.guideShortcut()) {
            GuideShortcut.NOW -> {
                details = null
                onNow()
            }
            GuideShortcut.SEARCH -> {
                details = null
                openVoiceSearch()
            }
            GuideShortcut.DETAILS -> {
                if (details != null) closeDetails() else focused?.let { openDetails(it.channel, it.cell) }
            }
            GuideShortcut.DAY_FORWARD, GuideShortcut.DAY_BACK -> {
                details = null
                if (!searchOpen) onDayJump(shortcut)
            }
            null -> {
                val digit = event.key.digitValue()
                if (digit == null || searchOpen) return@handler false
                numbers.append(digit)
            }
        }
        true
    }

    // A finger or mouse pressing or scrolling anywhere in the guide restarts the rest as a key does, and keeps the
    // focused channel from opening full screen under it until a key is pressed. Observed, never consumed.
    val onGuidePointer = Modifier.pointerInput(dwell) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.type == PointerEventType.Press || event.type == PointerEventType.Scroll) {
                    dwell.onPointer(uptimeMs())
                    dwellNonce++
                }
            }
        }
    }

    // The app header above already carries the top screen margin; 16dp here is spacing, not overscan.
    val profile = LocalLayoutProfile.current
    Box(modifier.fillMaxSize().testTag(GUIDE_TEST_TAG).onFocusChanged { if (it.hasFocus) awaitingEntry = false }.onPreviewKeyEvent(onGuideKey).then(onGuidePointer)) {
        Column(modifier = Modifier.fillMaxSize().screenPadding(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom).padding(top = 16.dp)) {
            if (PREVIEW_ENABLED && profile.showsPreviewBand) {
                PreviewBand(
                    focused = focusedState,
                    now = now,
                    guideLoading = lineup.guideLoading,
                    searchOpen = searchOpen,
                    chips = chips,
                    searchBar = searchBar,
                    player = previewPlayer,
                    sessions = sessions,
                    captionsEnabled = captionsEnabled,
                    captionStyle = captionStyle,
                    muted = previewMuted,
                    ownsVolume = shown,
                    failed = previewFailed,
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
                Box(Modifier.width(pinWidth(profile)).fillMaxHeight(), contentAlignment = Alignment.CenterStart) {
                    VisibleRange(
                        scrollState = scrollState,
                        viewport = viewport,
                        minutePx = minutePx,
                        windowStart = windowStart,
                        now = now,
                        timeFormat = timeFormat,
                        todayLabel = todayLabel,
                        tomorrowLabel = tomorrowLabel,
                        rangeFormat = rangeFormat,
                    )
                }
                TimelineStrip(
                    scrollState = scrollState,
                    nowMinute = nowMinute,
                    modifier = Modifier.weight(1f).onSizeChanged { viewport.intValue = it.width },
                ) {
                    // Formatted once per window, not on every recomposition of the strip.
                    val slotLabels = remember(windowStart, timeFormat) {
                        List((GuideTimeline.WINDOW_MINUTES / GuideTimeline.SLOT_MINUTES).toInt()) { slot ->
                            timeFormat.format(windowStart + (slot * GuideTimeline.SLOT_MINUTES).minutes)
                        }
                    }
                    Row(Modifier.fillMaxHeight()) {
                        slotLabels.forEach { label ->
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

            val emptyMessage = when {
                visible.isNotEmpty() -> null
                query.isNotBlank() -> Res.string.guide_search_none
                activeFilter == GuideFilter.Favorites -> Res.string.guide_favorites_none
                activeFilter == GuideFilter.Recent -> Res.string.guide_recent_none
                else -> null
            }
            if (emptyMessage != null) {
                Text(
                    stringResource(emptyMessage),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(top = 16.dp),
                )
            } else {
                CompositionLocalProvider(LocalBringIntoViewSpec provides NoBringIntoView) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).focusRestorer().onFocusChanged { gridFocused = it.hasFocus }.onPreviewKeyEvent(onGridKey),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    itemsIndexed(items, key = { _, item -> item.key }) { index, item ->
                        when (item) {
                            is GuideListItem.Row -> ChannelRow(
                                channel = item.channel,
                                programmes = lineup.guide[item.channel.guideId].orEmpty(),
                                isFavorite = item.channel.guideId in favorites,
                                windowStart = windowStart,
                                windowEnd = windowEnd,
                                nowMinute = nowMinute,
                                env = env,
                                initialFocus = initialFocus.takeIf { index == 0 },
                                jump = jump?.takeIf { it.guideId == item.channel.guideId },
                                onJumpHandled = onJumpHandled,
                            )
                            is GuideListItem.Later -> LaterRow(
                                item = item,
                                env = env,
                                firstFocus = laterFocus.getValue(item.key),
                                onFocused = {
                                    laterFocused = item.key
                                    pendingRow = null
                                },
                            )
                        }
                    }
                }
                }
            }
        }
        ChannelNumberBadge(numbers)
        details?.let { shown ->
            ProgrammeSheet(
                details = shown,
                now = now,
                isFavorite = shown.channel.guideId in favorites,
                guideLoading = lineup.guideLoading,
                onWatch = { channel ->
                    details = null
                    play(channel)
                },
                onToggleFavorite = onToggleFavorite,
                onDismiss = closeDetails,
                recording = recordingMarks?.let { marks -> shown.programme?.let(marks::of) ?: RecordingMark() },
                onRecord = onRecord,
                onRecordSeries = onRecordSeries,
            )
        }
    }
}

/** Chips and search or details beside the preview in one band, so the grid keeps four rows on a 1080p TV. */
@Composable
private fun PreviewBand(
    focused: State<FocusedCell?>,
    now: Instant,
    guideLoading: Boolean,
    searchOpen: Boolean,
    chips: @Composable (Modifier) -> Unit,
    searchBar: @Composable (Modifier) -> Unit,
    player: LivePlayer,
    sessions: PlaybackSessions,
    captionsEnabled: Boolean,
    captionStyle: CaptionStyle,
    muted: Boolean,
    ownsVolume: Boolean,
    failed: Boolean,
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
                    guideLoading = guideLoading,
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
            ownsVolume = ownsVolume,
            failed = failed,
            onFailedChanged = onFailedChanged,
        )
    }
}
