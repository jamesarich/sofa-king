package dev.jamesrich.sofaking.backend.catalog

import dev.jamesrich.sofaking.backend.api.LineupPolicy
import dev.jamesrich.sofaking.backend.api.PolicyGuideMatches
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GuideMatchesTest {
    private val channels = listOf(
        GuideChannel("a1", "ESPN 2 FHD"),
        GuideChannel("a2", "The History Channel"),
        GuideChannel("a3", "Comedy Central"),
        GuideChannel("CNN.us", "Cable News"),
    )

    private fun matcher(settings: PolicyGuideMatches = PolicyGuideMatches()) =
        GuideNameMatcher(settings, mapOf("one" to channels, "two" to listOf(GuideChannel("b1", "History"))))

    @Test fun `ignored prefixes and suffixes come off both names before they compare`() {
        val plain = matcher().matches("US: ESPN2")
        assertTrue(plain.none { it.confidence == 1.0 }, "without the lists the names differ")
        val cleaned = matcher(PolicyGuideMatches(ignorePrefixes = listOf("us:"), ignoreSuffixes = listOf("FHD")))
        val best = cleaned.matches("US: ESPN2").first()
        assertEquals("a1", best.channel.id)
        assertEquals(1.0, best.confidence)
    }

    @Test fun `loose names match exactly and the ID before its dot is a second name`() {
        val history = matcher().matches("History")
        assertEquals(listOf("a2" to 1.0, "b1" to 1.0), history.map { it.channel.id to it.confidence })
        assertEquals("one", history.first().guideId, "a tie lists the guides in configuration order")
        assertEquals("CNN.us", matcher().matches("CNN").single().channel.id)
    }

    @Test fun `a near name scores below an exact one and a name sharing no word scores nothing`() {
        val near = matcher().matches("Comedy Centrals").single()
        assertEquals("a3", near.channel.id)
        assertTrue(near.confidence in 0.6..0.95, "${near.confidence}")
        assertTrue(matcher().matches("Weather").isEmpty())
    }

    @Test fun `a guide's offset moves every program`() {
        val start = Instant.parse("2026-10-25T10:00:00Z")
        val guide = ParsedGuide(emptyList(), listOf(GuideProgram("c", start, start.plusSeconds(1800), "News")))
        val moved = guide.shifted(-90).programs.single()
        assertEquals(Instant.parse("2026-10-25T08:30:00Z"), moved.startsAt)
        assertEquals(Instant.parse("2026-10-25T09:00:00Z"), moved.endsAt)
        assertEquals(guide, guide.shifted(0))
    }

    @Test fun `guide match settings and guide offsets are validated`() {
        val errors = validatePolicy(
            LineupPolicy(guideMatches = PolicyGuideMatches(ignorePrefixes = listOf(" "), acceptAbove = 1.5)),
            emptySet(),
        )
        assertTrue("guideMatches.acceptAbove must be between 0 and 1" in errors, "$errors")
        assertTrue(errors.any { it.startsWith("guideMatches.ignorePrefixes") }, "$errors")
        val failure = runCatching { GuideConfig("g", "https://guide.test/x.xml", offsetMinutes = 1441).validate() }
        assertTrue(failure.exceptionOrNull()?.message.orEmpty().contains("offsetMinutes"))
    }
}
