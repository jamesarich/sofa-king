package dev.jamesrich.sofaking.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

class AiringsTest {
    private val now = Instant.parse("2026-10-23T20:00:00Z")

    private fun channel(id: String, number: Double) = Channel(id, number, "Ch $id", null, listOf("Test"))

    private fun show(channelId: String, startHours: Int, title: String = "Columbo") =
        Program(channelId, now + startHours.hours, now + (startHours + 1).hours, title, null)

    @Test
    fun `airings of the same title on any channel come soonest first without the one shown`() {
        val shown = show("a", 2)
        val lineup = Lineup(
            channels = listOf(channel("a", 1.0), channel("b", 2.0), channel("c", 3.0)),
            guide = mapOf(
                "a" to listOf(show("a", -2), shown, show("a", 5, title = "Kojak")),
                "b" to listOf(show("b", 0, title = " columbo "), show("b", 26)),
                "c" to listOf(show("c", 2)),
            ),
        )
        val airings = lineup.airingsOf(shown, now).map { it.channel.guideId to it.program.start }
        assertEquals(listOf("b" to now, "c" to now + 2.hours, "b" to now + 26.hours), airings)
    }

    @Test
    fun `a program without a title has no other airings`() {
        val untitled = show("a", 0, title = " ")
        val lineup = Lineup(listOf(channel("a", 1.0)), mapOf("a" to listOf(untitled, show("a", 1, title = " "))))
        assertEquals(emptyList(), lineup.airingsOf(untitled, now))
    }
}
