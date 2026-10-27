package dev.jamesrich.sofaking.backend.playback

import dev.jamesrich.sofaking.backend.catalog.Fixture
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CaptionScanTest {
    @Test fun `finds a CEA-608 pair in A53 caption data however the bytes arrive`() {
        val segment = Fixture.CAPTIONED_SEGMENT
        assertTrue(carries608(segment))
        for (chunk in listOf(1, 7, 188, 300)) {
            val scan = CaptionScan()
            segment.toList().chunked(chunk).forEach { scan.feed(it.toByteArray()) }
            assertTrue(scan.found, "fed $chunk bytes at a time")
        }
    }

    @Test fun `padding, 708 data, an invalid entry or no caption data are not captions`() {
        assertFalse(carries608(Fixture.tsPacket(1)))
        assertFalse(carries608(captionData(0xF8, 0x80, 0x80)), "an entry marked not valid")
        assertFalse(carries608(captionData(0xFC, 0x80, 0x80)), "a valid pair of padding")
        assertFalse(carries608(captionData(0xFE, 0x41, 0x42)), "CEA-708 data")
        assertTrue(carries608(captionData(0xFD, 0x94, 0x20)), "a command on field 2")
    }

    @Test fun `a bare media playlist goes out inside a multivariant playlist naming CC1`() {
        val master = CaptionPlaylists.captionedMaster(URI("https://cdn.example.test/live/index.m3u8"))
        assertEquals(
            "#EXTM3U\n" +
                "#EXT-X-MEDIA:TYPE=CLOSED-CAPTIONS,GROUP-ID=\"sofa-cc\",NAME=\"CC1\",LANGUAGE=\"en\"," +
                "INSTREAM-ID=\"CC1\"," +
                "DEFAULT=NO,AUTOSELECT=YES\n" +
                "#EXT-X-STREAM-INF:BANDWIDTH=8000000,CLOSED-CAPTIONS=\"sofa-cc\"\n" +
                "https://cdn.example.test/live/index.m3u8#sofa-captioned-media\n",
            master,
        )
        assertTrue(CaptionPlaylists.isMultivariant(master))
    }

    @Test fun `a multivariant playlist gets the caption group on every variant unless it names captions`() {
        val text = "#EXTM3U\n#EXT-X-INDEPENDENT-SEGMENTS\n#EXT-X-STREAM-INF:BANDWIDTH=1\nlow.m3u8\n" +
            "#EXT-X-STREAM-INF:BANDWIDTH=2,RESOLUTION=1280x720\nhigh.m3u8\n"
        assertEquals(
            "#EXTM3U\n#EXT-X-INDEPENDENT-SEGMENTS\n" +
                "#EXT-X-MEDIA:TYPE=CLOSED-CAPTIONS,GROUP-ID=\"sofa-cc\",NAME=\"CC1\",LANGUAGE=\"en\"," +
                "INSTREAM-ID=\"CC1\"," +
                "DEFAULT=NO,AUTOSELECT=YES\n" +
                "#EXT-X-STREAM-INF:BANDWIDTH=1,CLOSED-CAPTIONS=\"sofa-cc\"\nlow.m3u8\n" +
                "#EXT-X-STREAM-INF:BANDWIDTH=2,RESOLUTION=1280x720,CLOSED-CAPTIONS=\"sofa-cc\"\nhigh.m3u8\n",
            CaptionPlaylists.withCaptionGroup(text),
        )
        assertNull(
            CaptionPlaylists.withCaptionGroup("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1,CLOSED-CAPTIONS=NONE\na\n"),
        )
        assertNull(
            CaptionPlaylists.withCaptionGroup(
                "#EXTM3U\n#EXT-X-MEDIA:TYPE=CLOSED-CAPTIONS,GROUP-ID=\"cc\",NAME=\"English\",INSTREAM-ID=\"CC1\"\n" +
                    "#EXT-X-STREAM-INF:BANDWIDTH=1,CLOSED-CAPTIONS=\"cc\"\na\n",
            ),
        )
    }

    /** A53 caption data holding one entry: [head], then the pair [first] and [second]. */
    private fun captionData(head: Int, first: Int, second: Int): ByteArray =
        byteArrayOf(0x47, 0x41, 0x39, 0x34, 3, 0x41, 0xFF.toByte(), head.toByte(), first.toByte(), second.toByte())
}
