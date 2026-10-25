package dev.jamesrich.sofaking.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class TeamSidesTest {
    private fun program(title: String, subTitle: String? = null, sports: Boolean = true) = Program(
        "c1",
        Instant.parse("2026-10-25T18:00:00Z"),
        Instant.parse("2026-10-25T21:00:00Z"),
        title,
        null,
        subTitle = subTitle,
        kinds = if (sports) setOf(ProgramKind.SPORTS) else emptySet(),
    )

    @Test
    fun `a sports program names its two sides in the episode title or after its heading`() {
        assertEquals(listOf("Bears", "Packers"), teamSides(program("NFL Football", "Bears at Packers")))
        assertEquals(listOf("Lakers", "Celtics"), teamSides(program("NBA Basketball: Lakers vs. Celtics")))
        assertEquals(listOf("Cubs", "Cardinals"), teamSides(program("Cubs @ Cardinals")))
    }

    @Test
    fun `a program that is not sports or names no two sides has none`() {
        assertEquals(emptyList(), teamSides(program("Bears at Packers", sports = false)))
        assertEquals(emptyList(), teamSides(program("SportsCenter")))
        assertEquals(emptyList(), teamSides(program("Postgame", "A look at what went right for the team this season")))
    }
}
