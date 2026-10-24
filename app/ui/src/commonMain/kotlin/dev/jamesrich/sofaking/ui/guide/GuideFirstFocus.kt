package dev.jamesrich.sofaking.ui.guide

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import dev.jamesrich.sofaking.data.Channel
import dev.jamesrich.sofaking.ui.FIRST_FOCUS_FRAMES
import dev.jamesrich.sofaking.ui.requestFocusWhenAttached

/** How often the initial focus is retried after the row refused it, before the header keeps focus. */
private const val INITIAL_FOCUS_ATTEMPTS = 3

/**
 * The guide's first focus: the cell under the cursor on the channel [arrival] names, else the one focused last or
 * watched last; the selected chip when the filter lists nothing. It waits while focus is left in the header, and is
 * retried while the row refuses it. [all] is the whole lineup, which a filter hiding the returned-from channel gives
 * way to.
 */
@Composable
internal fun GuideFirstFocus(
    state: GuideState,
    nav: GuideNavigator,
    channels: GuideChannels,
    span: GuideSpan,
    arrival: GuideArrival,
    all: List<Channel>,
) {
    LaunchedEffect(
        channels.visible.isNotEmpty(),
        channels.active,
        state.searchOpen,
        state.initialFocusAttempt,
        state.awaitingEntry,
        state.dayOffset,
    ) {
        if (firstFocusDue(state) && firstFocusReady(state, nav, channels, arrival, all)) {
            focusFirstRow(state, nav, channels, span, arrival)
        }
    }
}

private fun firstFocusDue(state: GuideState): Boolean =
    !state.awaitingEntry && !state.initialFocusDone && state.initialFocusAttempt < INITIAL_FOCUS_ATTEMPTS

/** Whether a row can take the first focus now; false when the guide changed to rerun it, or the chips took it. */
private suspend fun firstFocusReady(
    state: GuideState,
    nav: GuideNavigator,
    channels: GuideChannels,
    arrival: GuideArrival,
    all: List<Channel>,
): Boolean {
    when {
        // Now is in today's window, whatever day the guide was left on; this reruns on it.
        arrival.startAtNow && state.dayOffset != 0 -> state.dayOffset = 0

        // Reruns with the full lineup on screen.
        watchedIsHidden(arrival.returnedFrom, channels.visible, all) -> state.showAll()

        // A filter with no channels (no favorites yet): the selected chip holds focus until it has some.
        channels.visible.isEmpty() -> nav.focusSelectedChip(channels)

        else -> return true
    }
    return false
}

private suspend fun focusFirstRow(
    state: GuideState,
    nav: GuideNavigator,
    channels: GuideChannels,
    span: GuideSpan,
    arrival: GuideArrival,
) {
    val nowAt = span.nowMinute ?: span.nowClamped
    if (arrival.startAtNow) {
        state.cursor = nowAt
    } else if (arrival.returnedFrom != null) {
        state.cursor = cursorOnReturn(state.cursor, nowAt)
    }
    val visible = channels.visible
    val row = initialGuideRow(visible, arrival.returnedFrom, state.lastFocusedGuideId, arrival.initialChannelId)
    if (row >= 0) {
        // Before the first measure the layout info is empty; a restored scroll position must not be thrown away.
        withFrameNanos { }
        val item = rowIndexOf(channels.items, visible[row])
        if (nav.listState.layoutInfo.visibleItemsInfo.none { it.index == item }) nav.listState.scrollToItem(item)
        // The row reports through onJumpFinish whether its cell took focus.
        nav.requestJump(visible[row].guideId, state.cursor)
    } else if (!requestFocusWhenAttached(nav.initialFocus, FIRST_FOCUS_FRAMES)) {
        state.initialFocusAttempt++
    }
}
