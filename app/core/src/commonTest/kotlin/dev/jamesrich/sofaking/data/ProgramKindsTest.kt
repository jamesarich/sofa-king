package dev.jamesrich.sofaking.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class ProgramKindsTest {
    @Test
    fun `categories in any guide's words name their kinds`() {
        assertEquals(setOf(ProgramKind.MOVIE), programKinds(listOf("Movies")))
        assertEquals(setOf(ProgramKind.MOVIE), programKinds(listOf("Feature Film")))
        assertEquals(setOf(ProgramKind.SPORTS), programKinds(listOf("Sports event", "Football")))
        assertEquals(setOf(ProgramKind.KIDS, ProgramKind.MOVIE), programKinds(listOf("Children", "Movie")))
        assertEquals(setOf(ProgramKind.NEWS), programKinds(listOf("News")))
        assertEquals(emptySet(), programKinds(listOf("Drama", "Reality")))
    }

    @Test
    fun `equal kinds share one set`() {
        assertSame(programKinds(listOf("Movies")), programKinds(listOf("Film")))
        assertEquals(setOf(ProgramKind.KIDS, ProgramKind.NEWS), kindsOf(setOf(ProgramKind.NEWS, ProgramKind.KIDS).mask()))
    }
}
