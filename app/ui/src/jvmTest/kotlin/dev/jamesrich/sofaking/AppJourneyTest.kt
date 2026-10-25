package dev.jamesrich.sofaking

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse

/**
 * Walks of the whole app on the remote, from the focus items of the checks on a TV, against a [FakeServer]. Each one
 * checks where focus lands after each key, so a change that loses the guide's place or traps focus fails here before
 * it reaches a TV. A failure's screen and semantics tree go to build/journeys.
 */
class AppJourneyTest {
    @Test
    fun `Back climbs from the grid to the chips and the Live tab and Down returns to the cell`() = journey(tv = true) {
        val cell = guideCell()
        keys("Back")
        assertFocused(ALL_CHIP)
        keys("Back")
        assertFocused("Live")
        keys("Down")
        assertFocused(ALL_CHIP)
        keys("Down")
        assertFocused(cell)
    }

    @Test
    fun `Info opens the program details on Watch and Back returns to the cell`() = journey(tv = true) {
        val cell = guideCell()
        keys("Info")
        assertFocused("Watch")
        keys("Back")
        assertFocused(cell)
    }

    @Test
    fun `Info on the guide chips leaves focus there`() = journey(tv = true) {
        guideCell()
        keys("Back", "Right", "Right", "Info")
        assertFocused("Recent 0")
    }

    @Test
    fun `Menu from Recordings opens the guide on its empty filter with the chip focused`() = journey(tv = true) {
        guideCell()
        keys("Back", "Right", "OK")
        assertFocused(FAVORITES_CHIP)
        keys("Back", "Right", "Right", "OK")
        assertFocused("Play")
        keys("Menu")
        assertFocused(FAVORITES_CHIP)
    }

    @Test
    fun `a held OK opens the program details on Watch without playing the channel`() = journey(tv = true) {
        val cell = guideCell()
        keys("HeldOK")
        assertFocused("Watch")
        assertFalse(server.watching, "the held OK played the channel")
        keys("Back")
        assertFocused(cell)
    }

    @Test
    fun `the Library tab opens with its tab focused and Back steps out to Live and the guide`() = journey(tv = true) {
        val cell = guideCell()
        keys("Back", "Back", "Right", "OK")
        assertFocused("Library")
        keys("Down")
        assertFocused("Search")
        keys("Down")
        assertFocused(CONTINUE_CARD)
        keys("OK")
        assertFocused("Play")
        keys("Back")
        assertFocused(CONTINUE_CARD)
        keys("Back")
        assertFocused("Library")
        keys("Back")
        assertFocused("Live")
        keys("Down")
        assertFocused(cell)
    }

    @Test
    fun `a held OK on a Library card opens its menu and the next OK opens the card on the TV`() = journey(tv = true) {
        guideCell()
        keys("Back", "Back", "Right", "OK", "Down", "Down", "Down")
        heldOkOpensCardMenu(back = "Back")
    }

    @Test
    fun `a held OK on a Library card opens its menu and the next OK opens the card on a desktop`() =
        journey(tv = false) {
            guideCell()
            keys("Up", "Up", "Down", "OK", "Right")
            // Right from the rail lands on the nearest row, which moves with the text size; step down to the movies.
            repeat(MAX_ROWS_TO_MOVIES) { if (focusedLabel() != MOVIE_CARD) keys("Down") }
            heldOkOpensCardMenu(back = "Escape")
        }

    @Test
    fun `Down from the guide chips returns to the grid on a desktop`() = journey(tv = false) {
        val cell = guideCell()
        keys("Up")
        assertFocused(ALL_CHIP)
        keys("Down")
        assertFocused(cell)
    }

    @Test
    fun `an empty DVR tab keeps focus on its tab`() = journey(tv = true, FakeServer(recordings = false)) {
        guideCell()
        keys("Back", "Back", "Right", "Right")
        assertFocused("DVR")
        keys("OK")
        assertFocused("DVR")
    }

    @Test
    fun `Settings opens on its first page and Back returns through it to its button`() = journey(tv = true) {
        guideCell()
        keys("Back", "Back", "Right", "Right", "Right")
        assertFocused("Settings")
        keys("OK")
        assertFocused("the Live TV row") { it?.startsWith("Live TV") == true }
        keys("OK")
        assertFocused("Where I left off")
        keys("Back")
        assertFocused("the Live TV row") { it?.startsWith("Live TV") == true }
        keys("Back")
        assertFocused("Settings")
    }

    /** From a focused movie card: the menu opens on Mark watched, [back] returns to the card, and OK opens its page. */
    private fun AppDriver.heldOkOpensCardMenu(back: String) {
        assertFocused(MOVIE_CARD)
        keys("HeldOK")
        assertFocused("Mark watched")
        keys(back)
        assertFocused(MOVIE_CARD)
        keys("OK")
        assertFocused("Play")
    }

    /** Waits for the guide to focus a program, and returns its label. */
    private fun AppDriver.guideCell(): String {
        assertFocused("a guide cell") { it?.startsWith("Show at") == true }
        return checkNotNull(focusedLabel())
    }

    private fun journey(tv: Boolean, server: FakeServer = FakeServer(), block: AppDriver.() -> Unit) =
        runSofaKing(tv, File("build/journeys", if (tv) "tv" else "desktop"), server, block)

    private companion object {
        const val ALL_CHIP = "All 40"
        const val FAVORITES_CHIP = "Favorites 0"
        const val CONTINUE_CARD = "Show 1, S1 E2 · Episode 2"
        const val MOVIE_CARD = "Movie 1, 1990"
        const val MAX_ROWS_TO_MOVIES = 3
    }
}
