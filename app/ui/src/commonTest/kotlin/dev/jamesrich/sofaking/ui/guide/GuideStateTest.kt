package dev.jamesrich.sofaking.ui.guide

import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import dev.jamesrich.sofaking.data.Channel
import dev.jamesrich.sofaking.data.Programme
import dev.jamesrich.sofaking.ui.player.ChannelNumberEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class GuideStateTest {
    private fun channel(id: String) = Channel(id, id.toDouble(), "Ch $id", null, listOf("Test"))
    private val channels = listOf(channel("1"), channel("2"), channel("3"))
    private val items = guideListItems(channels, emptyMap())
    private val base = Instant.parse("2026-10-09T10:00:00Z")
    private fun cell(start: Long, end: Long) = GuideCell(
        Programme(channelId = "1", start = base + start.minutes, stop = base + end.minutes, title = "Show", description = null),
        start,
        end,
    )

    /** Records what the keys asked of focus, scrolling and the preview. */
    private class Controls : GuideControls {
        val calls = mutableListOf<String>()
        override fun climbBack() { calls += "climb" }
        override fun restartRest() { calls += "rest" }
        override fun now() { calls += "now" }
        override fun search() { calls += "search" }
        override fun closeDetails() { calls += "closeDetails" }
        override fun dayJump(shortcut: GuideShortcut) { calls += "day $shortcut" }
    }

    private val controls = Controls()
    private val state = GuideState()

    private fun down(key: Key, back: GuideBack = GuideBack.FOCUS_CHIPS) = state.onKeyEvent(key, KeyEventType.KeyDown, back, controls)

    private fun up(key: Key, back: GuideBack = GuideBack.FOCUS_CHIPS) = state.onKeyEvent(key, KeyEventType.KeyUp, back, controls)

    @Test
    fun `Back is held on key-down and climbs on its key-up`() {
        assertTrue(down(Key.Back))
        assertTrue(controls.calls.isEmpty())
        assertTrue(up(Key.Back))
        assertEquals(listOf("climb"), controls.calls)
        // Another key-up without its own key-down does not climb again.
        assertFalse(up(Key.Back))
        assertEquals(listOf("climb"), controls.calls)
    }

    @Test
    fun `Back with nowhere to climb is left to the activity`() {
        assertFalse(down(Key.Back, GuideBack.EXIT))
        assertFalse(up(Key.Back, GuideBack.EXIT))
        assertFalse("climb" in controls.calls)
    }

    @Test
    fun `the open details take their own Back`() {
        state.details = ProgrammeDetails(channels[0], null)
        assertFalse(up(Key.Back))
        assertFalse("climb" in controls.calls)
    }

    @Test
    fun `every key-down restarts the preview's rest and a key-up does not`() {
        down(Key.DirectionDown)
        up(Key.DirectionDown)
        assertEquals(listOf("rest"), controls.calls)
    }

    @Test
    fun `digits and OK commit on OK's key-up`() {
        val committed = mutableListOf<String>()
        state.numbers.onCommit = { committed += it.digits }
        assertTrue(down(Key.Two))
        assertTrue(down(Key.DirectionCenter))
        assertTrue(committed.isEmpty())
        assertTrue(up(Key.DirectionCenter))
        assertEquals(listOf("2"), committed)
    }

    @Test
    fun `digits are search text while search is open`() {
        state.searchOpen = true
        assertFalse(down(Key.Two))
        assertFalse(state.numbers.hasDigits)
    }

    @Test
    fun `the rest of an OK held to open the details goes nowhere`() {
        assertFalse(down(Key.DirectionCenter))
        state.openDetails(channels[0], cell(0, 30))
        assertTrue(down(Key.DirectionCenter))
        assertTrue(up(Key.DirectionCenter))
        // The next press reaches the focused node again.
        assertFalse(down(Key.DirectionCenter))
    }

    @Test
    fun `a held OK opens the details sheet when it comes up and leaves the key-up to the cell`() {
        assertFalse(down(Key.DirectionCenter))
        state.openDetails(channels[0], cell(0, 30), inWindow = true)
        assertNull(state.details)
        assertFalse(down(Key.DirectionCenter))
        assertFalse(up(Key.DirectionCenter))
        assertEquals(ProgrammeDetails(channels[0], cell(0, 30).programme), state.details)
        assertFalse(down(Key.DirectionCenter))
    }

    @Test
    fun `Info opens the details sheet at once`() {
        state.cellFocused(channels[1], cell(0, 30))
        assertTrue(down(Key.Info))
        state.details = null
        state.openDetails(channels[1], cell(0, 30), inWindow = true)
        assertEquals(ProgrammeDetails(channels[1], cell(0, 30).programme), state.details)
    }

    @Test
    fun `Info opens the focused programme's details and closes them`() {
        state.cellFocused(channels[1], cell(0, 30))
        assertTrue(down(Key.Info))
        assertEquals(ProgrammeDetails(channels[1], cell(0, 30).programme), state.details)
        assertTrue(down(Key.Info))
        assertEquals(listOf("rest", "rest", "closeDetails"), controls.calls)
    }

    @Test
    fun `Guide and Search close the details and act`() {
        state.details = ProgrammeDetails(channels[0], null)
        down(Key.Guide)
        assertNull(state.details)
        state.details = ProgrammeDetails(channels[0], null)
        down(Key.Search)
        assertNull(state.details)
        assertEquals(listOf("rest", "now", "rest", "search"), controls.calls)
    }

    @Test
    fun `Fast Forward and Rewind move a day except while searching`() {
        down(Key.MediaFastForward)
        down(Key.MediaRewind)
        state.searchOpen = true
        down(Key.MediaFastForward)
        assertEquals(listOf("rest", "day DAY_FORWARD", "rest", "day DAY_BACK", "rest"), controls.calls)
    }

    @Test
    fun `other keys go on to the focused node`() {
        assertFalse(down(Key.DirectionLeft))
    }

    // onGridKey

    private val moves = mutableListOf<Int>()

    private fun grid(key: Key, type: KeyEventType = KeyEventType.KeyDown) = state.onGridKey(key, type, items, focusRow = { moves += it })

    @Test
    fun `Down moves to the next row under the cursor`() {
        state.cellFocused(channels[0], cell(0, 30))
        assertTrue(grid(Key.DirectionDown))
        assertEquals(listOf(1), moves)
    }

    @Test
    fun `rapid presses count from the row already requested`() {
        state.cellFocused(channels[0], cell(0, 30))
        state.pendingRow = 1
        grid(Key.DirectionDown)
        assertEquals(listOf(2), moves)
    }

    @Test
    fun `Up from the first row leaves the grid for the chips`() {
        state.cellFocused(channels[0], cell(0, 30))
        state.pendingRow = 0
        assertFalse(grid(Key.DirectionUp))
        assertNull(state.pendingRow)
        assertTrue(moves.isEmpty())
    }

    @Test
    fun `a page stops at the last row and stays put there`() {
        state.cellFocused(channels[1], cell(0, 30))
        assertTrue(grid(Key.ChannelDown))
        assertEquals(listOf(2), moves)
        state.cellFocused(channels[2], cell(0, 30))
        assertTrue(grid(Key.PageDown))
        assertEquals(listOf(2), moves)
    }

    @Test
    fun `Left on a row's first cell stays put`() {
        state.cellFocused(channels[0], cell(0, 30))
        assertTrue(grid(Key.DirectionLeft))
        assertFalse(grid(Key.DirectionRight))
    }

    @Test
    fun `a row step's key-up is taken without moving`() {
        state.cellFocused(channels[0], cell(0, 30))
        assertTrue(grid(Key.DirectionDown, KeyEventType.KeyUp))
        assertTrue(moves.isEmpty())
    }

    @Test
    fun `a row step counts from the focused Later line`() {
        val later = guideListItems(channels, mapOf("1" to listOf(cell(60, 90).programme!!)))
        state.cellFocused(channels[0], cell(0, 30))
        state.laterFocused(later[1].key)
        state.onGridKey(Key.DirectionDown, KeyEventType.KeyDown, later, focusRow = { moves += it })
        assertEquals(listOf(2), moves)
    }

    // gridTarget

    @Test
    fun `a row step can leave the grid and a page cannot`() {
        assertEquals(null, gridTarget(0, GridStep(-1, paged = false), 3))
        assertEquals(null, gridTarget(2, GridStep(1, paged = false), 3))
        assertEquals(0, gridTarget(1, GridStep(-GUIDE_PAGE_ROWS, paged = true), 3))
        assertEquals(2, gridTarget(1, GridStep(GUIDE_PAGE_ROWS, paged = true), 3))
        assertEquals(null, gridTarget(-1, GridStep(1, paged = false), 3))
        assertEquals(null, gridTarget(0, GridStep(1, paged = true), 0))
    }

    // Focus and the cursor

    @Test
    fun `a focused cell holds the cursor inside it`() {
        state.cursor = 45
        state.cellFocused(channels[0], cell(30, 60))
        assertEquals(45, state.cursor)
        state.cellFocused(channels[0], cell(60, 90))
        assertEquals(60, state.cursor)
        assertEquals("1", state.lastFocusedGuideId)
        assertTrue(state.initialFocusDone)
    }

    @Test
    fun `a missed jump retries the first focus until it lands`() {
        state.jumpTo("1", 30)
        assertEquals(GuideJump("1", 30, 1), state.jump)
        assertEquals(30, state.cursor)
        state.jumpHandled(landed = false)
        assertNull(state.jump)
        assertEquals(1, state.initialFocusAttempt)
        state.jumpTo("1", 30)
        state.jumpHandled(landed = true)
        assertTrue(state.initialFocusDone)
        state.jumpTo("1", 30)
        state.jumpHandled(landed = false)
        assertEquals(1, state.initialFocusAttempt)
    }

    @Test
    fun `a guide opened for search or in the header waits for its first focus`() {
        assertTrue(GuideState(startSearch = true).initialFocusDone)
        assertTrue(GuideState(startInHeader = true).awaitingEntry)
    }

    @Test
    fun `the window rolls over with the cursor once the clock leaves it`() {
        val live = GuideTimeline.windowStart(base)
        val rolled = GuideState(liveStartSecond = mutableLongStateOf(live.epochSeconds))
        rolled.dayOffset = 2
        rolled.rollWindow(base + 30.minutes)
        assertEquals(live, rolled.liveStart)
        assertEquals(2, rolled.dayOffset)

        val later = base + (GuideTimeline.WINDOW_MINUTES + 15).minutes
        rolled.rollWindow(later)
        assertEquals(GuideTimeline.windowStart(later), rolled.liveStart)
        assertEquals(0, rolled.dayOffset)
        assertEquals(GuideTimeline.offsetMinutes(rolled.liveStart, later), rolled.cursor)
    }

    // Typed numbers

    @Test
    fun `a typed number focuses its row or switches to All for a hidden one`() {
        val focused = mutableListOf<String>()
        state.commitNumber(ChannelNumberEntry("2"), channels, channels) { focused += it.guideId }
        assertEquals(listOf("2"), focused)

        state.filter = GuideFilter.Favorites
        state.commitNumber(ChannelNumberEntry("3"), channels.take(1), channels) { focused += it.guideId }
        assertEquals(GuideFilter.All, state.filter)
        assertEquals("3", state.numberJump)

        state.commitNumber(ChannelNumberEntry("9"), channels, channels) { focused += it.guideId }
        assertEquals("9", state.numbers.miss)
        assertEquals(listOf("2"), focused)
    }

    @Test
    fun `a typed number does nothing while search is open`() {
        state.searchOpen = true
        state.commitNumber(ChannelNumberEntry("9"), channels, channels) { }
        assertNull(state.numbers.miss)
    }

    // Search

    @Test
    fun `closing search clears it and sends focus back to its chip`() {
        state.toggleSearch()
        assertTrue(state.searchOpen)
        state.searchText.setTextAndPlaceCursorAtEnd("news")
        state.toggleSearch()
        assertFalse(state.searchOpen)
        assertEquals("", state.searchText.text.toString())
        assertTrue(state.returnToSearchChip)
    }

    @Test
    fun `spoken words become the query and focus the results`() {
        state.voiceSearched("news")
        assertTrue(state.searchOpen)
        assertEquals("news", state.searchText.text.toString())
        assertTrue(state.focusResults)
        assertEquals(1, state.searchFocusRequest)

        val dismissed = GuideState()
        dismissed.voiceSearched(null)
        assertTrue(dismissed.searchOpen)
        assertFalse(dismissed.focusResults)
    }

    @Test
    fun `a hidden watched channel clears the filter and search`() {
        state.filter = GuideFilter.Favorites
        state.searchOpen = true
        state.searchText.setTextAndPlaceCursorAtEnd("x")
        state.showAll()
        assertEquals(GuideFilter.All, state.filter)
        assertFalse(state.searchOpen)
        assertEquals("", state.searchText.text.toString())
        assertFalse(state.returnToSearchChip)
    }
}
