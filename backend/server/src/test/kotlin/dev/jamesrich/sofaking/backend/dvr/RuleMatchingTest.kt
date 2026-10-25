package dev.jamesrich.sofaking.backend.dvr

import dev.jamesrich.sofaking.backend.catalog.ApiFailure
import dev.jamesrich.sofaking.backend.catalog.GuideProgram
import dev.jamesrich.sofaking.contract.RuleMatch
import dev.jamesrich.sofaking.contract.Wire
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RuleMatchingTest {
    private val now = Instant.parse("2026-10-25T12:00:00Z")

    private fun rule(title: String, match: RuleMatch) =
        StoredRule("r", title, null, false, 0, Duration.ZERO, Duration.ZERO, 0, now, match)

    private fun program(
        title: String,
        subTitle: String? = null,
        description: String? = null,
        categories: List<String> = emptyList(),
    ) = GuideProgram("c", now, now.plusSeconds(3600), title, description, subTitle, categories = categories)

    @Test fun `a team matches as a whole word in the title or episode title of a sports program`() {
        val packers = RuleMatcher(rule("Packers", ruleMatch(RuleMatch(Wire.RuleMatchKind.TEAM), "Packers")))
        assertTrue(packers.takes(program("NFL Football", "Bears at Packers", categories = listOf("Sports event"))))
        assertTrue(packers.takes(program("Packers vs. Lions", categories = listOf("Football", "Sports"))))
        assertFalse(packers.takes(program("Bears at Packers")), "no Sports category")
        assertFalse(packers.takes(program("Meatpackers", categories = listOf("Sports"))), "inside a longer word")
        assertFalse(
            packers.takes(program("NFL Football", description = "Packers visit", categories = listOf("Sports"))),
            "a team never matches in the description",
        )
    }

    @Test fun `keywords match anywhere, in the categories given or in any`() {
        val any = RuleMatcher(rule("Space", RuleMatch(Wire.RuleMatchKind.KEYWORD, listOf("SpaceX", "moon landing"))))
        assertTrue(any.takes(program("Science Hour", description = "The Moon Landing, fifty years on.")))
        assertTrue(any.takes(program("Launch", "SpaceX Starship")))
        assertFalse(any.takes(program("Moonlanding")))
        val docs = RuleMatcher(
            rule("Space", RuleMatch(Wire.RuleMatchKind.KEYWORD, listOf("SpaceX"), listOf("Documentary"))),
        )
        assertFalse(docs.takes(program("SpaceX launch", categories = listOf("News"))))
        assertTrue(docs.takes(program("SpaceX launch", categories = listOf("Documentary"))))
    }

    @Test fun `a title rule matches its title only, and an unknown kind matches nothing`() {
        assertTrue(RuleMatcher(rule("News", RuleMatch())).takes(program(" NEWS ")))
        assertFalse(RuleMatcher(rule("News", RuleMatch())).takes(program("News at Ten")))
        assertFalse(RuleMatcher(rule("News", RuleMatch("genre", listOf("News")))).takes(program("News")))
    }

    @Test fun `a new rule's match is cleaned and checked`() {
        assertEquals(RuleMatch(), ruleMatch(RuleMatch(Wire.RuleMatchKind.TITLE, listOf("ignored")), "News"))
        assertEquals(
            RuleMatch(Wire.RuleMatchKind.TEAM, listOf("Cubs"), listOf("Baseball")),
            ruleMatch(RuleMatch(Wire.RuleMatchKind.TEAM, listOf(" Cubs", "cubs", " "), listOf("Baseball")), "Chicago"),
        )
        assertFailsWith<ApiFailure> { ruleMatch(RuleMatch(Wire.RuleMatchKind.KEYWORD), "News") }
        assertFailsWith<ApiFailure> { ruleMatch(RuleMatch("genre"), "News") }
        assertFailsWith<ApiFailure> { ruleMatch(RuleMatch(Wire.RuleMatchKind.TEAM, List(21) { "t$it" }), "News") }
    }

    @Test fun `the planner schedules what a team rule takes`() {
        val rule = rule("Packers", RuleMatch(Wire.RuleMatchKind.TEAM, listOf("Packers"), listOf("Sports")))
        val game = Airing("a", "A", program("NFL Football", "Bears at Packers", categories = listOf("Sports")))
        val other = Airing("a", "A", program("NFL Football", "Bears at Lions", categories = listOf("Sports")))
        val plan = DvrPlanner.plan(listOf(rule), listOf(game, other), emptyList(), now.minusSeconds(60))
        assertEquals(listOf(game), plan.add.map { it.second })
    }
}
