package dev.jamesrich.sofaking.ui.guide

import dev.jamesrich.sofaking.data.Program
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.Test
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

class GuideSearchTest {
    @Test
    fun `blank query matches everything`() {
        assertTrue(GuideSearch.matches("", 7.0, "Name", null))
        assertTrue(GuideSearch.matches("   ", 7.0, "Name", null))
    }

    @Test
    fun `matches channel name case-insensitively`() {
        assertTrue(GuideSearch.matches("nEws", 7.0, "Local News", null))
        assertFalse(GuideSearch.matches("sports", 7.0, "Local News", null))
    }

    @Test
    fun `matches channel number by its start`() {
        assertTrue(GuideSearch.matches("5", 58.0, "Name", null))
        assertTrue(GuideSearch.matches("58", 58.0, "Name", null))
        assertFalse(GuideSearch.matches("8", 58.0, "Name", null))
    }

    @Test
    fun `matches the airing program title`() {
        assertTrue(GuideSearch.matches("jeopardy", 7.0, "Name", "Jeopardy!"))
        assertFalse(GuideSearch.matches("jeopardy", 7.0, "Name", "Evening News"))
        assertFalse(GuideSearch.matches("jeopardy", 7.0, "Name", null))
    }

    @Test
    fun `ignores accents on either side`() {
        assertTrue(GuideSearch.matches("tele", 7.0, "Télé Québec", null))
        assertTrue(GuideSearch.matches("TÉLÉ", 7.0, "Tele Quebec", null))
        assertTrue(GuideSearch.matches("café", 7.0, "Name", "Le Cafe"))
    }

    @Test
    fun `normalize strips combining marks and lowercases`() {
        assertTrue(GuideSearch.normalize("Crème Brûlée") == "creme brulee")
    }

    @Test
    fun `query is trimmed before matching`() {
        assertTrue(GuideSearch.matches("  news  ", 7.0, "Local News", null))
    }

    @Test
    fun `decomposed accents fold the same as precomposed ones`() {
        assertTrue(GuideSearch.normalize("Télé") == "tele")
        assertTrue(GuideSearch.matches("tele", 7.0, "Télé Québec", null))
    }

    private val now = Instant.parse("2026-10-12T18:00:00Z")
    private fun show(title: String, startHours: Int, subTitle: String? = null) =
        Program("c", now + startHours.hours, now + (startHours + 1).hours, title, null, subTitle = subTitle)

    @Test
    fun `upcoming matches titles and episode titles after now in start order`() {
        val programs = listOf(show("Jeopardy!", 4), show("News", 1, subTitle = "Jeopardy champions"), show("Jeopardy!", 0), show("Jeopardy!", -1))
        val later = GuideSearch.upcoming("jeopardy", programs, after = now, before = now + 24.hours)
        assertEquals(listOf(1, 4), later.map { ((it.start - now).inWholeHours).toInt() })
    }

    @Test
    fun `upcoming stops at the window and at three matches`() {
        val programs = (1..6).map { show("Jeopardy!", it) }
        assertEquals(GuideSearch.MAX_UPCOMING, GuideSearch.upcoming("jeopardy", programs, now, now + 24.hours).size)
        assertEquals(2, GuideSearch.upcoming("jeopardy", programs, now, now + 3.hours).size)
        assertTrue(GuideSearch.upcoming("", programs, now, now + 24.hours).isEmpty())
    }
}
