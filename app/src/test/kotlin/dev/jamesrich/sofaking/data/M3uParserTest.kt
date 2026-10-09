package dev.jamesrich.sofaking.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class M3uParserTest {
    private val fixture = javaClass.getResource("/livingroom.m3u")!!.readText()

    @Test
    fun `parses every entry in the Dispatcharr profile playlist`() {
        val channels = M3uParser.parse(fixture)
        assertEquals(20, channels.size)
        assertTrue(channels.all { it.streamUrl.startsWith("http://") })
    }

    @Test
    fun `reads number, name, group and guide id from extinf attributes`() {
        val woi = M3uParser.parse(fixture).first { it.number == 5.0 }
        assertEquals("WOI ABC 5 Des Moines", woi.name)
        assertEquals("Local News", woi.group)
        assertEquals("5", woi.guideId)
        assertNull("empty tvg-logo is treated as no logo", woi.logo)
    }

    @Test
    fun `sorts channels by number`() {
        val numbers = M3uParser.parse(fixture).map { it.number }
        assertEquals(numbers.sorted(), numbers)
    }

    @Test
    fun `skips entries without a channel number`() {
        val text = "#EXTM3U\n#EXTINF:-1 tvg-name=\"No Number\",No Number\nhttp://x/1\n#EXTINF:-1 tvg-chno=\"7\",Seven\nhttp://x/7\n"
        assertEquals(listOf(7.0), M3uParser.parse(text).map { it.number })
    }
}
