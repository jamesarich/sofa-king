package dev.jamesrich.sofaking.ui.guide

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.jamesrich.sofaking.data.ProgramKind
import dev.jamesrich.sofaking.resources.Res
import dev.jamesrich.sofaking.resources.filter_all
import dev.jamesrich.sofaking.resources.filter_favorites
import dev.jamesrich.sofaking.resources.filter_kids_now
import dev.jamesrich.sofaking.resources.filter_movies_now
import dev.jamesrich.sofaking.resources.filter_news_now
import dev.jamesrich.sofaking.resources.filter_recent
import dev.jamesrich.sofaking.resources.filter_sports_now
import dev.jamesrich.sofaking.resources.guide_filter_count
import dev.jamesrich.sofaking.resources.guide_now
import dev.jamesrich.sofaking.resources.guide_search
import dev.jamesrich.sofaking.resources.on_later
import dev.jamesrich.sofaking.ui.TimeFormatter
import dev.jamesrich.sofaking.ui.TvTextField
import dev.jamesrich.sofaking.ui.formatTemplate
import dev.jamesrich.sofaking.ui.horizontalFadingEdges
import dev.jamesrich.sofaking.ui.theme.SofaChip
import dev.jamesrich.sofaking.ui.theme.tvFocus
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * Visible time range above the channel names, for example "Today 1:00 PM - 2:30 PM" or "Tomorrow 9:00 AM - 10:30 AM".
 * Reads scroll in its own scope.
 */
@Composable
internal fun VisibleRange(
    scrollState: ScrollState,
    viewport: () -> Int,
    minutePx: Float,
    windowStart: Instant,
    now: Instant,
    timeFormat: TimeFormatter,
    todayLabel: String,
    tomorrowLabel: String,
    rangeFormat: String,
) {
    val label by remember(windowStart, now, todayLabel, tomorrowLabel, rangeFormat) {
        derivedStateOf {
            if (viewport() == 0) {
                ""
            } else {
                val (startMinute, endMinute) = GuideTimeline.visibleSpan(
                    scrollPx = scrollState.value.toFloat(),
                    viewportPx = viewport().toFloat(),
                    pxPerMinute = minutePx,
                )
                val start = windowStart + startMinute.minutes
                val end = windowStart + endMinute.minutes
                val day = when (timeFormat.daysBetween(now, start)) {
                    0 -> todayLabel
                    1 -> tomorrowLabel
                    else -> timeFormat.day(start)
                }
                formatTemplate(rangeFormat, day, timeFormat.format(start), timeFormat.format(end))
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
internal fun FilterChipRow(
    filters: List<GuideFilter>,
    counts: Map<GuideFilter, Int>,
    activeFilter: GuideFilter,
    searchOpen: Boolean,
    selectedChip: FocusRequester,
    searchChip: FocusRequester,
    listState: LazyListState,
    onNow: () -> Unit,
    onSearch: () -> Unit,
    onSelect: (GuideFilter) -> Unit,
    modifier: Modifier = Modifier,
    onOnLater: (() -> Unit)? = null,
) {
    LazyRow(
        state = listState,
        modifier = modifier.focusRestorer(selectedChip).horizontalFadingEdges(listState),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "now") {
            SofaChip(selected = false, onClick = onNow, label = stringResource(Res.string.guide_now))
        }
        item(key = "search") {
            SofaChip(
                selected = searchOpen,
                onClick = onSearch,
                label = stringResource(Res.string.guide_search),
                modifier = Modifier.focusRequester(searchChip),
            )
        }
        items(filters, key = { it.toString() }) { option ->
            val selected = activeFilter == option
            SofaChip(
                selected = selected,
                onClick = { onSelect(option) },
                label = stringResource(Res.string.guide_filter_count, option.label(), counts[option] ?: 0),
                modifier = if (selected) Modifier.focusRequester(selectedChip) else Modifier,
            )
        }
        // Last, so the fixed chips before the filters keep their places.
        if (onOnLater != null) {
            item(key = "on-later") {
                SofaChip(selected = false, onClick = onOnLater, label = stringResource(Res.string.on_later))
            }
        }
    }
}

@Composable
internal fun SearchField(
    state: TextFieldState,
    focusRequester: FocusRequester,
    hint: String,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TvTextField(
        state = state,
        focusRequester = focusRequester,
        imeAction = ImeAction.Done,
        onAction = onDone,
        modifier = modifier,
        hint = hint,
    )
}

/**
 * One horizontally scrolled strip; the header and every row share [scrollState] so columns stay aligned. The now
 * line runs behind the strip's content, so it shows between cells and under the time labels but never cuts a title.
 */
@Composable
internal fun TimelineStrip(
    scrollState: ScrollState,
    nowMinute: Long?,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    // Focus must not scroll the strip: the default bring-into-view would chase a long cell's far end
    // (hours ahead) on a vertical move. GuideScreen scrolls the timeline itself in revealMinute.
    CompositionLocalProvider(LocalBringIntoViewSpec provides NoBringIntoView) {
        TimelineStripContent(scrollState, nowMinute, modifier, content)
    }
}

/**
 * Focus never scrolls the guide: the timeline and the channel list are moved by GuideScreen itself (revealMinute,
 * focusRow). A TV's default would chase a long cell's far end, and pivot the focused row a third of the way down,
 * leaving a sliver of the row above under the time header.
 */
internal val NoBringIntoView = object : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = 0f
}

@Composable
private fun TimelineStripContent(
    scrollState: ScrollState,
    nowMinute: Long?,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(modifier.clipToBounds().horizontalScroll(scrollState, overscrollEffect = null)) {
        NowLine(nowMinute)
        content()
    }
}

@Composable
private fun NowLine(nowMinute: Long?) {
    if (nowMinute == null) return
    Box(
        Modifier
            .offset(x = nowOffset(nowMinute))
            .fillMaxHeight()
            .width(2.dp)
            .background(MaterialTheme.colorScheme.primary),
    )
}

@Composable
internal fun GuideFilter.label(): String = when (this) {
    GuideFilter.All -> stringResource(Res.string.filter_all)

    GuideFilter.Favorites -> stringResource(Res.string.filter_favorites)

    GuideFilter.Recent -> stringResource(Res.string.filter_recent)

    is GuideFilter.Group -> name

    is GuideFilter.OnNow -> stringResource(
        when (kind) {
            ProgramKind.MOVIE -> Res.string.filter_movies_now
            ProgramKind.SPORTS -> Res.string.filter_sports_now
            ProgramKind.KIDS -> Res.string.filter_kids_now
            ProgramKind.NEWS -> Res.string.filter_news_now
        },
    )
}
