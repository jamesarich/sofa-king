package dev.jamesrich.sofaking.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SubtitlesTest {
    @Test
    fun `WebVTT cues read with short and long times and lose their markup`() {
        val subtitles = parseWebVtt(
            "WEBVTT\r\n\r\nNOTE made by FFmpeg\r\n\r\n00:01.000 --> 00:03.000\r\nHello <i>there</i>\r\n\r\n" +
                "intro\n01:00:04.500 --> 01:00:06.000 align:start\nTom &amp; Jerry\nsecond line\n",
        )
        assertEquals(
            listOf(SubtitleCue(1_000, 3_000, "Hello there"), SubtitleCue(3_604_500, 3_606_000, "Tom & Jerry\nsecond line")),
            subtitles.cues,
        )
    }

    @Test
    fun `the text at a position is every cue showing then`() {
        val subtitles = Subtitles(listOf(SubtitleCue(5_000, 9_000, "b"), SubtitleCue(1_000, 6_000, "a"), SubtitleCue(10_000, 11_000, "c")))
        assertNull(subtitles.textAt(500))
        assertEquals("a", subtitles.textAt(1_000))
        assertEquals("a\nb", subtitles.textAt(5_500))
        assertEquals("b", subtitles.textAt(6_000), "a cue ends at its end time")
        assertNull(subtitles.textAt(9_500))
        assertEquals("c", subtitles.textAt(10_999))
    }

    @Test
    fun `times that are not WebVTT read as nothing`() {
        assertEquals(61_250, vttTime("01:01.25"))
        assertNull(vttTime("61"))
        assertNull(vttTime("aa:bb.ccc"))
    }
}
