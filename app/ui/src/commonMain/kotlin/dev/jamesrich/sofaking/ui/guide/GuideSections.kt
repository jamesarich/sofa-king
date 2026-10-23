package dev.jamesrich.sofaking.ui.guide

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import dev.jamesrich.sofaking.data.CaptionStyle
import dev.jamesrich.sofaking.data.Channel
import dev.jamesrich.sofaking.data.Lineup
import dev.jamesrich.sofaking.data.PlaybackSessions
import dev.jamesrich.sofaking.data.Programme
import dev.jamesrich.sofaking.resources.Res
import dev.jamesrich.sofaking.resources.guide_cell_airing
import dev.jamesrich.sofaking.resources.guide_cell_later
import dev.jamesrich.sofaking.resources.guide_cell_none
import dev.jamesrich.sofaking.resources.guide_details
import dev.jamesrich.sofaking.resources.guide_favorite
import dev.jamesrich.sofaking.resources.guide_later
import dev.jamesrich.sofaking.resources.guide_later_day
import dev.jamesrich.sofaking.resources.guide_out_of_date
import dev.jamesrich.sofaking.resources.guide_range
import dev.jamesrich.sofaking.resources.guide_today
import dev.jamesrich.sofaking.resources.guide_toggle_favorite
import dev.jamesrich.sofaking.resources.guide_tomorrow
import dev.jamesrich.sofaking.resources.listing_loading
import dev.jamesrich.sofaking.resources.no_guide
import dev.jamesrich.sofaking.resources.no_listing
import dev.jamesrich.sofaking.ui.LocalLayoutProfile
import dev.jamesrich.sofaking.ui.TimeFormatter
import dev.jamesrich.sofaking.ui.player.LivePlayer
import dev.jamesrich.sofaking.ui.preview.PREVIEW_BAND_HEIGHT
import dev.jamesrich.sofaking.ui.preview.PreviewDetails
import dev.jamesrich.sofaking.ui.preview.PreviewVideo
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** What every row of the grid shares: its labels, its metrics and the moves it reports. */
@Composable
internal fun rememberGuideRowEnv(
    timeFormat: TimeFormatter,
    guideLoading: Boolean,
    dayOffset: Int,
    minutePx: Float,
    minStickyTextPx: Float,
    scrollState: ScrollState,
    onCellFocused: (Channel, GuideCell) -> Unit,
    onPlay: (Channel) -> Unit,
    onToggleFavorite: (Channel) -> Unit,
    onOpenDetails: (Channel, GuideCell) -> Unit,
    onJumpTo: (Channel, Programme) -> Unit,
): GuideRowEnv {
    val cellAiring = stringResource(Res.string.guide_cell_airing)
    val cellLater = stringResource(Res.string.guide_cell_later)
    val cellNone = stringResource(Res.string.guide_cell_none)
    val noListing = stringResource(Res.string.no_listing)
    val listingLoading = stringResource(Res.string.listing_loading)
    val noGuide = stringResource(Res.string.no_guide)
    val guideOutOfDate = stringResource(Res.string.guide_out_of_date)
    val favoriteLabel = stringResource(Res.string.guide_favorite)
    val toggleFavoriteLabel = stringResource(Res.string.guide_toggle_favorite)
    val detailsLabel = stringResource(Res.string.guide_details)
    val laterLabel = stringResource(if (dayOffset == 0) Res.string.guide_later else Res.string.guide_later_day)
    return remember(
        cellAiring, cellLater, cellNone, timeFormat, noListing, listingLoading, guideLoading, noGuide, guideOutOfDate, favoriteLabel,
        toggleFavoriteLabel, detailsLabel, laterLabel,
        minutePx, minStickyTextPx, scrollState, onCellFocused, onPlay, onToggleFavorite, onOpenDetails, onJumpTo,
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
            onPlay = onPlay,
            onToggleFavorite = onToggleFavorite,
            onOpenDetails = onOpenDetails,
            detailsLabel = detailsLabel,
            laterLabel = laterLabel,
            onJumpTo = onJumpTo,
        )
    }
}

/** The span on screen beside the channel names, and the half-hour labels above the grid, which scroll with it. */
@Composable
internal fun GuideTimeHeader(
    scrollState: ScrollState,
    viewport: MutableIntState,
    minutePx: Float,
    windowStart: Instant,
    now: Instant,
    nowMinute: Long?,
    timeFormat: TimeFormatter,
) {
    Row(Modifier.fillMaxWidth().height(HEADER_HEIGHT)) {
        Box(Modifier.width(pinWidth(LocalLayoutProfile.current)).fillMaxHeight(), contentAlignment = Alignment.CenterStart) {
            VisibleRange(
                scrollState = scrollState,
                viewport = viewport,
                minutePx = minutePx,
                windowStart = windowStart,
                now = now,
                timeFormat = timeFormat,
                todayLabel = stringResource(Res.string.guide_today),
                tomorrowLabel = stringResource(Res.string.guide_tomorrow),
                rangeFormat = stringResource(Res.string.guide_range),
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
}

/**
 * The channel rows, with a Later line under each channel search found later in the window, or [emptyMessage]
 * when the filter or search leaves none. Focus never scrolls the list: [onGridKey] and the guide's own moves do.
 * [focusRequester] enters the rows at the cell focus last left.
 */
@Composable
internal fun ColumnScope.GuideGrid(
    state: GuideState,
    lineup: Lineup,
    favorites: Set<String>,
    items: List<GuideListItem>,
    emptyMessage: StringResource?,
    listState: LazyListState,
    windowStart: Instant,
    windowEnd: Instant,
    nowMinute: Long?,
    env: GuideRowEnv,
    initialFocus: FocusRequester,
    focusRequester: FocusRequester,
    laterFocus: Map<String, FocusRequester>,
    onGridKey: (KeyEvent) -> Boolean,
    onJumpHandled: (Boolean) -> Unit,
) {
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
                modifier = Modifier.weight(1f).focusRequester(focusRequester).focusRestorer().onFocusChanged { state.gridFocused = it.hasFocus }.onPreviewKeyEvent(onGridKey),
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
                            jump = state.jump?.takeIf { it.guideId == item.channel.guideId },
                            onJumpHandled = onJumpHandled,
                        )
                        is GuideListItem.Later -> LaterRow(
                            item = item,
                            env = env,
                            firstFocus = laterFocus.getValue(item.key),
                            onFocused = { state.laterFocused(item.key) },
                        )
                    }
                }
            }
        }
    }
}

/** Chips and search or details beside the preview in one band, so the grid keeps four rows on a 1080p TV. */
@Composable
internal fun PreviewBand(
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
