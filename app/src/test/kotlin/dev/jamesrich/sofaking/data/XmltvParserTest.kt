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
    fun `hand parser handles positive offsets and surrounding whitespace`() {
        assertEquals(Instant.parse("2026-10-09T06:00:00Z"), XmltvParser.parseTime("20261009113000 +0530"))
        assertEquals(Instant.parse("2026-10-09T11:30:00Z"), XmltvParser.parseTime("  20261009113000 +0000 \n"))
        assertEquals(Instant.parse("2026-10-08T23:30:00Z"), XmltvParser.parseTime("20261009000000 +0030"))
    }

    @Test
    fun `hand parser rejects malformed and out of range values`() {
        assertNull("offset is required", XmltvParser.parseTime("20261009113000"))
        assertNull("no separator", XmltvParser.parseTime("20261009113000+0000"))
        assertNull("non-digit in date", XmltvParser.parseTime("2026100911300x +0000"))
        assertNull("bad sign", XmltvParser.parseTime("20261009113000 *0000"))
        assertNull("month 13", XmltvParser.parseTime("20261309113000 +0000"))
        assertNull("hour 24", XmltvParser.parseTime("20261009243000 +0000"))
        assertNull("offset minutes 60", XmltvParser.parseTime("20261009113000 +0060"))
        assertNull("February 30", XmltvParser.parseTime("20260230113000 +0000"))
    }

    @Test
    fun `skipped channels do not contribute programmes even when malformed`() {
        val xml = """
            <tv>
              <programme channel="1" start="20261009100000 +0000" stop="20261009110000 +0000"><title>Kept</title></programme>
              <programme channel="2" start="bogus" stop="20261009110000 +0000"><title>Dropped</title></programme>
              <programme channel="3" start="20261009100000 +0000" stop="20261009110000 +0000"><title>Also dropped</title><desc>x</desc></programme>
            </tv>
        """.trimIndent()
        val parser = KXmlParser().apply { setInput(xml.byteInputStream(), "UTF-8") }
        val guide = XmltvParser.parse(parser, setOf("1"))
        assertEquals(setOf("1"), guide.keys)
        assertEquals("Kept", guide.getValue("1").single().title)
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

    @Test
    fun `zero and negative programme durations are discarded`() {
        val parser = KXmlParser().apply {
            setInput("""<tv>
                <programme channel="5" start="20261009120000 +0000" stop="20261009120000 +0000"><title>Zero</title></programme>
                <programme channel="5" start="20261009120000 +0000" stop="20261009110000 +0000"><title>Negative</title></programme>
                <programme channel="5" start="20261009120000 +0000" stop="20261009130000 +0000"><title>Valid</title></programme>
                </tv>""".trimIndent().reader())
        }
        assertEquals("Valid", XmltvParser.parse(parser)["5"]?.single()?.title)
    }
}
