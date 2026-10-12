package dev.jamesrich.sofaking.ui.guide

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
    fun `matches the airing programme title`() {
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
}
