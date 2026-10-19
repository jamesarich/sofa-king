package dev.jamesrich.sofaking.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class ProgramKindsTest {
    @Test
    fun `categories in any guide's words name their kinds`() {
        assertEquals(setOf(ProgramKind.MOVIE), programKinds(listOf("Movie", "Thriller")))
        assertEquals(setOf(ProgramKind.MOVIE), programKinds(listOf("Movies")))
        assertEquals(setOf(ProgramKind.SPORTS), programKinds(listOf("Sports event", "Football")))
        assertEquals(setOf(ProgramKind.KIDS, ProgramKind.MOVIE), programKinds(listOf("Children", "Movie", "Animation")))
        assertEquals(setOf(ProgramKind.NEWS), programKinds(listOf("News")))
        assertEquals(emptySet(), programKinds(listOf("Drama", "Reality")))
    }

    @Test
    fun `a newscast or a sports program marked Movie is not a movie`() {
        assertEquals(setOf(ProgramKind.NEWS), programKinds(listOf("Movie", "News")))
        assertEquals(setOf(ProgramKind.SPORTS), programKinds(listOf("Movie", "Sports")))
        assertEquals(setOf(ProgramKind.MOVIE, ProgramKind.KIDS), programKinds(listOf("Movie", "Kids + Family")))
    }

    @Test
    fun `a Movie mark beside a feed's heading alone is not a movie`() {
        assertEquals(emptySet(), programKinds(listOf("Movie")))
        assertEquals(emptySet(), programKinds(listOf("Movie", "Music")))
        assertEquals(emptySet(), programKinds(listOf("Movie", "En Español")))
        assertEquals(emptySet(), programKinds(listOf("Movie", "Classic tv")))
    }

    @Test
    fun `equal kinds share one set`() {
        assertSame(programKinds(listOf("Movies")), programKinds(listOf("Feature Film")))
        assertEquals(setOf(ProgramKind.KIDS, ProgramKind.NEWS), kindsOf(setOf(ProgramKind.NEWS, ProgramKind.KIDS).mask()))
    }
}
