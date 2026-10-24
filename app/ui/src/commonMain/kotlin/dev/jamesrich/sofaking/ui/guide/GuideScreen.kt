package dev.jamesrich.sofaking.ui.guide

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.jamesrich.sofaking.data.Channel
import dev.jamesrich.sofaking.data.Lineup
import dev.jamesrich.sofaking.data.Program
import dev.jamesrich.sofaking.data.RecordingMark
import dev.jamesrich.sofaking.data.RecordingMarks
import dev.jamesrich.sofaking.data.airingsOf
import dev.jamesrich.sofaking.resources.Res
import dev.jamesrich.sofaking.resources.guide_favorites_none
import dev.jamesrich.sofaking.resources.guide_recent_none
import dev.jamesrich.sofaking.resources.guide_search_hint
import dev.jamesrich.sofaking.resources.guide_search_none
import dev.jamesrich.sofaking.ui.BackHandler
import dev.jamesrich.sofaking.ui.LocalLayoutProfile
import dev.jamesrich.sofaking.ui.TimeFormatter
import dev.jamesrich.sofaking.ui.player.ChannelNumberBadge
import dev.jamesrich.sofaking.ui.player.ZapScope
import dev.jamesrich.sofaking.ui.preview.PREVIEW_ENABLED
import dev.jamesrich.sofaking.ui.rememberMinuteNow
import dev.jamesrich.sofaking.ui.rememberTimeFormatter
import dev.jamesrich.sofaking.ui.screenPadding
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Instant

/** The guide's test tag; Android exposes it as a resource id for the Baseline Profile generator. */
const val GUIDE_TEST_TAG = "guide"

/**
 * The guide grid. Filter, search, time cursor, scroll and the focused channel are saveable across a trip to the
 * player; [arrival] says where a visit starts.
 */
@Composable
fun GuideScreen(
    lineup: Lineup,
    favorites: Set<String>,
    recentChannels: List<String>,
    arrival: GuideArrival,
    actions: GuideActions,
    preview: GuidePreview,
    modifier: Modifier = Modifier,
    /** The order Settings gave the favorites; the Favorites filter lists them so, the rest by number. */
    favoritesOrder: List<String> = emptyList(),
    /** How programs are set to record; null on a server that does not record, which hides the record buttons. */
    recordingMarks: RecordingMarks? = null,
    /** The header's open tab, which Back from the chips focuses; null when the header has no tabs. */
    headerFocus: FocusRequester? = null,
    /** Whether the guide is the page on top; on its way out under the player it leaves the volume alone. */
    shown: Boolean = true,
) {
    val now by rememberMinuteNow()
    val state = rememberGuideState(now, arrival.initialFilterKey, arrival.startSearch, arrival.startInHeader)
    val span = rememberGuideSpan(state, now)
    LaunchedEffect(now) { state.rollWindow(now) }
    val channels = rememberGuideChannels(lineup, favorites, favoritesOrder, recentChannels, state, span)
    val nav = rememberGuideNavigator(state)
    val open = rememberOpenChannel(actions.onPlay, channels.zapScope)
    val dwell = rememberPreviewDwell(preview.autoOpenDelay, arrival.returnedFrom)
    GuideFirstFocus(state, nav, channels, span, arrival, lineup.channels)
    GuideFocusEffects(state, nav, channels, span, lineup)
    PreviewRestEffects(state, nav, channels, dwell, preview.sound, open)
    val openSearch = rememberOpenSearch(state, arrival.startSearch)
    // From the grid, Back climbs to the selected chip, from the chips to the header's open tab; a press with nowhere
    // left to climb leaves the activity to finish.
    val backAction = guideBackAction(state.searchOpen, state.gridFocused, state.chipsFocused, headerFocus != null)
    // A remote's Back key climbs in GuideState.onKeyEvent; this takes the system Back gesture and Escape.
    BackHandler(enabled = backAction != GuideBack.EXIT) { nav.climb(backAction, channels, headerFocus) }
    val controls = object : GuideControls {
        override fun climbBack() = nav.climb(backAction, channels, headerFocus)

        override fun restartRest() = state.restartRest(dwell)

        override fun now() = nav.now(channels.items, now)

        override fun search() = openSearch()

        override fun closeDetails() = nav.closeDetails()

        override fun dayJump(shortcut: GuideShortcut) = nav.dayJump(shortcut, actions.onLoadGuideWindow)
    }
    Box(
        modifier.fillMaxSize().testTag(GUIDE_TEST_TAG)
            // Digits typed and left behind for the header, the rail or another tab go nowhere: committed later they
            // would pull focus back into the guide, or into a page already closing.
            .onFocusChanged { if (it.hasFocus) state.awaitingEntry = false else state.numbers.clear() }
            .onPreviewKeyEvent { event -> state.onKeyEvent(event.key, event.type, backAction, controls) }
            .restartsRestOnPointer(dwell, state),
    ) {
        GuideColumn(state, nav, channels, span, lineup, favorites, preview, shown, actions, open)
        ChannelNumberBadge(state.numbers)
        GuideDetails(state, nav, channels, span.now, lineup, favorites, recordingMarks, actions, open)
    }
}

/** Opens a channel full screen, in the zap scope of the filter it was opened from; unattended when the preview did. */
@Composable
private fun rememberOpenChannel(
    onPlay: (Channel, ZapScope?, Boolean) -> Unit,
    zapScope: ZapScope?,
): (Channel, Boolean) -> Unit {
    val currentZapScope by rememberUpdatedState(zapScope)
    val currentOnPlay by rememberUpdatedState(onPlay)
    return remember { { channel, unattended -> currentOnPlay(channel, currentZapScope, unattended) } }
}

/** The preview band or the chips, then the time header over the grid. The app header above carries the top margin. */
@Composable
private fun GuideColumn(
    state: GuideState,
    nav: GuideNavigator,
    channels: GuideChannels,
    span: GuideSpan,
    lineup: Lineup,
    favorites: Set<String>,
    preview: GuidePreview,
    shown: Boolean,
    actions: GuideActions,
    open: (Channel, Boolean) -> Unit,
) {
    val timeFormat = rememberTimeFormatter()
    val env = rememberGuideEnv(state, nav, span.start, timeFormat, lineup.guideLoading, open, actions.onToggleFavorite)
    val chips: @Composable (Modifier) -> Unit = { chipModifier ->
        GuideChips(state, nav, channels, { nav.now(channels.items, span.now) }, actions.onFilterChange, chipModifier)
    }
    val searchBar: @Composable (Modifier) -> Unit = { barModifier -> GuideSearchBar(state, nav, barModifier) }
    // 16dp here is spacing, not overscan.
    Column(
        modifier = Modifier.fillMaxSize().screenPadding(
            WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom,
        ).padding(top = 16.dp),
    ) {
        GuideTop(state, span.now, lineup.guideLoading, preview, shown, chips, searchBar)
        Spacer(Modifier.height(8.dp))
        GuideTimeHeader(
            scrollState = nav.scrollState,
            viewport = { nav.viewport.intValue },
            onViewportChange = { nav.viewport.intValue = it },
            minutePx = nav.minutePx,
            windowStart = span.start,
            now = span.now,
            nowMinute = span.nowMinute,
            timeFormat = timeFormat,
        )
        GuideGrid(
            state = state,
            lineup = lineup,
            favorites = favorites,
            items = channels.items,
            emptyMessage = emptyMessage(channels),
            listState = nav.listState,
            windowStart = span.start,
            windowEnd = span.end,
            nowMinute = span.nowMinute,
            env = env,
            initialFocus = nav.initialFocus,
            focusRequester = nav.grid,
            laterFocus = channels.laterFocus,
            onGridKey = { event ->
                state.onGridKey(event.key, event.type, channels.items) { nav.focusRow(it, channels) }
            },
            onJumpFinish = { landed -> state.jumpHandled(landed) },
        )
    }
}

/** What the grid says when it lists nothing: no search match, no favorites or no recent channels yet. */
private fun emptyMessage(channels: GuideChannels): StringResource? = when {
    channels.visible.isNotEmpty() -> null
    channels.query.isNotBlank() -> Res.string.guide_search_none
    channels.active == GuideFilter.Favorites -> Res.string.guide_favorites_none
    channels.active == GuideFilter.Recent -> Res.string.guide_recent_none
    else -> null
}

/** The chips and search beside the preview where the screen has room for it; else the chips over the search field. */
@Composable
private fun ColumnScope.GuideTop(
    state: GuideState,
    now: Instant,
    guideLoading: Boolean,
    preview: GuidePreview,
    shown: Boolean,
    chips: @Composable (Modifier) -> Unit,
    searchBar: @Composable (Modifier) -> Unit,
) {
    if (PREVIEW_ENABLED && LocalLayoutProfile.current.showsPreviewBand) {
        PreviewBand(
            focused = { state.focused },
            now = now,
            guideLoading = guideLoading,
            searchOpen = state.searchOpen,
            chips = chips,
            searchBar = searchBar,
            player = preview.player,
            sessions = preview.sessions,
            captionsEnabled = preview.captionsEnabled,
            captionStyle = preview.captionStyle,
            muted = state.previewMuted,
            ownsVolume = shown,
            failed = state.previewFailed,
            onFailedChange = { state.previewFailed = it },
        )
    } else {
        chips(Modifier.fillMaxWidth())
        if (state.searchOpen) {
            Spacer(Modifier.height(8.dp))
            searchBar(Modifier.fillMaxWidth())
        }
    }
}

/** What every row of the grid shares, with the moves that open a program's details and jump to a Later program. */
@Composable
private fun rememberGuideEnv(
    state: GuideState,
    nav: GuideNavigator,
    windowStart: Instant,
    timeFormat: TimeFormatter,
    guideLoading: Boolean,
    open: (Channel, Boolean) -> Unit,
    onToggleFavorite: (Channel) -> Unit,
): GuideRowEnv {
    // Off a TV the details are a bottom sheet, a window of their own.
    val detailsInWindow = !LocalLayoutProfile.current.tv
    val onOpenDetails: (Channel, GuideCell) -> Unit = remember(state, detailsInWindow) {
        { channel, cell -> state.openDetails(channel, cell, inWindow = detailsInWindow) }
    }
    val currentWindowStart by rememberUpdatedState(windowStart)
    val onJumpTo: (Channel, Program) -> Unit = remember(nav) {
        { channel, program ->
            nav.requestJump(channel.guideId, GuideTimeline.offsetMinutes(currentWindowStart, program.start))
        }
    }
    return rememberGuideRowEnv(
        timeFormat = timeFormat,
        guideLoading = guideLoading,
        dayOffset = state.dayOffset,
        minutePx = nav.minutePx,
        minStickyTextPx = nav.minStickyTextPx,
        scrollState = nav.scrollState,
        onCellFocus = nav.onCellFocus,
        onPlay = remember(open) { { channel -> open(channel, false) } },
        onToggleFavorite = onToggleFavorite,
        onOpenDetails = onOpenDetails,
        onJumpTo = onJumpTo,
    )
}

/** Now, Search and the filters; choosing a filter saves its key through [onFilterChange]. */
@Composable
private fun GuideChips(
    state: GuideState,
    nav: GuideNavigator,
    channels: GuideChannels,
    onNow: () -> Unit,
    onFilterChange: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    FilterChipRow(
        filters = channels.filters,
        counts = channels.counts,
        activeFilter = channels.active,
        searchOpen = state.searchOpen,
        selectedChip = nav.selectedChip,
        searchChip = nav.searchChip,
        listState = nav.chipsState,
        onNow = onNow,
        onSearch = { state.toggleSearch() },
        onSelect = {
            state.filter = it
            onFilterChange(it.key())
        },
        // Down returns to the cell focus left, wherever the chip is. Searched geometrically, it could land on a
        // rail item below and left of the chips rather than the grid.
        modifier = modifier
            .focusProperties {
                onExit = {
                    if (requestedFocusDirection == FocusDirection.Down && !state.searchOpen) {
                        nav.grid.requestFocus(FocusDirection.Enter)
                    }
                }
            }
            .onFocusChanged { state.chipsFocused = it.hasFocus },
    )
}

@Composable
private fun GuideSearchBar(state: GuideState, nav: GuideNavigator, modifier: Modifier = Modifier) {
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    SearchField(
        state = state.searchText,
        focusRequester = nav.searchField,
        hint = stringResource(Res.string.guide_search_hint),
        // A phone's keyboard in landscape can leave the results no room, so no row takes focus; it goes away then.
        onDone = { if (!focusManager.moveFocus(FocusDirection.Down)) keyboard?.hide() },
        modifier = modifier,
    )
}

/**
 * The details of the focused program, on the Info key or a held OK; Watch plays it and Back or Close returns focus to
 * its cell.
 */
@Composable
private fun GuideDetails(
    state: GuideState,
    nav: GuideNavigator,
    channels: GuideChannels,
    now: Instant,
    lineup: Lineup,
    favorites: Set<String>,
    recordingMarks: RecordingMarks?,
    actions: GuideActions,
    open: (Channel, Boolean) -> Unit,
) {
    val shown = state.details ?: return
    ProgramSheet(
        details = shown,
        now = now,
        isFavorite = shown.channel.guideId in favorites,
        guideLoading = lineup.guideLoading,
        onWatch = { channel ->
            state.details = null
            open(channel, false)
        },
        onToggleFavorite = actions.onToggleFavorite,
        onDismiss = { nav.closeDetails() },
        recording = recordingMarks?.let { marks -> shown.program?.let(marks::of) ?: RecordingMark() },
        onRecord = actions.onRecord,
        onRecordSeries = actions.onRecordSeries,
        airings = remember(lineup, shown.program, channels.onNowSlot) {
            shown.program?.let { lineup.airingsOf(it, now) }.orEmpty()
        },
        onShowAiring = { airing -> state.details = ProgramDetails(airing.channel, airing.program) },
    )
}
