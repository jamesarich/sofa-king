package dev.jamesrich.sofaking.ui.guide

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import dev.jamesrich.sofaking.data.Channel
import dev.jamesrich.sofaking.ui.FIRST_FOCUS_FRAMES
import dev.jamesrich.sofaking.ui.requestFocusWhenAttached
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * The guide's scroll positions and focus targets, and the moves that scroll and focus them: keeping a minute on
 * screen, jumping to a row's cell under the cursor, a row step, Now, a day jump and Back's climb. One per guide.
 */
@Stable
internal class GuideNavigator(
    private val state: GuideState,
    val scrollState: ScrollState,
    val listState: LazyListState,
    val chipsState: LazyListState,
    private val scope: CoroutineScope,
    density: Density,
) {
    val minutePx = with(density) { MINUTE_WIDTH.toPx() }
    val minStickyTextPx = with(density) { MIN_STICKY_TEXT.toPx() }
    private val marginPx = minutePx * FOCUS_MARGIN_MINUTES

    /** The width of the timeline on screen, in pixels. */
    val viewport = mutableIntStateOf(0)

    val selectedChip = FocusRequester()
    val searchChip = FocusRequester()
    val searchField = FocusRequester()
    val initialFocus = FocusRequester()
    val grid = FocusRequester()

    /** A cell took focus: the cursor moves to it, and the timeline keeps it on screen. */
    val onCellFocus: (Channel, GuideCell) -> Unit = { channel, cell ->
        state.cellFocused(channel, cell)
        revealMinute(state.cursor)
    }

    /**
     * Scrolls the timeline so [minute] is inside the visible strip, leaving a half hour of room on the left. Kept
     * after focus leaves so the preview holds the last program while focus is on the chips.
     */
    fun revealMinute(minute: Long) {
        val px = minute * minutePx
        val viewStart = scrollState.value.toFloat()
        if (px < viewStart + marginPx || px > viewStart + viewport.intValue - marginPx) {
            scope.launch { scrollState.animateScrollTo((px - marginPx).roundToInt().coerceAtLeast(0)) }
        }
    }

    /** Asks [guideId]'s row to focus its cell at [minute]; the row reports through onJumpFinish whether it did. */
    fun requestJump(guideId: String, minute: Long) {
        state.jumpTo(guideId, minute)
        revealMinute(minute)
    }

    /**
     * Scrolls the list so item [target] holds the second slot and focuses its cell under the time cursor, or a Later
     * line's first chip. The channels scroll under the focused row, as a cable guide does; the list clamps at either
     * end.
     */
    fun focusRow(target: Int, channels: GuideChannels) {
        state.pendingRow = target
        scope.launch {
            listState.scrollToItem((target - FOCUS_ROW_SLOT).coerceAtLeast(0))
            when (val item = channels.items[target]) {
                is GuideListItem.Row -> requestJump(item.channel.guideId, state.cursor)

                is GuideListItem.Later -> {
                    channels.laterFocus[item.key]?.let { requestFocusWhenAttached(it, FIRST_FOCUS_FRAMES) }
                    state.pendingRow = null
                }
            }
        }
    }

    /** The focused channel, which the chips keep, else the first row on screen. */
    fun anchorGuideId(items: List<GuideListItem>): String? =
        state.focused?.channel?.guideId ?: items.getOrNull(listState.firstVisibleItemIndex)?.channel?.guideId

    /** Now: the anchor row's program at [at]; a day ahead comes back to the live window. */
    fun now(items: List<GuideListItem>, at: Instant) {
        val guideId = anchorGuideId(items)
        state.dayOffset = 0
        if (guideId != null) requestJump(guideId, GuideTimeline.offsetMinutes(state.liveStart, at))
    }

    /**
     * Fast Forward and Rewind move the grid a whole day, keeping the time of day and the focused channel; a day
     * beyond the live one is fetched on first visit ([onLoadGuideWindow]) and its cells fill in as it arrives.
     */
    fun dayJump(shortcut: GuideShortcut, onLoadGuideWindow: (Instant) -> Unit) {
        val next = dayOffsetAfter(state.dayOffset, shortcut)
        if (next == state.dayOffset) return
        state.dayOffset = next
        if (next > 0) onLoadGuideWindow(state.liveStart + (next * GuideTimeline.WINDOW_MINUTES).minutes)
        refocus()
    }

    /** The details close, by Back or Close, and focus returns to the program's cell. */
    fun closeDetails() {
        state.details = null
        refocus()
    }

    /** Focus goes back to the focused channel's cell under the cursor. */
    fun refocus() {
        state.focused?.channel?.guideId?.let { requestJump(it, state.cursor) }
    }

    /**
     * Back's climb, as [action] decided it: from the grid to the selected chip (scrolled into the row first, since
     * only a composed chip can take focus), from the chips to the header's open tab ([headerFocus]).
     */
    fun climb(action: GuideBack, channels: GuideChannels, headerFocus: FocusRequester?) {
        when (action) {
            GuideBack.CLOSE_SEARCH -> state.closeSearch()
            GuideBack.FOCUS_CHIPS -> scope.launch { focusSelectedChip(channels) }
            GuideBack.FOCUS_HEADER -> headerFocus?.requestFocus()
            GuideBack.EXIT -> Unit
        }
    }

    /** Scrolls the selected chip into the row and focuses it. */
    suspend fun focusSelectedChip(channels: GuideChannels) {
        chipsState.scrollToItem(selectedChipIndex(channels.filters, channels.active))
        requestFocusWhenAttached(selectedChip)
    }
}

@Composable
internal fun rememberGuideNavigator(state: GuideState): GuideNavigator {
    val scrollState = rememberScrollState()
    val listState = rememberLazyListState()
    val chipsState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    return remember(state, scrollState, listState, chipsState, scope, density) {
        GuideNavigator(state, scrollState, listState, chipsState, scope, density)
    }
}
