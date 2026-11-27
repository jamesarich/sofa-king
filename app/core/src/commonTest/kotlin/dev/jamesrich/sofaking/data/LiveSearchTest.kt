package dev.jamesrich.sofaking.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

class LiveSearchTest {
    private val now = Instant.parse("2026-11-27T20:00:00Z")

    private fun channel(id: String, number: Double, name: String) = Channel(id, number, name, null, listOf("Test"))

    private fun show(channelId: String, startHours: Int, title: String, subTitle: String? = null) =
        Program(channelId, now + startHours.hours, now + (startHours + 1).hours, title, null, subTitle = subTitle)

    private val lineup = Lineup(
        channels = listOf(
            channel("a", 5.0, "Télé News"),
            channel("b", 5.1, "Movies"),
            channel("c", 12.0, "Sports"),
            channel("d", 50.0, "Weather"),
        ),
        guide = mapOf(
            "a" to listOf(show("a", 0, "Evening Report"), show("a", 3, "Late News")),
            "b" to listOf(show("b", 0, "News of the World"), show("b", 2, "Columbo", subTitle = "Bad News")),
            "c" to listOf(show("c", -1, "Old News"), show("c", 0, "Hockey"), show("c", 1, "The News Hour")),
        ),
    )

    private fun LiveSearchResults.ids() = now.map { it.channel.guideId } to later.map { it.channel.guideId }

    @Test
    fun `channels found by name come before those found by what airs on them`() {
        val found = lineup.search("news", now)
        assertEquals(listOf("a", "b"), found.now.map { it.channel.guideId })
        assertEquals(listOf("Evening Report", "News of the World"), found.now.map { it.program?.title })
    }

    @Test
    fun `programs coming up are listed soonest first and what has aired is left out`() {
        val found = lineup.search("news", now)
        assertEquals(listOf("The News Hour", "Columbo", "Late News"), found.later.map { it.program?.title })
        assertEquals(listOf("c", "b", "a"), found.later.map { it.channel.guideId })
    }

    @Test
    fun `a number finds the channels that start with it`() {
        assertEquals(listOf("a", "b", "d") to emptyList(), lineup.search("5", now).ids())
        assertEquals(listOf("b") to emptyList(), lineup.search("5.1", now).ids())
    }

    @Test
    fun `case and accents are ignored and a channel without a guide has no program`() {
        assertEquals(listOf("a") to emptyList(), lineup.search("  TELE ", now).ids())
        val weather = lineup.search("weather", now).now.single()
        assertEquals(null, weather.program)
        assertEquals("live:d:0", weather.key)
    }

    @Test
    fun `a blank query finds nothing and each row stops at its limit`() {
        assertFalse(lineup.search("  ", now).found)
        val found = lineup.search("news", now, limit = 1)
        assertTrue(found.found)
        assertEquals(listOf("a") to listOf("c"), found.ids())
    }

    @Test
    fun `the hit on a channel is the one a page returns to`() {
        val found = lineup.search("news", now)
        assertEquals("Evening Report", found.onChannel("a")?.program?.title)
        assertEquals("The News Hour", found.onChannel("c")?.program?.title)
        assertEquals(null, found.onChannel("d"))
    }
}
