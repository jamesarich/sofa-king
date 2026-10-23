package dev.jamesrich.sofaking.ui.guide

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import dev.jamesrich.sofaking.data.AutoOpenDelay
import dev.jamesrich.sofaking.data.CaptionStyle
import dev.jamesrich.sofaking.data.Channel
import dev.jamesrich.sofaking.data.Lineup
import dev.jamesrich.sofaking.data.PlaybackSessions
import dev.jamesrich.sofaking.data.ProgramKind
import dev.jamesrich.sofaking.data.Programme
import dev.jamesrich.sofaking.data.RecordingMark
import dev.jamesrich.sofaking.data.RecordingMarks
import dev.jamesrich.sofaking.data.airingsOf
import dev.jamesrich.sofaking.data.orderedFavorites
import dev.jamesrich.sofaking.resources.Res
import dev.jamesrich.sofaking.resources.guide_favorites_none
import dev.jamesrich.sofaking.resources.guide_recent_none
import dev.jamesrich.sofaking.resources.guide_search_hint
import dev.jamesrich.sofaking.resources.guide_search_none
import dev.jamesrich.sofaking.resources.guide_search_voice_prompt
import dev.jamesrich.sofaking.resources.refreshing
import dev.jamesrich.sofaking.ui.BackHandler
import dev.jamesrich.sofaking.ui.FIRST_FOCUS_FRAMES
import dev.jamesrich.sofaking.ui.InputKind
import dev.jamesrich.sofaking.ui.LocalLastInput
import dev.jamesrich.sofaking.ui.LocalLayoutProfile
import dev.jamesrich.sofaking.ui.appLifecycleOwner
import dev.jamesrich.sofaking.ui.player.ChannelNumberBadge
import dev.jamesrich.sofaking.ui.player.LivePlayer
import dev.jamesrich.sofaking.ui.player.ZapScope
import dev.jamesrich.sofaking.ui.preview.PREVIEW_ENABLED
import dev.jamesrich.sofaking.ui.preview.PreviewDwell
import dev.jamesrich.sofaking.ui.rememberMinuteNow
import dev.jamesrich.sofaking.ui.rememberScreenReaderActive
import dev.jamesrich.sofaking.ui.rememberTimeFormatter
import dev.jamesrich.sofaking.ui.rememberVoiceSearch
import dev.jamesrich.sofaking.ui.requestFocusWhenAttached
import dev.jamesrich.sofaking.ui.screenPadding
import dev.jamesrich.sofaking.ui.uptimeMs
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

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

/** What the preview's rest timers watch: the channel under focus, the last key press, whether the preview failed and whether sound is on. */
private data class DwellInput(val channelId: String?, val nonce: Int, val failed: Boolean, val sound: Boolean)

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
    val state = rememberGuideState(now, initialFilterKey, startSearch, startInHeader)
    val liveStart = remember(state.liveStartSecond) { Instant.fromEpochSeconds(state.liveStartSecond) }
    val windowStart = remember(liveStart, state.dayOffset) {
        liveStart + (state.dayOffset * GuideTimeline.WINDOW_MINUTES).minutes
    }
    val windowEnd = remember(windowStart) { GuideTimeline.windowEnd(windowStart) }
    val nowMinute = GuideTimeline.offsetMinutes(windowStart, now).takeIf { now >= windowStart && now < windowEnd }
    val nowClamped = GuideTimeline.offsetMinutes(windowStart, now)
    LaunchedEffect(now) { state.rollWindow(now) }
    val timeFormat = rememberTimeFormatter()

    // What is on now is read every few minutes, not every minute, so the chips and their lists hold still while browsing.
    val onNowSlot = now.epochSeconds / ON_NOW_REFRESH_SECONDS
    val kindsOnNow = remember(lineup.channels, lineup.guide, onNowSlot) { channelsOnNow(lineup, now) }
    val filters = remember(lineup.channels, kindsOnNow.keys) { guideFilters(lineup.channels, kindsOnNow.keys) }
    val filterCounts =
        remember(lineup.channels, favorites, recentChannels, kindsOnNow) {
            guideFilterCounts(lineup.channels, favorites, recentChannels, kindsOnNow)
        }
    val activeFilter = state.filter.takeIf { it in filters } ?: GuideFilter.All

    val query = if (state.searchOpen) state.searchText.text.toString() else ""
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
                    // Within the window on screen: a match from earlier would jump to the window's start, not to it.
                    val later = GuideSearch.upcoming(
                        query,
                        lineup.guide[channel.guideId].orEmpty(),
                        maxOf(
                            searchNow,
                            windowStart - 1.milliseconds,
                        ),
                        windowEnd,
                    )
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
    val laterFocus = remember(items) {
        items.filterIsInstance<GuideListItem.Later>().associate {
            it.key to
                FocusRequester()
        }
    }

    val scrollState = rememberScrollState()
    val listState = rememberLazyListState()
    val chipsState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val density = LocalDensity.current
    val minutePx = with(density) { MINUTE_WIDTH.toPx() }
    val minStickyTextPx = with(density) { MIN_STICKY_TEXT.toPx() }
    val marginPx = minutePx * FOCUS_MARGIN_MINUTES
    val viewport = remember { mutableIntStateOf(0) }
    // The preview's rest timers: muted on tune, sound after five seconds, full screen after the chosen delay,
    // restarted by any key. Both run only while a cell holds focus with search closed, so typing never opens a channel.
    // TalkBack reads the grid aloud as focus moves; a channel opening full screen would cut that off.
    val touchExploration = rememberScreenReaderActive()
    val fullScreenAfterMs = autoOpenDelay.afterMs.takeUnless { touchExploration }
    val currentPreviewSound by rememberUpdatedState(previewSound)
    val lastInput = LocalLastInput.current
    val dwell = remember(fullScreenAfterMs) {
        PreviewDwell(
            suppressedChannelId = returnedFrom,
            fullScreenAfterMs = fullScreenAfterMs,
            pointerDriven =
            lastInput.kind == InputKind.POINTER,
        )
    }

    val selectedChip = remember { FocusRequester() }
    val searchChip = remember { FocusRequester() }
    val searchField = remember { FocusRequester() }
    val initialFocus = remember { FocusRequester() }
    val grid = remember { FocusRequester() }

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
        state.cellFocused(channel, cell)
        revealMinute(state.cursor)
    }
    val requestJump: (String, Long) -> Unit = { guideId, minute ->
        state.jumpTo(guideId, minute)
        revealMinute(minute)
    }

    LaunchedEffect(
        visible.isNotEmpty(),
        activeFilter,
        state.searchOpen,
        state.initialFocusAttempt,
        state.awaitingEntry,
        state.dayOffset,
    ) {
        if (state.awaitingEntry || state.initialFocusDone || state.initialFocusAttempt >= INITIAL_FOCUS_ATTEMPTS) {
            return@LaunchedEffect
        }
        if (startAtNow && state.dayOffset != 0) {
            // Now is in today's window, whatever day the guide was left on; this reruns on it.
            state.dayOffset = 0
            return@LaunchedEffect
        }
        if (watchedIsHidden(returnedFrom, visible, lineup.channels)) {
            // Reruns with the full lineup on screen.
            state.showAll()
            return@LaunchedEffect
        }
        if (visible.isEmpty()) {
            // A filter with no channels (no favorites yet): the selected chip holds focus until it has some.
            chipsState.scrollToItem(selectedChipIndex(filters, activeFilter))
            requestFocusWhenAttached(selectedChip)
            return@LaunchedEffect
        }
        val nowAt = nowMinute ?: nowClamped
        if (startAtNow) {
            state.cursor = nowAt
        } else if (returnedFrom !=
            null
        ) {
            state.cursor = cursorOnReturn(state.cursor, nowAt)
        }
        val row = initialGuideRow(visible, returnedFrom, state.lastFocusedGuideId, initialChannelId)
        if (row >= 0) {
            // Before the first measure the layout info is empty; a restored scroll position must not be thrown away.
            withFrameNanos { }
            val item = rowIndexOf(items, visible[row])
            if (listState.layoutInfo.visibleItemsInfo.none { it.index == item }) listState.scrollToItem(item)
            // The row reports through onJumpHandled whether its cell took focus.
            requestJump(visible[row].guideId, state.cursor)
        } else if (!requestFocusWhenAttached(initialFocus, FIRST_FOCUS_FRAMES)) {
            state.initialFocusAttempt++
        }
    }
    val onJumpHandled: (Boolean) -> Unit = { landed -> state.jumpHandled(landed) }
    // Only a composed row answers a jump; one whose row left the list (the lineup refreshing under the first
    // focus) would wait forever, so it counts as missed and the first focus scrolls to the row again.
    LaunchedEffect(state.jump) {
        val waiting = state.jump ?: return@LaunchedEffect
        repeat(FIRST_FOCUS_FRAMES * 2) { withFrameNanos { } }
        if (state.jump == waiting) state.jumpHandled(false)
    }

    // The guide fills in after the channels, so the focused cell's programmes change under it without a focus
    // event: the band is refreshed from the new row, and focus is put back on the cell at the cursor.
    LaunchedEffect(lineup.guide) {
        val current = state.focused ?: return@LaunchedEffect
        val cells = GuideTimeline.cellsFor(lineup.guide[current.channel.guideId].orEmpty(), windowStart, windowEnd)
        val cell = GuideTimeline.cellAfterUpdate(cells, current.cell, state.cursor) ?: return@LaunchedEffect
        if (cell != current.cell) state.focused = FocusedCell(current.channel, cell)
        // A row move already requested keeps its jump.
        if (state.gridFocused && state.pendingRow == null) requestJump(current.channel.guideId, state.cursor)
    }

    // Scrolls the list so item [target] holds the second slot and focuses its cell under the time cursor, or
    // a Later line's first chip. The channels scroll under the focused row, as a cable guide does; the list
    // clamps at either end.
    val focusRow: (Int) -> Unit = { target ->
        state.pendingRow = target
        scope.launch {
            listState.scrollToItem((target - FOCUS_ROW_SLOT).coerceAtLeast(0))
            when (val item = items[target]) {
                is GuideListItem.Row -> requestJump(item.channel.guideId, state.cursor)

                is GuideListItem.Later -> {
                    laterFocus[item.key]?.let { requestFocusWhenAttached(it, FIRST_FOCUS_FRAMES) }
                    state.pendingRow = null
                }
            }
        }
    }

    // A filter change starts the list at the focused channel, or at the top when the filter hides it, rather
    // than at the old filter's scroll index. A number typed for a hidden channel switches to All and moves
    // to its row here once the list has changed. Skipped until the first focus has landed, which places
    // the list itself.
    var listedFilter by remember { mutableStateOf(activeFilter) }
    LaunchedEffect(activeFilter, state.numberJump) {
        if (listedFilter == activeFilter && state.numberJump == null) return@LaunchedEffect
        listedFilter = activeFilter
        if (!state.initialFocusDone) return@LaunchedEffect
        val jumpRow = state.numberJump?.let { id -> visible.indexOfFirst { it.guideId == id } } ?: -1
        state.numberJump = null
        if (jumpRow >= 0) {
            focusRow(rowIndexOf(items, visible[jumpRow]))
        } else {
            listState.scrollToItem(firstItemAfterFilter(items.map { it.channel }, state.focused?.channel?.guideId))
        }
    }

    // Typed channel digits, as the player takes them. OK or the entry timeout commits them.
    SideEffect {
        state.numbers.onCommit = { pending ->
            state.commitNumber(pending, visible, lineup.channels) { channel -> focusRow(rowIndexOf(items, channel)) }
        }
    }

    val onGridKey: (KeyEvent) -> Boolean = { event -> state.onGridKey(event.key, event.type, items, focusRow) }

    // Now goes to the focused channel's row, which the chips keep, else the first row on screen; a day ahead comes back to the live window.
    val onNow: () -> Unit = {
        val guideId =
            state.focused?.channel?.guideId ?: items.getOrNull(listState.firstVisibleItemIndex)?.channel?.guideId
        state.dayOffset = 0
        if (guideId != null) requestJump(guideId, GuideTimeline.offsetMinutes(liveStart, now))
    }
    // Fast Forward and Rewind move the grid a whole day, keeping the time of day and the focused channel; a day
    // beyond the live one is fetched on first visit and its cells fill in as it arrives.
    val onDayJump: (GuideShortcut) -> Unit = { shortcut ->
        val next = dayOffsetAfter(state.dayOffset, shortcut)
        if (next != state.dayOffset) {
            state.dayOffset = next
            val start = liveStart + (next * GuideTimeline.WINDOW_MINUTES).minutes
            if (next > 0) onLoadGuideWindow(start)
            state.focused?.channel?.guideId?.let { requestJump(it, state.cursor) }
        }
    }
    // The details of the focused programme, on the Info key or a held OK; Watch plays it and Back or Close returns focus to its cell.
    val onCloseDetails: () -> Unit = {
        state.details = null
        state.focused?.channel?.guideId?.let { requestJump(it, state.cursor) }
    }
    val startVoiceSearch =
        rememberVoiceSearch(stringResource(Res.string.guide_search_voice_prompt)) { spoken ->
            state.voiceSearched(spoken)
        }
    val openVoiceSearch: () -> Unit = {
        if (!startVoiceSearch()) state.openSearchField()
    }
    LaunchedEffect(Unit) { if (startSearch) openVoiceSearch() }
    // From the grid, Back climbs to the selected chip (scrolled into the row first, since only a composed chip
    // can take focus), from the chips to the header's open tab; a press with nowhere left to climb leaves the
    // activity to finish.
    val backAction = guideBackAction(
        state.searchOpen,
        state.gridFocused,
        state.chipsFocused,
        hasHeader =
        headerFocus != null,
    )
    val climb: () -> Unit = {
        when (backAction) {
            GuideBack.CLOSE_SEARCH -> state.closeSearch()

            GuideBack.FOCUS_CHIPS -> scope.launch {
                chipsState.scrollToItem(selectedChipIndex(filters, activeFilter))
                requestFocusWhenAttached(selectedChip)
            }

            GuideBack.FOCUS_HEADER -> headerFocus?.requestFocus()

            GuideBack.EXIT -> Unit
        }
    }
    // A remote's Back key climbs in GuideState.onKeyEvent; this takes the system Back gesture and Escape.
    BackHandler(enabled = backAction != GuideBack.EXIT, onBack = climb)
    LaunchedEffect(state.searchOpen, state.searchFocusRequest) {
        if (!state.searchOpen) return@LaunchedEffect
        if (state.focusResults) {
            state.focusResults = false
            // No row matched: the field takes focus so the query can be changed.
            if (requestFocusWhenAttached(initialFocus, FIRST_FOCUS_FRAMES)) return@LaunchedEffect
        }
        requestFocusWhenAttached(searchField)
    }
    LaunchedEffect(state.returnToSearchChip) {
        if (state.returnToSearchChip) {
            requestFocusWhenAttached(searchChip)
            state.returnToSearchChip = false
        }
    }

    // Off a TV the details are a bottom sheet, a window of their own.
    val detailsInWindow = !LocalLayoutProfile.current.tv
    val onOpenDetails: (Channel, GuideCell) -> Unit = remember(detailsInWindow) {
        { channel, cell -> state.openDetails(channel, cell, inWindow = detailsInWindow) }
    }
    val currentRequestJump by rememberUpdatedState(requestJump)
    val currentWindowStart by rememberUpdatedState(windowStart)
    val onJumpTo: (Channel, Programme) -> Unit = remember {
        { channel, programme ->
            currentRequestJump(channel.guideId, GuideTimeline.offsetMinutes(currentWindowStart, programme.start))
        }
    }
    val env = rememberGuideRowEnv(
        timeFormat = timeFormat,
        guideLoading = lineup.guideLoading,
        dayOffset = state.dayOffset,
        minutePx = minutePx,
        minStickyTextPx = minStickyTextPx,
        scrollState = scrollState,
        onCellFocused = onCellFocused,
        onPlay = play,
        onToggleFavorite = onToggleFavorite,
        onOpenDetails = onOpenDetails,
        onJumpTo = onJumpTo,
    )

    val chips: @Composable (Modifier) -> Unit = { chipModifier ->
        FilterChipRow(
            filters = filters,
            counts = filterCounts,
            activeFilter = activeFilter,
            searchOpen = state.searchOpen,
            selectedChip = selectedChip,
            searchChip = searchChip,
            listState = chipsState,
            onNow = onNow,
            onSearch = { state.toggleSearch() },
            onSelect = {
                state.filter = it
                onFilterChanged(it.key())
            },
            // Down returns to the cell focus left, wherever the chip is. Searched geometrically, it could land on a
            // rail item below and left of the chips rather than the grid.
            modifier = chipModifier
                .focusProperties {
                    onExit =
                        {
                            if (requestedFocusDirection == FocusDirection.Down && !state.searchOpen) {
                                grid.requestFocus(FocusDirection.Enter)
                            }
                        }
                }
                .onFocusChanged { state.chipsFocused = it.hasFocus },
        )
    }
    val searchBar: @Composable (Modifier) -> Unit = { barModifier ->
        SearchField(
            state = state.searchText,
            focusRequester = searchField,
            hint = stringResource(Res.string.guide_search_hint),
            // A phone's keyboard in landscape can leave the results no room, so no row takes focus; it goes away then.
            onDone = { if (!focusManager.moveFocus(FocusDirection.Down)) keyboard?.hide() },
            modifier = barModifier,
        )
    }

    // Observed from the snapshot, not read in composition, so a focus move still recomposes only the band.
    // Nothing opens while the app is stopped (Home, the speech recognizer, screen off); coming back starts
    // the rest over, muted, so a channel is never found open on return.
    val lifecycle = appLifecycleOwner.lifecycle
    LifecycleEventEffect(Lifecycle.Event.ON_START, appLifecycleOwner) {
        dwell.restart(uptimeMs())
        state.dwellNonce++
        // Back after half an hour or more, the grid shows now again: the window rolls on if it must and the
        // focused row jumps to the programme on now.
        val at = Clock.System.now()
        if (state.cameBack(at)) {
            state.rollWindow(at)
            val guideId =
                state.focused?.channel?.guideId ?: items.getOrNull(listState.firstVisibleItemIndex)?.channel?.guideId
            if (guideId != null) requestJump(guideId, GuideTimeline.offsetMinutes(state.liveStart, at))
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP, appLifecycleOwner) { state.left(Clock.System.now()) }
    LaunchedEffect(dwell) {
        snapshotFlow {
            DwellInput(
                state.focused?.channel?.guideId?.takeIf {
                    state.gridFocused && !state.searchOpen
                },
                state.dwellNonce,
                state.previewFailed,
                currentPreviewSound,
            )
        }
            .collectLatest { input ->
                if (dwell.channelId != input.channelId) dwell.onFocus(input.channelId, uptimeMs())
                while (true) {
                    val now = uptimeMs()
                    state.previewMuted = !input.sound || dwell.isMuted(now)
                    if (dwell.opensFullScreen(now)) {
                        // The same handoff as OK: the preview session is promoted, nothing reloads. A preview
                        // without a picture is not opened; it would only carry its failure to the full screen.
                        val channel = state.focused?.channel?.takeIf { it.guideId == dwell.channelId }
                        if (channel != null && !input.failed &&
                            lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
                        ) {
                            currentOnPlay(channel, currentZapScope, true)
                        }
                        return@collectLatest
                    }
                    delay(dwell.nextTransitionMs(now) ?: return@collectLatest)
                }
            }
    }

    val controls = object : GuideControls {
        override fun climbBack() = climb()

        override fun restartRest() {
            dwell.onKey(uptimeMs())
            state.dwellNonce++
        }

        override fun now() = onNow()

        override fun search() = openVoiceSearch()

        override fun closeDetails() = onCloseDetails()

        override fun dayJump(shortcut: GuideShortcut) = onDayJump(shortcut)
    }

    // A finger or mouse pressing or scrolling anywhere in the guide restarts the rest as a key does, and keeps the
    // focused channel from opening full screen under it until a key is pressed. Observed, never consumed.
    val onGuidePointer = Modifier.pointerInput(dwell) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.type == PointerEventType.Press || event.type == PointerEventType.Scroll) {
                    dwell.onPointer(uptimeMs())
                    state.dwellNonce++
                }
            }
        }
    }

    // The app header above already carries the top screen margin; 16dp here is spacing, not overscan.
    val profile = LocalLayoutProfile.current
    Box(
        modifier.fillMaxSize().testTag(GUIDE_TEST_TAG)
            // Digits typed and left behind for the header, the rail or another tab go nowhere: committed later they
            // would pull focus back into the guide, or into a page already closing.
            .onFocusChanged { if (it.hasFocus) state.awaitingEntry = false else state.numbers.clear() }
            .onPreviewKeyEvent { event -> state.onKeyEvent(event.key, event.type, backAction, controls) }
            .then(onGuidePointer),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().screenPadding(
                WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom,
            ).padding(top = 16.dp),
        ) {
            if (PREVIEW_ENABLED && profile.showsPreviewBand) {
                PreviewBand(
                    focused = state.focusedState,
                    now = now,
                    guideLoading = lineup.guideLoading,
                    searchOpen = state.searchOpen,
                    chips = chips,
                    searchBar = searchBar,
                    player = previewPlayer,
                    sessions = sessions,
                    captionsEnabled = captionsEnabled,
                    captionStyle = captionStyle,
                    muted = state.previewMuted,
                    ownsVolume = shown,
                    failed = state.previewFailed,
                    onFailedChanged = { state.previewFailed = it },
                )
            } else {
                chips(Modifier.fillMaxWidth())
                if (state.searchOpen) {
                    Spacer(Modifier.height(8.dp))
                    searchBar(Modifier.fillMaxWidth())
                }
            }
            Spacer(Modifier.height(8.dp))
            GuideTimeHeader(scrollState, viewport, minutePx, windowStart, now, nowMinute, timeFormat)
            GuideGrid(
                state = state,
                lineup = lineup,
                favorites = favorites,
                items = items,
                emptyMessage = when {
                    visible.isNotEmpty() -> null
                    query.isNotBlank() -> Res.string.guide_search_none
                    activeFilter == GuideFilter.Favorites -> Res.string.guide_favorites_none
                    activeFilter == GuideFilter.Recent -> Res.string.guide_recent_none
                    else -> null
                },
                listState = listState,
                windowStart = windowStart,
                windowEnd = windowEnd,
                nowMinute = nowMinute,
                env = env,
                initialFocus = initialFocus,
                focusRequester = grid,
                laterFocus = laterFocus,
                onGridKey = onGridKey,
                onJumpHandled = onJumpHandled,
            )
        }
        ChannelNumberBadge(state.numbers)
        state.details?.let { shown ->
            ProgrammeSheet(
                details = shown,
                now = now,
                isFavorite = shown.channel.guideId in favorites,
                guideLoading = lineup.guideLoading,
                onWatch = { channel ->
                    state.details = null
                    play(channel)
                },
                onToggleFavorite = onToggleFavorite,
                onDismiss = onCloseDetails,
                recording = recordingMarks?.let { marks -> shown.programme?.let(marks::of) ?: RecordingMark() },
                onRecord = onRecord,
                onRecordSeries = onRecordSeries,
                airings = remember(lineup, shown.programme, onNowSlot) {
                    shown.programme?.let { lineup.airingsOf(it, now) }.orEmpty()
                },
                onShowAiring = { airing -> state.details = ProgrammeDetails(airing.channel, airing.programme) },
            )
        }
    }
}
