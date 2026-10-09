package dev.jamesrich.sofaking.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.kxml2.io.KXmlParser
import java.time.Instant

class XmltvParserTest {
    private fun parseFixture(keep: Set<String>? = null): Map<String, List<Programme>> {
        val parser = KXmlParser().apply { setInput(javaClass.getResourceAsStream("/livingroom.xml"), "UTF-8") }
        return XmltvParser.parse(parser, keep)
    }

    @Test
    fun `groups programmes by channel id`() {
        val guide = parseFixture()
        assertEquals(13, guide.size)
        assertEquals(4, guide["5"]?.size)
        assertEquals(25, guide["108"]?.size)
        assertNull("channels with no guide data have no entry", guide["112"])
    }

    @Test
    fun `programmes are sorted and have titles`() {
        parseFixture().values.forEach { list ->
            assertEquals(list.sortedBy { it.start }, list)
            assertTrue(list.all { it.title.isNotBlank() && it.stop.isAfter(it.start) })
        }
    }

    @Test
    fun `keepChannels filters out other channels`() {
        assertEquals(setOf("5"), parseFixture(setOf("5")).keys)
    }

    @Test
    fun `parses xmltv timestamps with offset`() {
        assertEquals(Instant.parse("2026-10-09T11:30:00Z"), XmltvParser.parseTime("20261009113000 +0000"))
        assertEquals(Instant.parse("2026-10-09T16:30:00Z"), XmltvParser.parseTime("20261009113000 -0500"))
        assertNull(XmltvParser.parseTime("garbage"))
    }

    @Test
    fun `nowAndNext picks the airing programme and the one after it`() {
        val a = Programme("1", Instant.parse("2026-10-09T10:00:00Z"), Instant.parse("2026-10-09T11:00:00Z"), "A", null, null)
        val b = Programme("1", Instant.parse("2026-10-09T11:00:00Z"), Instant.parse("2026-10-09T12:00:00Z"), "B", null, null)
        val channel = Channel("1", 1.0, "One", null, "Test", "http://x")
        val lineup = Lineup(listOf(channel), mapOf("1" to listOf(a, b)))
        assertEquals(a to b, lineup.nowAndNext(channel, Instant.parse("2026-10-09T10:30:00Z")))
        assertEquals("boundary belongs to the next programme", b, lineup.nowAndNext(channel, Instant.parse("2026-10-09T11:00:00Z")).first)
        val (current, next) = lineup.nowAndNext(channel, Instant.parse("2026-10-09T09:00:00Z"))
        assertNull(current)
        assertNotNull(next)
    }
}
