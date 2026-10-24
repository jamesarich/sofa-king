package dev.jamesrich.sofaking.ui.guide

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import dev.jamesrich.sofaking.data.Lineup
import dev.jamesrich.sofaking.data.Program
import dev.jamesrich.sofaking.resources.Res
import dev.jamesrich.sofaking.resources.guide_search_voice_prompt
import dev.jamesrich.sofaking.ui.FIRST_FOCUS_FRAMES
import dev.jamesrich.sofaking.ui.rememberVoiceSearch
import dev.jamesrich.sofaking.ui.requestFocusWhenAttached
import org.jetbrains.compose.resources.stringResource

/**
 * What moves focus in the grid after the first focus: a jump no row answers, the guide filling in under the focused
 * cell, a filter change, a typed channel number and search.
 */
@Composable
internal fun GuideFocusEffects(
    state: GuideState,
    nav: GuideNavigator,
    channels: GuideChannels,
    span: GuideSpan,
    lineup: Lineup,
) {
    JumpTimeout(state)
    RefocusOnGuideFill(state, nav, span, lineup.guide)
    FilterChangeScroll(state, nav, channels)
    NumberCommit(state, nav, channels, lineup)
    SearchFocus(state, nav)
}

/**
 * Only a composed row answers a jump; one whose row left the list (the lineup refreshing under the first focus) would
 * wait forever, so it counts as missed and the first focus scrolls to the row again.
 */
@Composable
private fun JumpTimeout(state: GuideState) {
    LaunchedEffect(state.jump) {
        val waiting = state.jump ?: return@LaunchedEffect
        repeat(FIRST_FOCUS_FRAMES * 2) { withFrameNanos { } }
        if (state.jump == waiting) state.jumpHandled(false)
    }
}

/**
 * The guide fills in after the channels, so the focused cell's programs change under it without a focus event: the
 * band is refreshed from the new row, and focus is put back on the cell at the cursor.
 */
@Composable
private fun RefocusOnGuideFill(
    state: GuideState,
    nav: GuideNavigator,
    span: GuideSpan,
    guide: Map<String, List<Program>>,
) {
    LaunchedEffect(guide) {
        val current = state.focused ?: return@LaunchedEffect
        val cells = GuideTimeline.cellsFor(guide[current.channel.guideId].orEmpty(), span.start, span.end)
        val cell = GuideTimeline.cellAfterUpdate(cells, current.cell, state.cursor) ?: return@LaunchedEffect
        if (cell != current.cell) state.focused = FocusedCell(current.channel, cell)
        // A row move already requested keeps its jump.
        if (state.gridFocused && state.pendingRow == null) nav.requestJump(current.channel.guideId, state.cursor)
    }
}

/**
 * A filter change starts the list at the focused channel, or at the top when the filter hides it, rather than at the
 * old filter's scroll index. A number typed for a hidden channel switches to All and moves to its row here once the
 * list has changed. Skipped until the first focus has landed, which places the list itself.
 */
@Composable
private fun FilterChangeScroll(state: GuideState, nav: GuideNavigator, channels: GuideChannels) {
    var listedFilter by remember { mutableStateOf(channels.active) }
    LaunchedEffect(channels.active, state.numberJump) {
        if (listedFilter == channels.active && state.numberJump == null) return@LaunchedEffect
        listedFilter = channels.active
        if (!state.initialFocusDone) return@LaunchedEffect
        val visible = channels.visible
        val jumpRow = state.numberJump?.let { id -> visible.indexOfFirst { it.guideId == id } } ?: -1
        state.numberJump = null
        if (jumpRow >= 0) {
            nav.focusRow(rowIndexOf(channels.items, visible[jumpRow]), channels)
        } else {
            nav.listState.scrollToItem(
                firstItemAfterFilter(channels.items.map { it.channel }, state.focused?.channel?.guideId),
            )
        }
    }
}

/** Typed channel digits, as the player takes them. OK or the entry timeout commits them. */
@Composable
private fun NumberCommit(state: GuideState, nav: GuideNavigator, channels: GuideChannels, lineup: Lineup) {
    SideEffect {
        state.numbers.onCommit = { pending ->
            state.commitNumber(pending, channels.visible, lineup.channels) { channel ->
                nav.focusRow(rowIndexOf(channels.items, channel), channels)
            }
        }
    }
}

/**
 * Search's focus: the field when search opens, the first matching row after a voice search (the field again when no
 * row matched), and the Search chip once search closes.
 */
@Composable
private fun SearchFocus(state: GuideState, nav: GuideNavigator) {
    LaunchedEffect(state.searchOpen, state.searchFocusRequest) {
        if (!state.searchOpen) return@LaunchedEffect
        if (state.focusResults) {
            state.focusResults = false
            // No row matched: the field takes focus so the query can be changed.
            if (requestFocusWhenAttached(nav.initialFocus, FIRST_FOCUS_FRAMES)) return@LaunchedEffect
        }
        requestFocusWhenAttached(nav.searchField)
    }
    LaunchedEffect(state.returnToSearchChip) {
        if (state.returnToSearchChip) {
            requestFocusWhenAttached(nav.searchChip)
            state.returnToSearchChip = false
        }
    }
}

/**
 * Opens search by voice, or the text field where there is no recognizer; on arrival too when [startSearch].
 * The TV's recognizer takes the query and the first matching row takes focus.
 */
@Composable
internal fun rememberOpenSearch(state: GuideState, startSearch: Boolean): () -> Unit {
    val startVoiceSearch = rememberVoiceSearch(stringResource(Res.string.guide_search_voice_prompt)) { spoken ->
        state.voiceSearched(spoken)
    }
    val open: () -> Unit = { if (!startVoiceSearch()) state.openSearchField() }
    LaunchedEffect(Unit) { if (startSearch) open() }
    return open
}
