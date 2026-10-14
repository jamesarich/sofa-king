
package dev.jamesrich.sofaking.ui.guide

import androidx.compose.foundation.ExperimentalFoundationApi
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
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import dev.jamesrich.sofaking.resources.*
import dev.jamesrich.sofaking.ui.TimeFormatter
import dev.jamesrich.sofaking.ui.TvTextField
import dev.jamesrich.sofaking.ui.formatTemplate
import dev.jamesrich.sofaking.ui.theme.tvFocus
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** Visible time range above the channel names, for example "Today 1:00 PM - 2:30 PM". Reads scroll in its own scope. */
@Composable
internal fun VisibleRange(
    scrollState: ScrollState,
    viewport: MutableIntState,
    minutePx: Float,
    windowStart: Instant,
    now: Instant,
    timeFormat: TimeFormatter,
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
                val start = windowStart + startMinute.minutes
                val end = windowStart + endMinute.minutes
                val day = if (timeFormat.sameDay(start, now)) todayLabel else timeFormat.day(start)
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
    modifier: Modifier,
) {
    LazyRow(
        state = listState,
        modifier = modifier.focusRestorer(selectedChip),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "now") {
            GuideChip(selected = false, onClick = onNow, label = stringResource(Res.string.guide_now))
        }
        item(key = "search") {
            GuideChip(
                selected = searchOpen,
                onClick = onSearch,
                label = stringResource(Res.string.guide_search),
                modifier = Modifier.focusRequester(searchChip),
            )
        }
        items(filters, key = { it.toString() }) { option ->
            val selected = activeFilter == option
            GuideChip(
                selected = selected,
                onClick = { onSelect(option) },
                label = stringResource(Res.string.guide_filter_count, option.label(), counts[option] ?: 0),
                modifier = if (selected) Modifier.focusRequester(selectedChip) else Modifier,
            )
        }
    }
}

/** A Material 3 filter chip in the guide's label type, with the D-pad focus cue. */
@Composable
private fun GuideChip(selected: Boolean, onClick: () -> Unit, label: String, modifier: Modifier = Modifier) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, style = MaterialTheme.typography.labelLarge) },
        modifier = modifier.tvFocus(FilterChipDefaults.shape),
    )
}

@Composable
internal fun SearchField(
    state: TextFieldState,
    focusRequester: FocusRequester,
    hint: String,
    onDone: () -> Unit,
    modifier: Modifier,
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

/** One horizontally scrolled strip; the header and every row share [scrollState] so columns stay aligned. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun TimelineStrip(
    scrollState: ScrollState,
    nowMinute: Long?,
    modifier: Modifier,
    content: @Composable () -> Unit,
) {
    // Focus must not scroll the strip: the default bring-into-view would chase a long cell's far end
    // (hours ahead) on a vertical move. GuideScreen scrolls the timeline itself in revealMinute.
    CompositionLocalProvider(LocalBringIntoViewSpec provides NoBringIntoView) {
        TimelineStripContent(scrollState, nowMinute, modifier, content)
    }
}

@OptIn(ExperimentalFoundationApi::class)
private val NoBringIntoView = object : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = 0f
}

@Composable
private fun TimelineStripContent(
    scrollState: ScrollState,
    nowMinute: Long?,
    modifier: Modifier,
    content: @Composable () -> Unit,
) {
    Box(modifier.clipToBounds().horizontalScroll(scrollState, overscrollEffect = null)) {
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

@Composable
internal fun GuideFilter.label(): String = when (this) {
    GuideFilter.All -> stringResource(Res.string.filter_all)
    GuideFilter.Favorites -> stringResource(Res.string.filter_favorites)
    is GuideFilter.Group -> name
}
