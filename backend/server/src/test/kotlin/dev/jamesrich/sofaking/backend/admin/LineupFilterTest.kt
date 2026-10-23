package dev.jamesrich.sofaking.backend.admin

import dev.jamesrich.sofaking.backend.api.GuideMapping
import dev.jamesrich.sofaking.backend.api.PolicyStation
import dev.jamesrich.sofaking.backend.catalog.LineupEntryRecord
import io.ktor.http.parametersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class LineupFilterTest {
    private fun row(number: String, name: String, group: String? = null, enabled: Boolean = true, required: Boolean = false, disabledReason: String? = null, key: String? = null, guideStatus: String = "empty") =
        StationRow(
            LineupEntryRecord("id-$number", number, name, group, enabled = enabled, guide = GuideMapping("g", number)),
            key?.let { PolicyStation("id-$number", it, number, name, group, enabled = enabled, required = required, disabledReason = disabledReason) },
            guideStatus,
        )

    private val rows = listOf(
        row("5", "News Channel", "News", key = "news-channel", guideStatus = "available"),
        row("7", "Sports One", "Sports", required = true, key = "sports-one", guideStatus = "stale"),
        row("12", "Kids Hour", "Kids", enabled = false, disabledReason = "unverified", key = "kids-hour"),
        row("42", "Movies", "Movies", enabled = false, key = "movies"),
    )

    private fun numbers(filter: LineupFilter) = rows.filter(filter::admits).map { it.entry.number }

    @Test fun `parameters are trimmed, clamped and limited to the known values`() {
        val filter = LineupFilter.from(parametersOf("q" to listOf("  news "), "group" to listOf("News"), "state" to listOf("hidden"), "required" to listOf("true"), "page" to listOf("3"), "guide" to listOf("missing")))
        assertEquals(LineupFilter("news", "News", "hidden", true, 3, "missing"), filter)
        assertEquals(LineupFilter(), LineupFilter.from(parametersOf()))
        val odd = LineupFilter.from(parametersOf("q" to listOf("x".repeat(300)), "state" to listOf("bogus"), "guide" to listOf("all"), "page" to listOf("-4"), "required" to listOf("yes")))
        assertEquals(200, odd.q.length)
        assertEquals("", odd.state, "an unknown state is dropped")
        assertEquals("", odd.guide, "an unknown guide filter is dropped")
        assertEquals(1, odd.page, "a page below one is the first")
        assertFalse(odd.required, "only the exact value true requires")
        assertEquals(1, LineupFilter.from(parametersOf("page" to listOf("two"))).page)
    }

    @Test fun `the text filter matches the name, the exact number or the station key`() {
        assertEquals(listOf("5"), numbers(LineupFilter(q = "news")))
        assertEquals(listOf("5"), numbers(LineupFilter(q = "NEWS-channel")), "the key matches regardless of case")
        assertEquals(listOf("12"), numbers(LineupFilter(q = "12")))
        assertEquals(emptyList(), numbers(LineupFilter(q = "1")), "a number matches whole, not as a prefix")
        assertEquals(listOf("5", "7", "12", "42"), numbers(LineupFilter()))
    }

    @Test fun `group, state, required and guide filters narrow the rows together`() {
        assertEquals(listOf("7"), numbers(LineupFilter(group = "Sports")))
        assertEquals(listOf("5", "7"), numbers(LineupFilter(state = "enabled")))
        assertEquals(listOf("12", "42"), numbers(LineupFilter(state = "hidden")))
        assertEquals(listOf("12"), numbers(LineupFilter(state = "unverified")))
        assertEquals(listOf("7"), numbers(LineupFilter(required = true)))
        assertEquals(listOf("5"), numbers(LineupFilter(guide = "available")))
        assertEquals(listOf("7", "12", "42"), numbers(LineupFilter(guide = "missing")), "stale counts as missing")
        assertEquals(emptyList(), numbers(LineupFilter(group = "News", state = "hidden")))
        assertEquals(listOf("12"), numbers(LineupFilter(q = "kids", state = "hidden", guide = "missing")))
    }

    @Test fun `the query string round-trips and leaves out defaults and the first page`() {
        assertEquals("", LineupFilter().query())
        val filter = LineupFilter("a b", "News & Sport", "hidden", true, 2, "missing")
        val query = filter.query()
        assertEquals("q=a+b&group=News+%26+Sport&state=hidden&guide=missing&required=true&page=2", query)
        assertEquals(filter, LineupFilter.from(io.ktor.http.parseQueryString(query)))
        assertEquals("state=hidden", LineupFilter(state = "hidden", page = 1).query())
        assertEquals("state=hidden&page=4", LineupFilter(state = "hidden").query(page = 4))
    }
}
